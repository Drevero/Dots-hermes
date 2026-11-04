import { removeAgent } from "../store";
import type { WebAgent } from "../types";
import {
  EMPTY_SNAPSHOT,
  MAIN_CHAT,
  chatOf,
  toMessage,
  toTool,
  type Activity,
  type ChatMessage,
  type Delivery,
  type GatewaySnapshot,
  type ToolCall,
} from "./model";
import { toolLabel } from "./toolLabels";

/**
 * Connexion d'un navigateur à UN gateway (un agent Hermes), voir docs/appairage-web.md §5 et
 * docs/protocole-gateway.md.
 *
 * Le gateway est la source de vérité : à chaque (re)connexion, `hello` rejoue ce qui a été manqué
 * depuis `lastSeq`, puis les messages tapés hors ligne repartent (le plugin déduplique par
 * `client_id`). Rien n'est gardé entre deux chargements de la page : on repart de la page récente.
 *
 * L'état exposé est un instantané immuable ([GatewaySnapshot]) : `subscribe` + `getSnapshot`, lus
 * par le hook `useGateway`.
 */

/** Backoff de reconnexion, comme l'app Android (GatewayChatRepository.ReconnectBackoffMs). */
const BACKOFF_MS = [1_000, 2_000, 5_000, 10_000, 30_000];
/** Une socket qui ne s'ouvre pas (ou sans `welcome`) au bout de ce délai est abandonnée. */
const CONNECT_TIMEOUT_MS = 15_000;
/** Code de fermeture du gateway : jeton web inconnu ou révoqué. */
const CLOSE_UNAUTHORIZED = 4401;
const PAGE_SIZE = 80;
const MAX_PAGE = 200;
const FETCH_TIMEOUT_MS = 60_000;

type Json = Record<string, any>;
type PageMode = "older" | "refresh";

interface PendingFetch {
  resolve: (blob: Blob) => void;
  reject: (error: Error) => void;
  timer: number;
}

const DELIVERY_RANK: Record<Delivery, number> = { sending: 0, sent: 1, read: 2 };

export class GatewayClient {
  agent: WebAgent;
  private snapshot: GatewaySnapshot = EMPTY_SNAPSHOT;
  private readonly listeners = new Set<() => void>();

  private ws: WebSocket | null = null;
  /** `welcome` reçu sur la socket courante : on peut envoyer. */
  private ready = false;
  private lastSeq = 0;
  private oldestSeq = 0;
  private attempt = 0;
  private retryTimer: number | null = null;
  private connectTimer: number | null = null;
  private disposed = false;
  /** Demandes `history_page` en vol, dans l'ordre : le gateway répond dans le même ordre. */
  private pageQueue: PageMode[] = [];
  private readonly fetches = new Map<string, PendingFetch>();

  private foreground = false;
  private active = false;

  constructor(agent: WebAgent, foreground: boolean) {
    this.agent = agent;
    this.foreground = foreground;
  }

  /* -- Observable --------------------------------------------------------------------------- */

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): GatewaySnapshot => this.snapshot;

  private set(patch: Partial<GatewaySnapshot>): void {
    this.snapshot = { ...this.snapshot, ...patch };
    this.listeners.forEach((l) => l());
  }

  /* -- Cycle de vie ---------------------------------------------------------------------------- */

  start(): void {
    if (this.disposed || this.ws) return;
    this.open();
  }

  dispose(): void {
    this.disposed = true;
    this.clearTimers();
    this.fetches.forEach((f) => {
      clearTimeout(f.timer);
      f.reject(new Error("closed"));
    });
    this.fetches.clear();
    const ws = this.ws;
    this.ws = null;
    if (ws && ws.readyState <= WebSocket.OPEN) ws.close(1000);
    this.listeners.clear();
  }

  /** Réseau revenu, onglet de nouveau visible : on n'attend pas la fin du backoff. */
  wake(): void {
    if (this.disposed || this.ws) return;
    this.attempt = 0;
    this.open();
  }

  /** Nouvelle version de l'agent dans le store (nom, palette) : même gateway, même session. */
  setAgent(agent: WebAgent): void {
    this.agent = agent;
  }

  /**
   * Présence déclarée au gateway : onglet visible et actif ET cet agent à l'écran. Un gateway qui
   * croit l'app au premier plan ne pousse plus vers le téléphone : un agent caché derrière un autre
   * doit continuer à notifier.
   */
  private get present(): boolean {
    return this.foreground && this.active;
  }

  private updatePresence(change: () => void): void {
    const before = this.present;
    change();
    if (this.ready && this.present !== before) this.sendFrame({ type: "presence", foreground: this.present });
    this.clearUnreadIfSeen();
  }

  /** Onglet visible et actif. */
  setForeground(foreground: boolean): void {
    this.updatePresence(() => (this.foreground = foreground));
  }

  /** Cet agent est celui affiché dans le chat. */
  setActive(active: boolean): void {
    this.updatePresence(() => (this.active = active));
  }

  private clearUnreadIfSeen(): void {
    if (this.active && this.foreground && this.snapshot.unread > 0) this.set({ unread: 0 });
  }

  /* -- Transport ------------------------------------------------------------------------------- */

  private open(): void {
    this.clearTimers();
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.agent.url, ["dots.v1", "dots.token." + this.agent.token]);
    } catch {
      // URL invalide : rien à espérer d'une reconnexion rapide, mais on retente quand même au rythme lent.
      this.set({ status: "offline" });
      this.scheduleRetry();
      return;
    }
    this.ws = ws;
    this.ready = false;
    this.pageQueue = [];
    this.set({ status: "connecting" });
    this.connectTimer = window.setTimeout(() => {
      if (this.ws === ws && !this.ready) ws.close();
    }, CONNECT_TIMEOUT_MS);

    ws.onopen = () => {
      if (this.ws !== ws) return;
      // Pas de `lang` : le gateway la garde pour tous ses textes (push, libellés), c'est celle du téléphone.
      ws.send(JSON.stringify({ type: "hello", last_seq: this.lastSeq, foreground: this.present }));
    };
    ws.onmessage = (e) => {
      if (this.ws !== ws || typeof e.data !== "string") return;
      let frame: Json;
      try {
        frame = JSON.parse(e.data);
      } catch {
        return;
      }
      this.onFrame(frame);
    };
    ws.onclose = (e) => {
      if (this.ws !== ws) return;
      this.onDropped(e.code);
    };
  }

  private onDropped(code: number): void {
    this.ws = null;
    this.ready = false;
    this.pageQueue = [];
    if (this.connectTimer != null) clearTimeout(this.connectTimer);
    if (this.disposed) return;
    if (code === CLOSE_UNAUTHORIZED) {
      // Jeton révoqué depuis le téléphone (ou déconnexion depuis ce navigateur) : on oublie cet agent.
      this.disposed = true;
      removeAgent(this.agent);
      return;
    }
    this.set({ status: "offline", activity: "idle", loadingOlder: false });
    this.scheduleRetry();
  }

  private scheduleRetry(): void {
    const wait = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
    this.attempt++;
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null;
      if (!this.disposed && !this.ws) this.open();
    }, wait);
  }

  private clearTimers(): void {
    if (this.retryTimer != null) clearTimeout(this.retryTimer);
    if (this.connectTimer != null) clearTimeout(this.connectTimer);
    this.retryTimer = this.connectTimer = null;
  }

  private sendFrame(frame: Json): boolean {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    ws.send(JSON.stringify(frame));
    return true;
  }

  /* -- Trames reçues --------------------------------------------------------------------------- */

  private bumpSeq(frame: Json): void {
    if (typeof frame.seq === "number" && frame.seq > this.lastSeq) this.lastSeq = frame.seq;
  }

  private onFrame(frame: Json): void {
    switch (frame.type) {
      case "welcome":
        return this.onWelcome(frame);
      case "history_page":
        return this.onHistoryPage(frame);
      case "ack":
        this.bumpSeq(frame);
        if (chatOf(frame) !== MAIN_CHAT) return;
        return this.updateMessages(
          (m) => m.key === frame.client_id && m.id == null,
          (m) => ({
            ...m,
            id: String(frame.id),
            seq: Number(frame.seq) || 0,
            ts: Number(frame.ts) || m.ts,
            delivery: maxDelivery(m.delivery, "sent"),
          }),
        );
      case "read":
        return this.updateMessages(
          (m) => m.id === frame.id,
          (m) => ({ ...m, delivery: "read" }),
        );
      case "activity":
        if (chatOf(frame) === MAIN_CHAT) this.set({ activity: toActivity(frame.state) });
        return;
      case "message":
      case "approval":
      case "clarify":
      case "tool":
        this.bumpSeq(frame);
        if (chatOf(frame) !== MAIN_CHAT) return;
        return this.merge([frame], true);
      case "edit":
        // Pas de `chat` sur `edit` : l'id suffit, il n'existe que dans une conversation.
        return this.updateMessages(
          (m) => m.id === frame.id,
          (m) => ({ ...m, text: String(frame.text ?? ""), final: frame.final !== false }),
        );
      case "tool_update":
        return this.updateTools(frame.id, (t) => ({
          ...t,
          state: frame.state === "error" ? "error" : "done",
          durationMs: typeof frame.duration_ms === "number" ? frame.duration_ms : t.durationMs,
        }));
      case "reaction":
        return this.updateMessages(
          (m) => m.id === frame.message_id,
          (m) => ({ ...m, reactions: { ...m.reactions, agent: frame.emoji || undefined } }),
        );
      case "approval_resolved":
        return this.updateMessages(
          (m) => m.id === frame.approval_id && !!m.approval,
          (m) => ({ ...m, approval: { ...m.approval!, resolved: String(frame.choice || "deny") } }),
        );
      case "transcript":
        return this.updateMessages(
          (m) => m.id === frame.id,
          (m) => ({ ...m, transcript: String(frame.text ?? "") }),
        );
      case "takeover_done":
        return this.updateMessages(
          (m) => m.id === frame.id && m.kind === "takeover",
          (m) => ({ ...m, status: frame.expired ? "expired" : "done" }),
        );
      case "audio":
      case "file_data":
        return this.onFileData(frame);
      case "error":
        console.warn(`[dots] ${this.agent.name}: gateway error`, frame.code);
        return;
      default:
      // Sous-dots, écran, activité… : hors du périmètre de cette version.
    }
  }

  private onWelcome(frame: Json): void {
    if (this.connectTimer != null) clearTimeout(this.connectTimer);
    this.ready = true;
    this.attempt = 0;
    const history: Json[] = Array.isArray(frame.history) ? frame.history : [];
    const mine = history.filter((e) => chatOf(e) === MAIN_CHAT);
    const activity = toActivity(frame.activities?.[MAIN_CHAT] ?? frame.activity);

    if (frame.reset) {
      // Nouvelle fenêtre (premier chargement, ou trop de retard) : on remplace le fil, en gardant
      // ce qui a été tapé ici et pas encore enregistré.
      const unsent = this.snapshot.messages.filter((m) => m.role === "user" && m.id == null);
      this.snapshot = { ...this.snapshot, messages: unsent, tools: [] };
      this.oldestSeq = minSeq(mine);
      this.merge(mine, false);
      this.set({ hasMore: !!frame.has_more, loaded: true });
    } else {
      // Rattrapage d'une coupure : ce que l'agent a écrit entre-temps compte comme non lu.
      this.merge(mine, true);
      if (!this.snapshot.loaded) this.set({ loaded: true });
      if (this.oldestSeq === 0) this.oldestSeq = minSeq(mine);
      // Une réponse en streaming, un outil ou une carte peut avoir changé pendant la coupure : ces
      // mises à jour modifient l'événement sans nouveau `seq`, donc `hello` ne les rejoue pas.
      if (this.hasStaleEvents()) this.requestPage(this.lastSeq + 1, MAX_PAGE, "refresh");
    }
    if (typeof frame.seq === "number") this.lastSeq = Math.max(this.lastSeq, frame.seq);
    this.set({ status: "online", serverName: frame.agent ? String(frame.agent) : null, activity });
    this.flushUnsent();
  }

  private hasStaleEvents(): boolean {
    const { messages, tools } = this.snapshot;
    return (
      messages.some(
        (m) =>
          m.id != null &&
          ((m.role === "agent" && !m.final) ||
            (m.approval && !m.approval.resolved) ||
            (m.kind === "takeover" && m.status === "pending")),
      ) || tools.some((t) => t.state === "running")
    );
  }

  private onHistoryPage(frame: Json): void {
    if (chatOf(frame) !== MAIN_CHAT) return;
    const mode = this.pageQueue.shift() ?? "older";
    const events: Json[] = Array.isArray(frame.events) ? frame.events : [];
    this.merge(events, false);
    if (mode === "older") {
      const oldest = minSeq(events);
      if (oldest > 0) this.oldestSeq = this.oldestSeq === 0 ? oldest : Math.min(this.oldestSeq, oldest);
      this.set({ hasMore: !!frame.has_more && events.length > 0, loadingOlder: false });
    }
  }

  private onFileData(frame: Json): void {
    const pending = this.fetches.get(String(frame.id));
    if (!pending) return;
    this.fetches.delete(String(frame.id));
    clearTimeout(pending.timer);
    if (frame.error || typeof frame.data !== "string") {
      pending.reject(new Error(String(frame.error || "not_found")));
      return;
    }
    const mime = String(frame.mime || "application/octet-stream");
    // Décodage base64 par le navigateur (data: URL), sans boucle JS sur des Mo de texte.
    fetch(`data:${mime};base64,${frame.data}`)
      .then((r) => r.blob())
      .then(pending.resolve, pending.reject);
  }

  /* -- Fusion des événements ------------------------------------------------------------------- */

  /**
   * Insère ou remplace par id les messages et outils reçus (welcome, page, trame live), en gardant
   * ce que seul ce navigateur sait : clé locale, réactions, réponse à une question.
   */
  private merge(events: Json[], live: boolean): void {
    if (events.length === 0) return;
    let messages = this.snapshot.messages;
    let tools = this.snapshot.tools;
    let unread = this.snapshot.unread;
    let messagesChanged = false;
    let toolsChanged = false;

    for (const event of events) {
      if (event.type === "tool") {
        const tool = toTool(event, toolLabel);
        if (!tool) continue;
        const index = tools.findIndex((t) => t.id === tool.id);
        tools = index < 0 ? [...tools, tool] : tools.map((t, i) => (i === index ? { ...tool, seq: tool.seq || t.seq } : t));
        toolsChanged = true;
        continue;
      }
      const incoming = toMessage(event);
      if (!incoming) continue;
      let index = messages.findIndex((m) => m.id === incoming.id);
      if (index < 0 && incoming.role === "user" && incoming.kind === "text") {
        // L'ack d'un message a pu se perdre dans une coupure : le plugin ignore alors le renvoi
        // (même client_id), mais le rejoue dans l'historique. On le reconnaît à son texte.
        index = messages.findIndex((m) => m.role === "user" && m.id == null && m.text.trim() === incoming.text.trim());
      }
      if (index < 0) {
        const answer = incoming.clarify ? clarifyAnswers.get(incoming.id!) : undefined;
        messages = [...messages, answer ? { ...incoming, clarify: { ...incoming.clarify!, answer } } : incoming];
        if (live && incoming.role === "agent" && !(this.active && this.foreground)) unread++;
      } else {
        const existing = messages[index];
        const merged: ChatMessage = {
          ...incoming,
          key: existing.key,
          reactions: { ...existing.reactions, ...incoming.reactions },
          delivery: incoming.role === "user" ? maxDelivery(existing.delivery, incoming.delivery) : incoming.delivery,
          transcript: incoming.transcript ?? existing.transcript,
          clarify: incoming.clarify ? { ...incoming.clarify, answer: existing.clarify?.answer ?? null } : undefined,
          approval:
            incoming.approval && existing.approval
              ? { ...incoming.approval, resolved: incoming.approval.resolved ?? existing.approval.resolved }
              : incoming.approval,
        };
        messages = messages.map((m, i) => (i === index ? merged : m));
      }
      messagesChanged = true;
    }
    const patch: Partial<GatewaySnapshot> = {};
    if (messagesChanged) patch.messages = sortMessages(messages);
    if (toolsChanged) patch.tools = tools;
    if (unread !== this.snapshot.unread) patch.unread = unread;
    if (messagesChanged || toolsChanged) this.set(patch);
  }

  private updateMessages(match: (m: ChatMessage) => boolean, transform: (m: ChatMessage) => ChatMessage): void {
    const { messages } = this.snapshot;
    if (!messages.some(match)) return;
    this.set({ messages: messages.map((m) => (match(m) ? transform(m) : m)) });
  }

  private updateTools(id: unknown, transform: (t: ToolCall) => ToolCall): void {
    const { tools } = this.snapshot;
    if (!tools.some((t) => t.id === id)) return;
    this.set({ tools: tools.map((t) => (t.id === id ? transform(t) : t)) });
  }

  private requestPage(before: number, limit: number, mode: PageMode): boolean {
    if (!this.ready || !this.sendFrame({ type: "history_page", chat: MAIN_CHAT, before_seq: before, limit })) return false;
    this.pageQueue.push(mode);
    return true;
  }

  /** Renvoie ce qui a été tapé pendant la coupure ; le plugin déduplique par client_id. */
  private flushUnsent(): void {
    for (const m of this.snapshot.messages) {
      if (m.role === "user" && m.id == null && m.kind === "text") {
        this.sendFrame({ type: "message", client_id: m.key, text: m.text, chat: MAIN_CHAT });
      }
    }
  }

  /* -- Actions de l'interface ------------------------------------------------------------------ */

  /** Bulle « Envoi… » tout de suite ; part maintenant si la socket est prête, sinon au prochain welcome. */
  send(text: string): void {
    const trimmed = text.trim();
    if (!trimmed) return;
    const message: ChatMessage = {
      key: newClientId(),
      id: null,
      type: "message",
      role: "user",
      kind: "text",
      text: trimmed,
      ts: Date.now(),
      seq: 0,
      turn: null,
      final: true,
      delivery: "sending",
      reactions: {},
    };
    this.set({ messages: [...this.snapshot.messages, message] });
    if (this.ready) this.sendFrame({ type: "message", client_id: message.key, text: trimmed, chat: MAIN_CHAT });
  }

  /** Double-clic sur une bulle : pose ou retire la réaction de l'utilisateur. */
  toggleReaction(key: string, emoji: string): void {
    const target = this.snapshot.messages.find((m) => m.key === key);
    if (!target?.id || !this.ready) return;
    const next = target.reactions.user === emoji ? null : emoji;
    this.updateMessages(
      (m) => m.key === key,
      (m) => ({ ...m, reactions: { ...m.reactions, user: next ?? undefined } }),
    );
    this.sendFrame({ type: "reaction", message_id: target.id, emoji: next });
  }

  /** Approuver = `once`, Refuser = `deny`, comme l'app. */
  answerApproval(key: string, approve: boolean): void {
    const target = this.snapshot.messages.find((m) => m.key === key);
    if (!target?.approval || target.approval.resolved || !target.id) return;
    const choice = approve ? "once" : "deny";
    if (!this.sendFrame({ type: "approval", approval_id: target.id, choice })) return;
    this.updateMessages(
      (m) => m.key === key,
      (m) => ({ ...m, approval: { ...m.approval!, resolved: choice } }),
    );
  }

  answerClarify(key: string, response: string): void {
    const target = this.snapshot.messages.find((m) => m.key === key);
    if (!target?.clarify || target.clarify.answer != null || !target.id || !response.trim()) return;
    if (!this.sendFrame({ type: "clarify", clarify_id: target.id, response })) return;
    clarifyAnswers.set(target.id, response);
    this.updateMessages(
      (m) => m.key === key,
      (m) => ({ ...m, clarify: { ...m.clarify!, answer: response } }),
    );
  }

  /** Interrompt le tour en cours (équivalent de `/stop`, non écrit dans la conversation). */
  stop(): void {
    this.sendFrame({ type: "stop", chat: MAIN_CHAT });
  }

  /** Page plus ancienne, quand le haut du fil apparaît. */
  loadOlder(): void {
    const s = this.snapshot;
    if (!s.hasMore || s.loadingOlder || this.oldestSeq <= 1) return;
    if (this.requestPage(this.oldestSeq, PAGE_SIZE, "older")) this.set({ loadingOlder: true });
  }

  /** Contenu d'une pièce jointe (`file_get`) ou d'un vocal (`audio_get`) de l'historique. */
  fetchAttachment(id: string, kind: "file" | "audio"): Promise<Blob> {
    return new Promise((resolve, reject) => {
      if (this.fetches.has(id)) {
        reject(new Error("busy"));
        return;
      }
      if (!this.ready || !this.sendFrame({ type: kind === "audio" ? "audio_get" : "file_get", id })) {
        reject(new Error("offline"));
        return;
      }
      const timer = window.setTimeout(() => {
        this.fetches.delete(id);
        reject(new Error("timeout"));
      }, FETCH_TIMEOUT_MS);
      this.fetches.set(id, { resolve, reject, timer });
    });
  }

  /**
   * « Déconnecter ce navigateur » : révoque la session web côté gateway (`web_revoke` sans id, la
   * seule forme permise à un navigateur), puis oublie l'agent. Hors ligne, le jeton reste valable
   * sur le gateway jusqu'à révocation depuis le téléphone ; on l'oublie quand même ici.
   */
  logout(): void {
    const ws = this.ws;
    const sent = this.sendFrame({ type: "web_revoke" });
    this.disposed = true;
    this.clearTimers();
    this.ws = null;
    removeAgent(this.agent);
    // Laisse au gateway le temps de lire la trame : il ferme lui-même la socket (4401).
    if (ws) window.setTimeout(() => ws.readyState <= WebSocket.OPEN && ws.close(1000), sent ? 3_000 : 0);
  }
}

/* -- Utilitaires ------------------------------------------------------------------------------- */

function toActivity(state: unknown): Activity {
  return state === "thinking" || state === "typing" ? state : "idle";
}

function maxDelivery(a: Delivery, b: Delivery): Delivery {
  return DELIVERY_RANK[a] >= DELIVERY_RANK[b] ? a : b;
}

function minSeq(events: Json[]): number {
  let min = 0;
  for (const e of events) if (typeof e.seq === "number" && e.seq > 0 && (min === 0 || e.seq < min)) min = e.seq;
  return min;
}

/** Ordre du fil : par `seq` (ordre du serveur), les messages pas encore enregistrés à la fin. */
function sortMessages(messages: ChatMessage[]): ChatMessage[] {
  return [...messages].sort((a, b) => {
    if (a.seq && b.seq) return a.seq - b.seq;
    if (a.seq) return -1;
    if (b.seq) return 1;
    return a.ts - b.ts;
  });
}

function newClientId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    // Contexte non sécurisé (http hors localhost) : randomUUID n'existe pas.
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 12);
  }
}

/**
 * Réponses aux questions (`clarify`) données depuis ce navigateur. Le gateway ne garde pas la
 * réponse dans l'historique : sans ce petit registre, la carte se rouvrirait au rechargement.
 */
const clarifyAnswers = (() => {
  const KEY = "dots.clarify.v1";
  let map: Record<string, string> = {};
  try {
    map = JSON.parse(localStorage.getItem(KEY) || "{}");
  } catch {
    /* stockage indisponible */
  }
  return {
    get: (id: string): string | undefined => map[id],
    set(id: string, answer: string) {
      map[id] = answer;
      const keys = Object.keys(map);
      if (keys.length > 200) for (const k of keys.slice(0, keys.length - 200)) delete map[k];
      try {
        localStorage.setItem(KEY, JSON.stringify(map));
      } catch {
        /* idem */
      }
    },
  };
})();

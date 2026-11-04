/**
 * Modèle du fil tel que le chat l'affiche, construit à partir des trames du gateway
 * (docs/protocole-gateway.md). Tout est immuable : chaque changement produit un nouvel objet, ce
 * qui permet à Preact de comparer par référence.
 */

export type Activity = "idle" | "thinking" | "typing";
export type ConnectionStatus = "connecting" | "online" | "offline";
export type Delivery = "sending" | "sent" | "read";

/** Le dot principal ; les sous-dots (`dot-<id>`) sont hors du périmètre de cette version. */
export const MAIN_CHAT = "owner";

export interface Approval {
  title: string;
  command: string;
  description: string;
  /** Choix retenu (`once`, `session`, `always`, `deny`), null tant que la demande est ouverte. */
  resolved: string | null;
}

export interface Clarify {
  question: string;
  choices: string[];
  /** Réponse donnée depuis ce navigateur ; le gateway ne la renvoie pas dans l'historique. */
  answer: string | null;
}

export interface ChatMessage {
  /** Clé locale stable : `client_id` pour un message tapé ici, sinon l'id du serveur. */
  key: string;
  /** Id attribué par le gateway, null tant que l'`ack` n'est pas arrivé. */
  id: string | null;
  type: "message" | "approval" | "clarify";
  role: "user" | "agent";
  kind: "text" | "voice" | "file" | "takeover";
  text: string;
  ts: number;
  /** 0 pour un message local pas encore enregistré. */
  seq: number;
  /** Id du message de l'utilisateur dont ce tour est la réponse. */
  turn: string | null;
  final: boolean;
  delivery: Delivery;
  /** Réaction de l'utilisateur (posée ici) et de l'agent (trame `reaction`). */
  reactions: { user?: string; agent?: string };
  // Pièce jointe et vocal
  name?: string;
  mime?: string;
  size?: number;
  durationMs?: number;
  waveform?: number[];
  transcript?: string;
  // Prise en main : `pending`, `done` ou `expired`
  status?: string;
  approval?: Approval;
  clarify?: Clarify;
}

export type ToolState = "running" | "done" | "error";

export interface ToolCall {
  id: string;
  turn: string | null;
  name: string;
  kind: string;
  label: string;
  detail: string;
  state: ToolState;
  ts: number;
  seq: number;
  durationMs: number | null;
}

export interface GatewaySnapshot {
  status: ConnectionStatus;
  /** Nom du profil annoncé par `welcome` (le nom choisi dans l'app reste prioritaire à l'affichage). */
  serverName: string | null;
  messages: ChatMessage[];
  tools: ToolCall[];
  activity: Activity;
  /** Première page reçue (le fil peut s'afficher). */
  loaded: boolean;
  hasMore: boolean;
  loadingOlder: boolean;
  /** Messages de l'agent arrivés pendant que cet agent n'était pas à l'écran. */
  unread: number;
}

export const EMPTY_SNAPSHOT: GatewaySnapshot = {
  status: "connecting",
  serverName: null,
  messages: [],
  tools: [],
  activity: "idle",
  loaded: false,
  hasMore: false,
  loadingOlder: false,
  unread: 0,
};

/* -- Conversion des événements du gateway ------------------------------------------------------- */

type Json = Record<string, any>;

const str = (v: unknown): string => (typeof v === "string" ? v : v == null ? "" : String(v));
const num = (v: unknown, fallback = 0): number => (typeof v === "number" && isFinite(v) ? v : fallback);

/** Conversation d'un événement : absente = dot principal (plugin d'avant les sous-dots). */
export function chatOf(event: Json): string {
  return str(event.chat) || MAIN_CHAT;
}

/** Événement d'historique ou trame live (`message`, `approval`, `clarify`) → message ; null sinon. */
export function toMessage(event: Json): ChatMessage | null {
  const id = str(event.id);
  if (!id) return null;
  const base = {
    key: id,
    id,
    ts: num(event.ts, Date.now()),
    seq: num(event.seq),
    turn: str(event.turn) || null,
    final: event.final !== false,
    delivery: "read" as Delivery,
    reactions: {},
    text: str(event.text),
  };
  switch (event.type) {
    case "message": {
      const kind = (["voice", "file", "takeover"].includes(event.kind) ? event.kind : "text") as ChatMessage["kind"];
      return {
        ...base,
        type: "message",
        role: event.role === "user" ? "user" : "agent",
        kind,
        name: event.name != null ? str(event.name) : undefined,
        mime: event.mime != null ? str(event.mime) : undefined,
        size: event.size != null ? num(event.size) : undefined,
        durationMs: event.duration_ms != null ? num(event.duration_ms) : undefined,
        waveform: Array.isArray(event.waveform) ? event.waveform.map((v: unknown) => num(v)) : undefined,
        transcript: str(event.transcript) || undefined,
        status: kind === "takeover" ? str(event.status) || "pending" : undefined,
      };
    }
    case "approval":
      return {
        ...base,
        type: "approval",
        role: "agent",
        kind: "text",
        approval: {
          title: str(event.title),
          command: str(event.command),
          description: str(event.description),
          resolved: str(event.resolved) || null,
        },
      };
    case "clarify":
      return {
        ...base,
        type: "clarify",
        role: "agent",
        kind: "text",
        clarify: {
          question: str(event.question),
          choices: Array.isArray(event.choices) ? event.choices.map(str) : [],
          answer: null,
        },
      };
    default:
      return null;
  }
}

/** Événement `tool` (live ou rejoué : l'historique garde l'état final). */
export function toTool(event: Json, labelFor: (name: string) => string | null): ToolCall | null {
  const id = str(event.id);
  if (!id) return null;
  const name = str(event.name);
  const state = event.state === "done" || event.state === "error" ? event.state : "running";
  return {
    id,
    turn: str(event.turn) || null,
    name,
    kind: str(event.kind) || "tool",
    // Le gateway rédige déjà le libellé dans la langue annoncée par `hello` : il passe en premier.
    label: str(event.label) || labelFor(name) || name || "Tool",
    detail: str(event.detail),
    state,
    ts: num(event.ts, Date.now()),
    seq: num(event.seq),
    durationMs: event.duration_ms != null ? num(event.duration_ms) : null,
  };
}

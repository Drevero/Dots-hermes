package app.dotshermes.data.gateway

import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CronJob
import app.dotshermes.data.model.UsageWindow
import app.dotshermes.data.model.UsageHour
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.DotKind
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.data.model.ApprovalStatus
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.Delivery
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolKind
import app.dotshermes.data.model.ToolState
import app.dotshermes.data.webpair.WebSession
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Locale

/** Traduction des trames JSON du plugin « dots » (docs/protocole-gateway.md) en modèles de l'app. */
internal object GatewayProtocol {

    /** [lang] : langue de l'app (celle du téléphone), pour les textes que le plugin rédige lui-même (push, outils). */
    fun hello(lastSeq: Long, foreground: Boolean, pushToken: String?, lang: String = Locale.getDefault().language): String = JSONObject()
        .put("type", "hello").put("last_seq", lastSeq).put("foreground", foreground).put("lang", lang)
        .apply { if (pushToken != null) put("push_token", pushToken) }
        .toString()

    fun voice(clientId: String, audio: ByteArray, durationMs: Long, waveform: List<Float>, chat: String = MAIN_CHAT): String = JSONObject()
        .put("type", "voice").put("client_id", clientId).put("chat", chat).put("mime", "audio/mp4").put("duration_ms", durationMs)
        .put("waveform", JSONArray(waveform.map { it.toDouble() }))
        .put("audio", Base64.getEncoder().encodeToString(audio))
        .toString()

    fun file(clientId: String, data: ByteArray, name: String, mime: String, caption: String, chat: String = MAIN_CHAT): String = JSONObject()
        .put("type", "file").put("client_id", clientId).put("chat", chat).put("name", name).put("mime", mime).put("caption", caption)
        .put("data", Base64.getEncoder().encodeToString(data))
        .toString()

    fun fileGet(remoteId: String): String = JSONObject().put("type", "file_get").put("id", remoteId).toString()

    fun screenWatch(on: Boolean, takeover: Boolean = false): String =
        JSONObject().put("type", "screen_watch").put("on", on).put("takeover", takeover).toString()

    fun screenInput(input: ScreenInput): String = JSONObject().put("type", "screen_input").apply {
        when (input) {
            is ScreenInput.Tap -> put("action", "click").put("x", input.x).put("y", input.y)
            is ScreenInput.Scroll -> put("action", "scroll").put("x", input.x).put("y", input.y).put("dy", input.dy)
            is ScreenInput.Type -> put("action", "type").put("text", input.text)
            is ScreenInput.Key -> put("action", "key").put("key", input.key)
        }
    }.toString()

    fun activityGet(): String = JSONObject().put("type", "activity_get").toString()

    // -- Sessions web (docs/appairage-web.md) -------------------------------------------------------

    /** Demande un jeton web pour le navigateur d'[origin] ; la réponse `web_token` reprend [ref]. */
    fun webTokenCreate(ref: String, origin: String, label: String): String =
        JSONObject().put("type", "web_token_create").put("ref", ref).put("origin", origin).put("label", label).toString()

    fun webSessionsGet(): String = JSONObject().put("type", "web_sessions_get").toString()

    fun webRevoke(id: String): String = JSONObject().put("type", "web_revoke").put("id", id).toString()

    /** Trame `web_sessions` : `sessions[]` = `{id, label, origin, created_at, last_seen}` (millisecondes). */
    fun toWebSessions(frame: JSONObject): List<WebSession> {
        val array = frame.optJSONArray("sessions") ?: return emptyList()
        return List(array.length()) { array.optJSONObject(it) }.mapNotNull { s ->
            val id = s?.optString("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            WebSession(
                id = id,
                label = s.optString("label"),
                origin = s.optString("origin"),
                createdAt = s.optLong("created_at").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                lastSeen = s.optLong("last_seen").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            )
        }.sortedByDescending { it.lastSeen ?: it.createdAt ?: Instant.EPOCH }
    }

    fun usageGet(from: Instant, to: Instant): String =
        JSONObject().put("type", "usage_get").put("from", from.toEpochMilli()).put("to", to.toEpochMilli()).toString()

    /** Trame `usage` : `hours` = [[heure UTC depuis l'époque, tokens, messages], …]. */
    fun toUsage(frame: JSONObject): UsageWindow {
        val hours = frame.optJSONArray("hours")
        return UsageWindow(
            from = Instant.ofEpochMilli(frame.optLong("from")),
            to = Instant.ofEpochMilli(frame.optLong("to")),
            hours = if (hours == null) emptyList() else List(hours.length()) { i ->
                val h = hours.getJSONArray(i)
                UsageHour(hour = h.optLong(0), tokens = h.optLong(1), messages = h.optInt(2))
            },
        )
    }

    fun cronAction(id: String, action: String): String =
        JSONObject().put("type", "cron_action").put("id", id).put("action", action).toString()

    fun stop(chat: String = MAIN_CHAT): String = JSONObject().put("type", "stop").put("chat", chat).toString()

    fun takeoverDone(id: String): String = JSONObject().put("type", "takeover_done").put("id", id).toString()

    /** [beforeSeq] = 0 : la page la plus récente (premier affichage d'une conversation). */
    fun historyPage(beforeSeq: Long, chat: String = MAIN_CHAT, limit: Int = 80): String =
        JSONObject().put("type", "history_page").put("chat", chat).put("before_seq", beforeSeq).put("limit", limit).toString()

    fun dotCreate(ref: String, draft: SubDotDraft): String =
        JSONObject().put("type", "dot_create").put("ref", ref).put("dot", draft.toJson()).toString()

    fun dotUpdate(id: String, draft: SubDotDraft): String =
        JSONObject().put("type", "dot_update").put("id", id).put("dot", draft.toJson()).toString()

    fun dotDelete(id: String): String = JSONObject().put("type", "dot_delete").put("id", id).toString()

    private fun SubDotDraft.toJson() = JSONObject().put("name", name).put("kind", kind.wire).put("mission", mission)
        .put("species", species).put("palette", palette).put("accessory", accessory)

    fun toDots(array: JSONArray?): List<SubDot> = if (array == null) emptyList() else List(array.length()) { i ->
        val d = array.getJSONObject(i)
        SubDot(
            id = d.optString("id"),
            name = d.optString("name").ifBlank { "Dot" },
            kind = if (d.optString("kind") == "permanent") DotKind.Permanent else DotKind.Temporary,
            mission = d.optString("mission"),
            species = d.optString("species"),
            palette = d.optString("palette"),
            accessory = d.optString("accessory"),
            createdAt = Instant.ofEpochMilli(d.optLong("created_at")),
            lastActivityAt = d.optLong("last_ts").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        )
    }

    fun chatOf(frame: JSONObject): String = frame.optString("chat").ifEmpty { MAIN_CHAT }

    fun toActivity(frame: JSONObject): ActivitySnapshot {
        val crons = frame.optJSONArray("crons")
        return ActivitySnapshot(
            crons = if (crons == null) emptyList() else List(crons.length()) { i ->
                val c = crons.getJSONObject(i)
                CronJob(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    schedule = c.optString("schedule"),
                    state = c.optString("state"),
                    enabled = c.optBoolean("enabled"),
                    nextRunAt = c.instant("next_run_at"),
                    lastRunAt = c.instant("last_run_at"),
                    lastStatus = c.optString("last_status").takeIf { !c.isNull("last_status") && it.isNotEmpty() },
                    prompt = c.optString("prompt"),
                )
            },
            busyTurn = frame.optString("turn").takeIf { !frame.isNull("turn") && it.isNotEmpty() },
            browserOpen = frame.optBoolean("browser"),
        )
    }

    private fun JSONObject.instant(key: String): Instant? =
        if (isNull(key)) null else runCatching { OffsetDateTime.parse(optString(key)).toInstant() }.getOrNull()

    fun audioGet(remoteId: String): String = JSONObject().put("type", "audio_get").put("id", remoteId).toString()

    fun presence(foreground: Boolean): String = JSONObject().put("type", "presence").put("foreground", foreground).toString()

    fun pushToken(token: String): String = JSONObject().put("type", "push_token").put("token", token).toString()

    /** [room] non vide : salon de groupe, ces sous-dots débattent puis le dot de la conversation conclut. */
    fun message(clientId: String, text: String, chat: String = MAIN_CHAT, room: List<String> = emptyList()): String =
        JSONObject().put("type", "message").put("client_id", clientId).put("text", text).put("chat", chat)
            .apply { if (room.isNotEmpty()) put("room", JSONArray(room)) }
            .toString()

    /** Sous-dot qui parle dans le salon (trame `activity`, `welcome.speakers`) ; null = le dot lui-même. */
    fun speakerOf(frame: JSONObject?, key: String = "speaker"): String? =
        frame?.optString(key)?.takeIf { !frame.isNull(key) && it.isNotEmpty() }

    fun reaction(remoteId: String, emoji: String?): String =
        JSONObject().put("type", "reaction").put("message_id", remoteId).put("emoji", emoji ?: JSONObject.NULL).toString()

    fun approval(approvalId: String, approved: Boolean): String =
        JSONObject().put("type", "approval").put("approval_id", approvalId).put("choice", if (approved) "once" else "deny").toString()

    fun clarify(clarifyId: String, response: String): String =
        JSONObject().put("type", "clarify").put("clarify_id", clarifyId).put("response", response).toString()

    fun activity(state: String): AgentActivity = when (state) {
        "thinking" -> AgentActivity.Thinking
        "typing" -> AgentActivity.Typing
        else -> AgentActivity.Idle
    }

    /** Événement d'historique ou trame live (`message`, `approval`, `clarify`) → message ; `null` sinon. */
    fun toMessage(event: JSONObject): ChatMessage? {
        val id = event.optString("id").ifEmpty { return null }
        val sentAt = Instant.ofEpochMilli(event.optLong("ts", System.currentTimeMillis()))
        val content: MessageContent
        var sender = Sender.Agent
        when (event.optString("type")) {
            "message" -> {
                content = if (event.optString("kind") == "takeover") {
                    MessageContent.Takeover(event.optString("text"), pending = event.optString("status", "pending") == "pending")
                } else if (event.optString("kind") == "file") {
                    MessageContent.File(
                        name = event.optString("name", "file"),
                        mime = event.optString("mime", "application/octet-stream"),
                        size = event.optLong("size"),
                        caption = event.optString("text"),
                    )
                } else if (event.optString("kind") == "voice") {
                    MessageContent.Voice(
                        durationMs = event.optLong("duration_ms"),
                        waveform = event.optJSONArray("waveform").floats(),
                        transcript = event.optString("transcript").takeIf { it.isNotBlank() },
                    )
                } else {
                    MessageContent.Text(event.optString("text"))
                }
                if (event.optString("role") == "user") sender = Sender.User
            }
            "approval" -> content = MessageContent.Approval(
                // Vide : la carte et la notification affichent un titre par défaut, traduit.
                title = event.optString("description"),
                detail = event.optString("command"),
                status = when (event.optString("resolved")) {
                    "" -> ApprovalStatus.Pending
                    "deny" -> ApprovalStatus.Declined
                    else -> ApprovalStatus.Approved
                },
            )
            "clarify" -> content = MessageContent.Clarify(
                question = event.optString("question"),
                choices = event.optJSONArray("choices").strings(),
            )
            else -> return null
        }
        return ChatMessage(
            id = id, remoteId = id, sender = sender, content = content, sentAt = sentAt, delivery = Delivery.Read,
            turn = event.optString("turn").takeIf { !event.isNull("turn") && it.isNotEmpty() },
            seq = event.optLong("seq", 0).takeIf { it > 0 },
            speaker = speakerOf(event),
            speakerName = speakerOf(event, "speaker_name"),
        )
    }

    /**
     * Événement `tool` (live ou rejoué, état final déjà fusionné par le plugin) → appel d'outil.
     * [labelFor] traduit le libellé des outils connus ; sinon on garde celui du plugin.
     */
    fun toTool(event: JSONObject, labelFor: (name: String) -> String? = { null }): ToolCall? {
        val id = event.optString("id").ifEmpty { return null }
        val name = event.optString("name")
        return ToolCall(
            id = id,
            turn = event.optString("turn").takeIf { !event.isNull("turn") && it.isNotEmpty() },
            kind = toolKind(event.optString("kind")),
            label = labelFor(name) ?: event.optString("label").ifBlank { name.ifBlank { "Tool" } },
            detail = event.optString("detail"),
            state = toolState(event.optString("state")),
            startedAt = Instant.ofEpochMilli(event.optLong("ts", System.currentTimeMillis())),
            durationMs = event.optLong("duration_ms", -1).takeIf { it >= 0 },
            name = name,
        )
    }

    fun toolState(state: String): ToolState = when (state) {
        "done" -> ToolState.Done
        "error" -> ToolState.Error
        else -> ToolState.Running
    }

    private fun toolKind(kind: String): ToolKind =
        ToolKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: ToolKind.Tool

    private fun JSONArray?.floats(): List<Float> =
        if (this == null) emptyList() else List(length()) { optDouble(it, 0.0).toFloat() }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { optString(it) }
}

package app.dotshermes.data.mock

import android.content.Context
import app.dotshermes.R
import app.dotshermes.data.ChatRepository
import app.dotshermes.data.ToolLabels
import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CronJob
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ApprovalStatus
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.Delivery
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.StepState
import app.dotshermes.data.model.TaskStep
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolState
import app.dotshermes.data.model.UsageHour
import app.dotshermes.data.model.UsageWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Faux agent Hermes : simule le gateway (connexion en plusieurs étapes), les accusés de lecture,
 * la réflexion, la frappe, les réactions, les tâches de fond et les demandes d'approbation.
 */
class MockChatRepository(
    /** Textes de la démo, dans la langue du téléphone. */
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) : ChatRepository {

    private val _profile = MutableStateFlow(AgentProfile(name = "Hermes", tagline = context.getString(R.string.profile_tagline_demo)))
    override val profile: StateFlow<AgentProfile> = _profile.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _messages = MutableStateFlow(MockSeed.conversation(context, clock()))
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _activity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    override val activity: StateFlow<AgentActivity> = _activity.asStateFlow()

    private val _tools = MutableStateFlow<List<ToolCall>>(emptyList())
    override val tools: StateFlow<List<ToolCall>> = _tools.asStateFlow()

    override val screen: StateFlow<ScreenState> = MutableStateFlow(ScreenState(idle = true)).asStateFlow()
    override val hasOlder: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    // Démo : une seule conversation, pas de sous-dots (la barre des dots est masquée).
    override val dots: StateFlow<List<SubDot>> = MutableStateFlow(emptyList<SubDot>()).asStateFlow()
    override val activeChat: StateFlow<String> = MutableStateFlow(MAIN_CHAT).asStateFlow()
    override val chatActivity: StateFlow<Map<String, AgentActivity>> = MutableStateFlow(emptyMap<String, AgentActivity>()).asStateFlow()
    override val unread: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap<String, Int>()).asStateFlow()

    /** Démo : deux tâches planifiées plausibles pour montrer la vue Activité. */
    override val activitySnapshot: StateFlow<ActivitySnapshot?> = MutableStateFlow(
        ActivitySnapshot(
            crons = listOf(
                CronJob("demo-1", context.getString(R.string.demo_cron_mail_name), "every day at 07:30", "scheduled", true,
                    clock().plusSeconds(5_400), clock().minusSeconds(81_000), "ok", context.getString(R.string.demo_cron_mail_prompt)),
                CronJob("demo-2", context.getString(R.string.demo_cron_backup_name), "every 2h", "scheduled", true,
                    clock().plusSeconds(2_100), clock().minusSeconds(5_100), "ok", context.getString(R.string.demo_cron_backup_prompt)),
            ),
            busyTurn = null,
            browserOpen = false,
        ),
    ).asStateFlow()

    private val _usage = MutableStateFlow<UsageWindow?>(null)
    override val usage: StateFlow<UsageWindow?> = _usage.asStateFlow()

    /**
     * Démo : une activité plausible et stable (chaque heure tire toujours le même hasard), surtout en
     * journée et plus calme le week-end, pour que la carte « Consommation » ait de quoi montrer.
     */
    override fun requestUsage(from: Instant, to: Instant) {
        val last = minOf(to, clock()).epochSecond / 3600
        val hours = (from.epochSecond / 3600..last).mapNotNull { hour ->
            val random = Random(hour * 7919)
            val weekend = Math.floorMod(hour / 24 + 3, 7) >= 5 // le jour 0 de l'époque Unix est un jeudi
            val daytime = Math.floorMod(hour, 24) in 6..21
            val chance = (if (daytime) 0.32 else 0.04) * (if (weekend) 0.4 else 1.0)
            if (random.nextDouble() >= chance) return@mapNotNull null
            UsageHour(hour, tokens = random.nextLong(1_500, 48_000), messages = random.nextInt(1, 7))
        }
        _usage.value = UsageWindow(from, to, hours)
    }

    /** Message utilisateur en cours de traitement : rattache outils et réponses au bon tour. */
    private var currentTurn: String? = null

    /** L'agent traite les tours un par un, comme une vraie session mono-conversation. */
    private val turns = Channel<Turn>(Channel.UNLIMITED)
    private var agentLoop: Job? = null
    private var turnCount = 0

    private sealed interface Turn {
        data object Greeting : Turn
        data class UserText(val messageId: String, val text: String) : Turn
        data class ApprovalAnswered(val approved: Boolean) : Turn
    }

    override suspend fun connect(config: GatewayConfig) {
        for (step in listOf(R.string.demo_connect_step_1, R.string.demo_connect_step_2, R.string.demo_connect_step_3)) {
            _connection.value = ConnectionState.Connecting(context.getString(step))
            delay(700)
        }
        _connection.value = ConnectionState.Connected(context.getString(R.string.demo_mode), latencyMs = 38, demo = true)
        agentLoop?.cancel()
        agentLoop = scope.launch { for (turn in turns) play(turn) }
        turns.trySend(Turn.Greeting)
    }

    override fun disconnect() {
        agentLoop?.cancel()
        agentLoop = null
        _activity.value = AgentActivity.Idle
        _connection.value = ConnectionState.Disconnected
    }

    override fun send(text: String) {
        val message = ChatMessage(
            id = newId(),
            sender = Sender.User,
            content = MessageContent.Text(text),
            sentAt = clock(),
            delivery = Delivery.Sending,
        )
        _messages.update { it + message }
        scope.launch {
            delay(350)
            setDelivery(message.id, Delivery.Sent)
        }
        turns.trySend(Turn.UserText(message.id, text))
    }

    override fun sendVoice(file: File, durationMs: Long, waveform: List<Float>) {
        val message = ChatMessage(
            id = newId(),
            sender = Sender.User,
            content = MessageContent.Voice(durationMs, waveform, localPath = file.path),
            sentAt = clock(),
            delivery = Delivery.Sending,
        )
        _messages.update { it + message }
        scope.launch {
            delay(350)
            setDelivery(message.id, Delivery.Sent)
            delay(900)
            // Pas de STT en démo : une transcription factice montre où elle s'affiche.
            updateMessage(message.id) { m ->
                (m.content as? MessageContent.Voice)?.let { m.copy(content = it.copy(transcript = context.getString(R.string.demo_voice_transcript, durationMs / 1000))) } ?: m
            }
        }
        turns.trySend(Turn.UserText(message.id, MockScript.VOICE))
    }

    override fun sendFile(file: File, name: String, mime: String, caption: String) {
        val message = ChatMessage(
            id = newId(), sender = Sender.User,
            content = MessageContent.File(name, mime, file.length(), localPath = file.path, caption = caption),
            sentAt = clock(), delivery = Delivery.Sending,
        )
        _messages.update { it + message }
        turns.trySend(Turn.UserText(message.id, MockScript.FILE))
    }

    override suspend fun attachmentFile(messageId: String): File? {
        val path = when (val c = _messages.value.firstOrNull { it.id == messageId }?.content) {
            is MessageContent.Voice -> c.localPath
            is MessageContent.File -> c.localPath
            else -> null
        }
        return path?.let(::File)?.takeIf { it.exists() }
    }

    override fun toggleReaction(messageId: String, emoji: String) = updateMessage(messageId) { message ->
        val reactions = if (message.reactions[Sender.User] == emoji) {
            message.reactions - Sender.User
        } else {
            message.reactions + (Sender.User to emoji)
        }
        message.copy(reactions = reactions)
    }

    override fun answerApproval(messageId: String, approved: Boolean) {
        val target = _messages.value.firstOrNull { it.id == messageId } ?: return
        val approval = target.content as? MessageContent.Approval ?: return
        if (approval.status != ApprovalStatus.Pending) return
        val status = if (approved) ApprovalStatus.Approved else ApprovalStatus.Declined
        updateMessage(messageId) { it.copy(content = approval.copy(status = status)) }
        turns.trySend(Turn.ApprovalAnswered(approved))
    }

    override fun answerClarify(messageId: String, response: String) = updateMessage(messageId) { message ->
        val clarify = message.content as? MessageContent.Clarify ?: return@updateMessage message
        message.copy(content = clarify.copy(answer = response))
    }

    private suspend fun play(turn: Turn) {
        when (turn) {
            Turn.Greeting -> {
                delay(900)
                think(800)
                say(context.getString(R.string.demo_greeting))
            }

            is Turn.ApprovalAnswered -> {
                think(700)
                say(MockScript.approvalOutcome(context, turn.approved))
            }

            is Turn.UserText -> {
                delay(700)
                setDelivery(turn.messageId, Delivery.Read)
                currentTurn = turn.messageId
                val reply = MockScript.replyFor(context, turn.text, turnCount++)
                reply.reaction?.let { react(turn.messageId, it) }
                think(reply.thinkMs)
                for (part in reply.parts) {
                    when (part) {
                        is ReplyPart.Say -> say(part.text)
                        is ReplyPart.RunTask -> runTask(part)
                        is ReplyPart.AskApproval -> append(MessageContent.Approval(part.title, part.detail))
                        is ReplyPart.UseTools -> useTools(part)
                    }
                }
            }
        }
        currentTurn = null
        _activity.value = AgentActivity.Idle
    }

    private suspend fun react(messageId: String, emoji: String) {
        delay(300)
        updateMessage(messageId) { it.copy(reactions = it.reactions + (Sender.Agent to emoji)) }
        _activity.value = AgentActivity.Reacting
        delay(1_100)
    }

    private suspend fun think(durationMs: Long) {
        _activity.value = AgentActivity.Thinking
        delay(durationMs)
    }

    private suspend fun say(text: String) {
        _activity.value = AgentActivity.Typing
        delay((500L + text.length * 18L).coerceAtMost(2_200L))
        append(MessageContent.Text(text))
        _activity.value = AgentActivity.Idle
        delay(450)
    }

    private suspend fun runTask(part: ReplyPart.RunTask) {
        val steps = part.steps.mapIndexed { i, label -> TaskStep(label, if (i == 0) StepState.Running else StepState.Pending) }
        val id = append(MessageContent.Task(part.title, steps))
        part.steps.forEachIndexed { index, label ->
            _activity.value = AgentActivity.Working(label)
            delay(1_100L + Random.nextLong(900L))
            updateMessage(id) { message ->
                val task = message.content as MessageContent.Task
                val next = task.steps.mapIndexed { i, step ->
                    when (i) {
                        index -> step.copy(state = StepState.Done)
                        index + 1 -> step.copy(state = StepState.Running)
                        else -> step
                    }
                }
                message.copy(content = task.copy(steps = next))
            }
        }
        say(part.outro)
    }

    private suspend fun useTools(part: ReplyPart.UseTools) {
        _activity.value = AgentActivity.Thinking
        for (tool in part.tools) {
            val label = ToolLabels[tool.name]?.let(context::getString) ?: tool.name
            val call = ToolCall(newId(), currentTurn, tool.kind, label, tool.detail, ToolState.Running, clock(), name = tool.name)
            _tools.update { it + call }
            delay(tool.durationMs)
            _tools.update { list -> list.map { if (it.id == call.id) it.copy(state = ToolState.Done, durationMs = tool.durationMs) else it } }
            delay(200)
        }
    }

    private fun append(content: MessageContent): String {
        val message = ChatMessage(id = newId(), sender = Sender.Agent, content = content, sentAt = clock(), turn = currentTurn)
        _messages.update { it + message }
        return message.id
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) =
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun setDelivery(id: String, delivery: Delivery) =
        updateMessage(id) { if (delivery > it.delivery) it.copy(delivery = delivery) else it }

    private fun newId() = UUID.randomUUID().toString()
}

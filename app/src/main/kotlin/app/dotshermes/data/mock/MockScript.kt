package app.dotshermes.data.mock

import android.content.Context
import app.dotshermes.R
import app.dotshermes.data.model.ToolKind

internal sealed interface ReplyPart {
    data class Say(val text: String) : ReplyPart
    data class RunTask(val title: String, val steps: List<String>, val outro: String) : ReplyPart
    data class AskApproval(val title: String, val detail: String) : ReplyPart
    /** Outils utilisés avant de répondre : apparaissent en sur-message au-dessus de la réponse. */
    data class UseTools(val tools: List<MockTool>) : ReplyPart
}

/** [name] : nom de l'outil Hermes, qui donne son libellé traduit (voir `ToolLabels`). */
internal data class MockTool(val kind: ToolKind, val name: String, val detail: String, val durationMs: Long)

internal data class MockReply(
    val parts: List<ReplyPart>,
    /** Réaction posée par l'agent sur le message de l'utilisateur avant de répondre. */
    val reaction: String? = null,
    val thinkMs: Long = 1_100,
)

/**
 * Réponses scriptées par mots-clés, pour montrer chaque type de message de l'interface. Les textes
 * viennent des ressources (langue du téléphone) ; les mots-clés couvrent le français et l'anglais.
 */
internal object MockScript {

    private val fallbacks = listOf(R.string.demo_fallback_1, R.string.demo_fallback_2, R.string.demo_fallback_3, R.string.demo_fallback_4)

    /** [text] vaut [FILE] ou [VOICE] quand l'utilisateur envoie une pièce jointe ou un vocal. */
    fun replyFor(context: Context, text: String, turn: Int): MockReply {
        val t = text.lowercase()
        fun say(id: Int) = ReplyPart.Say(context.getString(id))
        return when {
            t == FILE -> MockReply(
                reaction = "👀",
                parts = listOf(say(R.string.demo_reply_file)),
            )

            t == VOICE -> MockReply(
                reaction = "🎧",
                thinkMs = 900,
                parts = listOf(say(R.string.demo_reply_voice)),
            )

            t.containsAny("merci", "super", "top", "bravo", "génial", "parfait", "thanks", "thank you", "great", "perfect", "awesome") -> MockReply(
                reaction = "❤️",
                thinkMs = 600,
                parts = listOf(say(R.string.demo_reply_thanks)),
            )

            t.containsAny("salut", "bonjour", "hello", "coucou", "hey", "hi ") || t == "hi" -> MockReply(
                reaction = "👋",
                parts = listOf(say(R.string.demo_reply_hello)),
            )

            t.containsAny("déploie", "deploy", "lance", "tâche", "build", "launch", "task") -> MockReply(
                reaction = "👍",
                thinkMs = 1_400,
                parts = listOf(
                    say(R.string.demo_reply_deploy),
                    ReplyPart.RunTask(
                        title = context.getString(R.string.demo_task_deploy_title),
                        steps = listOf(
                            R.string.demo_task_deploy_step_1, R.string.demo_task_deploy_step_2,
                            R.string.demo_task_deploy_step_3, R.string.demo_task_deploy_step_4,
                        ).map(context::getString),
                        outro = context.getString(R.string.demo_task_deploy_outro),
                    ),
                ),
            )

            t.containsAny("mail", "envoie", "récap", "recap", "send") -> MockReply(
                parts = listOf(
                    say(R.string.demo_reply_mail),
                    ReplyPart.AskApproval(
                        title = context.getString(R.string.demo_approval_mail_title),
                        detail = context.getString(R.string.demo_approval_mail_detail),
                    ),
                ),
            )

            t.containsAny("nuit", "absence", "quoi de neuf", "résume", "résumé", "night", "what's new", "summar") -> MockReply(
                thinkMs = 900,
                parts = listOf(
                    ReplyPart.UseTools(listOf(
                        MockTool(ToolKind.Skill, "skill_view", "email/triage-inbox", 400),
                        MockTool(ToolKind.Memory, "session_search", context.getString(R.string.demo_tool_memory_detail), 900),
                        MockTool(ToolKind.Terminal, "terminal", "systemctl status backup", 1_300),
                    )),
                    say(R.string.demo_reply_night_1),
                    say(R.string.demo_reply_night_2),
                ),
            )

            t.trimEnd().endsWith("?") -> MockReply(
                parts = listOf(
                    ReplyPart.UseTools(listOf(
                        MockTool(ToolKind.Search, "search_files", "ERROR · /var/log/hermes", 800),
                        MockTool(ToolKind.Web, "web_search", text.take(40), 1_500),
                    )),
                    say(R.string.demo_reply_question),
                ),
            )

            else -> MockReply(
                reaction = if (turn % 3 == 0) "👀" else null,
                parts = listOf(say(fallbacks[turn % fallbacks.size])),
            )
        }
    }

    fun approvalOutcome(context: Context, approved: Boolean): String =
        context.getString(if (approved) R.string.demo_approval_sent else R.string.demo_approval_declined)

    /** Mots-clés internes (jamais affichés) pour une pièce jointe ou un vocal. */
    const val FILE = "[file]"
    const val VOICE = "[voice]"

    private fun String.containsAny(vararg words: String) = words.any { it in this }
}

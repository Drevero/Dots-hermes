package app.dotshermes.data.mock

import android.content.Context
import app.dotshermes.R
import app.dotshermes.data.model.ApprovalStatus
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.StepState
import app.dotshermes.data.model.TaskStep
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Historique de démonstration : hier soir puis ce matin, avec chaque type de message. */
internal object MockSeed {

    fun conversation(context: Context, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<ChatMessage> {
        val today = now.atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        var counter = 0

        fun at(day: LocalDate, hour: Int, minute: Int): Instant {
            val instant = day.atTime(hour, minute).atZone(zone).toInstant()
            // Ouvert avant 8 h : on garde l'ordre en reculant les messages du matin.
            return if (instant.isAfter(now)) now.minusSeconds(900L - counter * 60L) else instant
        }

        fun msg(
            sender: Sender,
            content: MessageContent,
            sentAt: Instant,
            reactions: Map<Sender, String> = emptyMap(),
        ) = ChatMessage(id = "seed-${counter++}", sender = sender, content = content, sentAt = sentAt, reactions = reactions)

        fun text(id: Int) = MessageContent.Text(context.getString(id))

        return listOf(
            msg(Sender.Agent, text(R.string.demo_seed_watching), at(yesterday, 18, 12)),
            msg(Sender.User, text(R.string.demo_seed_thanks), at(yesterday, 18, 13), mapOf(Sender.Agent to "❤️")),
            msg(Sender.Agent, text(R.string.demo_seed_good_evening), at(yesterday, 18, 13)),
            msg(
                Sender.Agent,
                MessageContent.Task(
                    title = context.getString(R.string.demo_seed_task_title),
                    steps = listOf(
                        R.string.demo_seed_task_step_1,
                        R.string.demo_seed_task_step_2,
                        R.string.demo_seed_task_step_3,
                        R.string.demo_seed_task_step_4,
                    ).map { TaskStep(context.getString(it), StepState.Done) },
                ),
                at(today, 7, 58),
            ),
            msg(Sender.Agent, text(R.string.demo_seed_good_morning), at(today, 7, 59)),
            msg(
                Sender.Agent,
                text(R.string.demo_seed_night_report),
                at(today, 7, 59),
            ),
            msg(Sender.User, text(R.string.demo_seed_ask_recap), at(today, 8, 15), mapOf(Sender.Agent to "👍")),
            msg(
                Sender.Agent,
                MessageContent.Approval(
                    title = context.getString(R.string.demo_seed_approval_title),
                    detail = context.getString(R.string.demo_seed_approval_detail),
                    status = ApprovalStatus.Approved,
                ),
                at(today, 8, 16),
            ),
            msg(Sender.Agent, text(R.string.demo_seed_sent), at(today, 8, 17)),
        )
    }
}

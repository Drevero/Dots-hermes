package app.dotshermes.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dotshermes.R
import app.dotshermes.ui.chat.ChatRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** Heure courte (« 18:05 » ou « 6:05 PM ») : langue du téléphone et son réglage 12 / 24 h. */
@Composable
fun formatTime(at: Instant): String {
    LocalConfiguration.current // relit le format si la langue change
    return DateFormat.getTimeFormat(LocalContext.current).format(Date.from(at))
}

/**
 * Date selon un squelette ICU (« dMMMjmm », « EEEEdMMMM ») : Android en tire l'ordre, la
 * ponctuation et l'horloge propres à la langue (« 3 nov. 18:05 », « Nov 3, 6:05 PM »).
 */
@Composable
fun formatDate(at: Instant, skeleton: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(at.atZone(zone))
}

/** « Aujourd'hui · 18:05 », « Hier · 09:12 », sinon « Lundi 3 novembre · 08:00 ». */
@Composable
fun dayLabel(row: ChatRow.Day, zone: ZoneId = ZoneId.systemDefault()): String {
    val today = LocalDate.now(zone)
    val locale = LocalConfiguration.current.locales[0]
    val name = when (row.date) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> formatDate(row.firstAt, "EEEEdMMMM", zone).replaceFirstChar { it.titlecase(locale) }
    }
    return "$name · ${formatTime(row.firstAt)}"
}

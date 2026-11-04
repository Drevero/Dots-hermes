package app.dotshermes.ui.activity

import app.dotshermes.data.model.UsageHour
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Ce qu'un jour a consommé, dans le fuseau du téléphone. */
data class DayUsage(val tokens: Long = 0, val messages: Int = 0) {
    operator fun plus(other: DayUsage) = DayUsage(tokens + other.tokens, messages + other.messages)
}

/** Ce que la carte compte : les tokens de l'agent ou les messages échangés. */
enum class UsageMetric { Tokens, Messages }

fun DayUsage.value(metric: UsageMetric): Long = if (metric == UsageMetric.Tokens) tokens else messages.toLong()

/**
 * Le gateway compte par heure UTC ; on range chaque heure dans le jour local où elle commence.
 * Un fuseau décalé d'une demi-heure (Inde) déborde d'au plus 30 min sur le jour voisin : sans
 * importance pour une carte de chaleur.
 */
fun usageByDay(hours: List<UsageHour>, zone: ZoneId): Map<LocalDate, DayUsage> =
    hours.groupingBy { Instant.ofEpochSecond(it.hour * 3600).atZone(zone).toLocalDate() }
        .fold(DayUsage()) { day, hour -> day + DayUsage(hour.tokens, hour.messages) }

/**
 * Teinte d'une case, de 0 (rien) à 4 (le plus actif), comme GitHub : par quarts du maximum du mois
 * affiché. Toute activité, même minime, vaut au moins 1 pour ne pas se confondre avec un jour vide.
 */
fun heatLevel(value: Long, max: Long): Int = when {
    value <= 0 || max <= 0 -> 0
    else -> ((value * 4 + max - 1) / max).toInt().coerceIn(1, 4)
}

/**
 * Le mois en colonnes de semaines, chaque colonne de 7 cases commençant par [firstDay] (lundi en
 * France, dimanche aux États-Unis) ; null hors du mois. C'est la disposition du graphe de GitHub.
 */
fun monthColumns(month: YearMonth, firstDay: DayOfWeek): List<List<LocalDate?>> {
    val first = month.atDay(1)
    val lead = Math.floorMod(first.dayOfWeek.value - firstDay.value, 7)
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    return cells.chunked(7) { week -> week + List(7 - week.size) { null } }
}

/** Les 7 jours dans l'ordre des lignes, à partir de [firstDay]. */
fun weekDays(firstDay: DayOfWeek): List<DayOfWeek> = List(7) { firstDay.plus(it.toLong()) }

/** « 950 », « 12 k », « 3,4 k », « 1,8 M » : court, dans la langue du téléphone. */
fun compactCount(value: Long, locale: Locale): String {
    val (scaled, suffix) = when {
        value >= 1_000_000_000 -> value / 1e9 to " G"
        value >= 1_000_000 -> value / 1e6 to " M"
        value >= 1_000 -> value / 1e3 to " k"
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (scaled < 10) 1 else 0 }
    return format.format(scaled) + suffix
}

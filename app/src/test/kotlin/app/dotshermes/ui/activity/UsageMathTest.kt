package app.dotshermes.ui.activity

import app.dotshermes.data.model.UsageHour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class UsageMathTest {

    private fun hourOf(at: ZonedDateTime) = at.toEpochSecond() / 3600

    @Test
    fun `utc hours land in the local day they start in`() {
        val paris = ZoneId.of("Europe/Paris")
        // 23:00 UTC le 3 novembre = minuit à Paris le 4 : la consommation compte pour le 4.
        val late = hourOf(ZonedDateTime.of(2026, 11, 3, 23, 0, 0, 0, ZoneId.of("UTC")))
        val days = usageByDay(listOf(UsageHour(late, 100, 1), UsageHour(late - 1, 50, 2), UsageHour(late + 1, 7, 0)), paris)
        assertEquals(DayUsage(50, 2), days[LocalDate.of(2026, 11, 3)])
        assertEquals(DayUsage(107, 1), days[LocalDate.of(2026, 11, 4)])
    }

    @Test
    fun `levels split the month maximum in quarters, any activity at least 1`() {
        assertEquals(0, heatLevel(0, 100))
        assertEquals(1, heatLevel(1, 100))
        assertEquals(1, heatLevel(25, 100))
        assertEquals(2, heatLevel(26, 100))
        assertEquals(3, heatLevel(60, 100))
        assertEquals(4, heatLevel(100, 100))
        assertEquals(0, heatLevel(5, 0))
    }

    @Test
    fun `month columns follow the first day of the week`() {
        // Novembre 2026 commence un dimanche.
        val monday = monthColumns(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(6, monday.size)
        assertNull(monday[0][5])
        assertEquals(LocalDate.of(2026, 11, 1), monday[0][6])
        assertEquals(LocalDate.of(2026, 11, 30), monday[5][0])
        assertEquals(List(6) { 7 }, monday.map { it.size }) // chaque colonne a ses 7 cases, même incomplète

        val sunday = monthColumns(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(5, sunday.size)
        assertEquals(LocalDate.of(2026, 11, 1), sunday[0][0])
        assertEquals(listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), weekDays(DayOfWeek.SUNDAY).take(2))
    }

    @Test
    fun `counts are short and localized`() {
        assertEquals("950", compactCount(950, Locale.US))
        assertEquals("1.2 k", compactCount(1_234, Locale.US))
        assertEquals("48 k", compactCount(48_000, Locale.US))
        assertEquals("1,8 M", compactCount(1_752_449, Locale.FRANCE))
    }
}

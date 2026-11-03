package app.dotshermes.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {

    @Test
    fun `any recording length becomes a fixed number of normalised bars`() {
        val bars = downsample(List(173) { i -> if (i in 80..90) 0.5f else 0.05f }, 40)
        assertEquals(40, bars.size)
        assertEquals(1f, bars.max(), 1e-6f)
        assertTrue(bars.all { it in 0.08f..1f })
    }

    @Test
    fun `silence or empty input still draws a flat line`() {
        assertEquals(List(40) { 0.08f }, downsample(emptyList(), 40))
        assertEquals(12, downsample(listOf(0.3f, 0.6f), 12).size)
    }
}

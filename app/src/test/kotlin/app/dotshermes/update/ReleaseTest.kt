package app.dotshermes.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {

    @Test
    fun `versions compare number by number`() {
        assertTrue(isNewer("v0.2.0.12", "0.2.0.9"))
        assertTrue(isNewer("0.3.0.1", "0.2.0.40"))
        assertTrue(isNewer("0.2.0.3", "0.2.0"))
        assertFalse(isNewer("v0.2.0.3", "0.2.0.3"))
        assertFalse(isNewer("0.2.0.2", "0.2.0.3"))
    }

    @Test
    fun `an unreadable remote version is never offered`() {
        assertFalse(isNewer("nightly", "0.2.0.3"))
        assertNull(versionParts("v1.beta"))
        assertEquals(listOf(0, 2, 0, 12), versionParts("v0.2.0.12"))
    }
}

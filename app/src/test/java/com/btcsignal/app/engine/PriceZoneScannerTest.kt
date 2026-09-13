package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Direction
import org.junit.Assert.*
import org.junit.Test

class PriceZoneScannerTest {

    private val candleOpen = 0L
    private val windowStart = 2 * 60_000L // t+2min (2:00) -- inclusive
    private val windowEnd = 4 * 60_000L   // t+4min (4:00) -- EXCLUSIVE hard cutoff
    private val openPrice = 50_000.0

    private fun scanner(config: PriceZoneConfig = PriceZoneConfig()) = PriceZoneScanner(config)

    private fun priceFor(movePct: Double): Double = openPrice * (1 + movePct / 100.0)

    // ---- Opposite-direction rule (spec sections 2, 4) ----

    @Test
    fun `initial GREEN fires RED the first tick inside the red zone`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        val result = s.onTick(0.02, priceFor(0.02), windowStart + 5_000) // inside +0.01..+0.04
        assertNotNull(result)
        assertEquals(Direction.RED, result!!.direction)
        assertEquals(0.02, result.movePct, 0.0001)
        assertEquals(priceFor(0.02), result.price, 0.0001)
        assertTrue(s.fired)
    }

    @Test
    fun `initial RED fires GREEN the first tick inside the green zone`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.RED)
        val result = s.onTick(-0.03, priceFor(-0.03), windowStart + 5_000) // inside -0.01..-0.04
        assertNotNull(result)
        assertEquals(Direction.GREEN, result!!.direction)
        assertEquals(-0.03, result.movePct, 0.0001)
        assertTrue(s.fired)
    }

    @Test
    fun `initial GREEN never fires GREEN even if price re-enters the green zone`() {
        // Same-direction signals are never allowed (spec section 4) -- an initial GREEN
        // candle only ever watches the RED zone, so a move into the (same-direction)
        // green zone must simply be ignored, not fire GREEN.
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        assertNull(s.onTick(-0.03, priceFor(-0.03), windowStart + 1_000))
        assertFalse(s.fired)
    }

    @Test
    fun `initial RED never fires RED even if price re-enters the red zone`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.RED)
        assertNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        assertFalse(s.fired)
    }

    @Test
    fun `NONE initial direction never fires regardless of price`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.NONE)
        assertNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        assertNull(s.onTick(-0.03, priceFor(-0.03), windowStart + 2_000))
        assertFalse(s.fired)
    }

    @Test
    fun `a tick before the initial direction has ever been set never fires`() {
        val s = scanner()
        s.arm(candleOpen)
        // setInitialDirection was never called -- must never guess (spec section 10).
        assertNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        assertFalse(s.fired)
    }

    @Test
    fun `determineInitialDirection matches GREEN, RED and NONE from price vs open`() {
        assertEquals(InitialDirection.GREEN, determineInitialDirection(100_000.0, 100_050.0))
        assertEquals(InitialDirection.RED, determineInitialDirection(100_000.0, 99_950.0))
        assertEquals(InitialDirection.NONE, determineInitialDirection(100_000.0, 100_000.0))
    }

    // ---- One signal per candle ----

    @Test
    fun `only ever fires once per candle - a later qualifying tick is ignored`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        assertNotNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        // A second, later tick that also lands in the (same, only-watched) zone must
        // NOT produce a second signal.
        assertNull(s.onTick(0.03, priceFor(0.03), windowStart + 2_000))
    }

    // ---- Time window boundaries (spec sections 3, 9, 18) ----

    @Test
    fun `ticks before minute 3 (minutes 1 and 2) never fire even inside a zone`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        assertNull(s.onTick(0.02, priceFor(0.02), windowStart - 1_000)) // 1:59
        assertNull(s.onTick(0.02, priceFor(0.02), 30_000)) // minute 1
        assertFalse(s.fired)
    }

    @Test
    fun `signal allowed exactly at 2 colon 00`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        assertNotNull(s.onTick(0.02, priceFor(0.02), windowStart)) // exactly t+2min
    }

    @Test
    fun `signal allowed at 3 colon 59 but NOT at exactly 4 colon 00`() {
        val s1 = scanner()
        s1.arm(candleOpen)
        s1.setInitialDirection(InitialDirection.GREEN)
        assertNotNull(s1.onTick(0.02, priceFor(0.02), windowEnd - 1_000)) // 3:59

        val s2 = scanner()
        s2.arm(candleOpen)
        s2.setInitialDirection(InitialDirection.GREEN)
        assertNull(s2.onTick(0.02, priceFor(0.02), windowEnd)) // exactly 4:00 -- hard cutoff, excluded
        assertFalse(s2.fired)
    }

    @Test
    fun `ticks after 4 colon 00 (minute 5) never fire even inside a zone`() {
        val s = scanner()
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        assertNull(s.onTick(0.02, priceFor(0.02), windowEnd + 1_000)) // 4:30
        assertNull(s.onTick(0.02, priceFor(0.02), windowEnd + 59_000)) // 4:59
        assertFalse(s.fired)
    }

    // ---- Zone boundaries ----

    @Test
    fun `fires exactly at the inner and outer zone edges`() {
        val config = PriceZoneConfig(redLowPct = 0.01, redHighPct = 0.04)
        val s1 = scanner(config)
        s1.arm(candleOpen)
        s1.setInitialDirection(InitialDirection.GREEN)
        assertNotNull(s1.onTick(0.01, priceFor(0.01), windowStart + 1_000)) // inner edge

        val s2 = scanner(config)
        s2.arm(candleOpen)
        s2.setInitialDirection(InitialDirection.GREEN)
        assertNotNull(s2.onTick(0.04, priceFor(0.04), windowStart + 1_000)) // outer edge
    }

    @Test
    fun `just below minimum and just above maximum never fire`() {
        val config = PriceZoneConfig(redLowPct = 0.01, redHighPct = 0.04)
        val s1 = scanner(config)
        s1.arm(candleOpen)
        s1.setInitialDirection(InitialDirection.GREEN)
        assertNull(s1.onTick(0.0099, priceFor(0.0099), windowStart + 1_000)) // just below min

        val s2 = scanner(config)
        s2.arm(candleOpen)
        s2.setInitialDirection(InitialDirection.GREEN)
        assertNull(s2.onTick(0.0401, priceFor(0.0401), windowStart + 1_000)) // just above max
    }

    @Test
    fun `zone edges are configurable`() {
        val config = PriceZoneConfig(redLowPct = 0.10, redHighPct = 0.20, greenLowPct = 0.10, greenHighPct = 0.20)
        val s = scanner(config)
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        // Would have fired RED under the default 0.01-0.04 zone, but not under this config.
        assertNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        val result = s.onTick(0.15, priceFor(0.15), windowStart + 2_000)
        assertNotNull(result)
        assertEquals(Direction.RED, result!!.direction)
    }

    @Test
    fun `an unarmed scanner never fires regardless of price`() {
        val s = scanner()
        s.setInitialDirection(InitialDirection.GREEN)
        assertNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        assertFalse(s.isArmed)
    }

    // ---- 5-second waiting (spec section 8.2) ----

    @Test
    fun `5s waiting OFF fires immediately on first qualifying tick`() {
        val s = scanner(PriceZoneConfig(waitEnabled = false))
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        assertNotNull(s.onTick(0.02, priceFor(0.02), windowStart + 1_000))
        assertTrue(s.fired)
    }

    @Test
    fun `5s waiting ON does not fire on entry, fires only after confirmation still inside range`() {
        val s = scanner(PriceZoneConfig(waitEnabled = true))
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        val entryAt = windowStart + 10_000
        assertNull(s.onTick(0.01, priceFor(0.01), entryAt)) // entry -- must NOT fire yet
        assertFalse(s.fired)
        assertNull(s.onTick(0.015, priceFor(0.015), entryAt + 2_000)) // still waiting (< 5s)
        assertFalse(s.fired)
        val result = s.onTick(0.03, priceFor(0.03), entryAt + 5_000) // exactly 5s later, still inside
        assertNotNull(result)
        assertEquals(Direction.RED, result!!.direction)
        assertEquals(0.03, result.movePct, 0.0001)
        assertTrue(s.fired)
    }

    @Test
    fun `5s waiting ON cancels if price moved outside range at the 5s mark, then can re-enter`() {
        val s = scanner(PriceZoneConfig(waitEnabled = true))
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        val entryAt = windowStart + 10_000
        assertNull(s.onTick(0.01, priceFor(0.01), entryAt)) // entry
        assertNull(s.onTick(0.005, priceFor(0.005), entryAt + 5_000)) // below min at the 5s mark -- cancelled
        assertFalse(s.fired)

        // A fresh valid entry after the cancellation may start a brand-new attempt.
        val secondEntryAt = entryAt + 6_000
        assertNull(s.onTick(0.02, priceFor(0.02), secondEntryAt))
        val result = s.onTick(0.02, priceFor(0.02), secondEntryAt + 5_000)
        assertNotNull(result)
        assertTrue(s.fired)
    }

    @Test
    fun `5s waiting ON cancels if price moved beyond the maximum at the 5s mark`() {
        val s = scanner(PriceZoneConfig(waitEnabled = true))
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        val entryAt = windowStart + 10_000
        assertNull(s.onTick(0.01, priceFor(0.01), entryAt)) // entry
        assertNull(s.onTick(0.05, priceFor(0.05), entryAt + 5_000)) // above max -- cancelled
        assertFalse(s.fired)
    }

    @Test
    fun `5s waiting ON never extends a signal past the 4 colon 00 cutoff`() {
        // Range first reached at 3:58 (2 seconds before the 4:00 cutoff) -- must NOT
        // generate a signal even if a later tick at/after the 5s mark would land past
        // 4:00, because that later tick is itself outside the armed window and is
        // therefore never even evaluated.
        val s = scanner(PriceZoneConfig(waitEnabled = true))
        s.arm(candleOpen)
        s.setInitialDirection(InitialDirection.GREEN)
        val entryAt = windowEnd - 2_000 // 3:58
        assertNull(s.onTick(0.02, priceFor(0.02), entryAt)) // entry, starts the timer
        assertNull(s.onTick(0.02, priceFor(0.02), windowEnd)) // 4:00 -- outside window, ignored
        assertNull(s.onTick(0.02, priceFor(0.02), entryAt + 5_000)) // "4:03" -- outside window, ignored
        assertFalse(s.fired)
    }
}

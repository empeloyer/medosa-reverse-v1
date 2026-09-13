package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Direction
import org.junit.Assert.*
import org.junit.Test

class ReversalZoneScannerTest {

    private val candleOpen = 0L
    private val windowStart = 3 * 60_000L // t+3min
    private val windowEnd = 4 * 60_000L   // t+4min
    private val openPrice = 50_000.0

    private fun scanner() = ReversalZoneScanner()

    /** A plausible raw price for a given percentage move off [openPrice], purely so tests
     *  can also assert [ReversalZoneScanner.FireResult.price] threads through correctly. */
    private fun priceFor(movePct: Double): Double = openPrice * (1 + movePct / 100.0)

    @Test
    fun `never fires if price stays out of the zone all of minute 4`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(0.01, priceFor(0.01), windowStart + 5_000))
        assertNull(s.onTick(-0.01, priceFor(-0.01), windowStart + 15_000))
        assertNull(s.onTick(0.02, priceFor(0.02), windowEnd))
        assertFalse(s.fired)
    }

    @Test
    fun `GREEN primary - a deeper tick fires immediately with the deeper price`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(-0.03, priceFor(-0.03), windowStart + 1_000)) // touch
        val result = s.onTick(-0.05, priceFor(-0.05), windowStart + 2_000) // deeper -> fire now
        assertNotNull(result)
        assertEquals(Direction.GREEN, result!!.direction)
        assertEquals(-0.05, result.movePct, 0.0001)
        assertEquals(priceFor(-0.05), result.price, 0.0001)
        assertTrue(s.fired)
    }

    @Test
    fun `GREEN primary - no deeper tick, fires at the 10s mark using the current price`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(-0.05, priceFor(-0.05), windowStart + 1_000)) // touch, deep already
        assertNull(s.onTick(-0.04, priceFor(-0.04), windowStart + 5_000)) // shallower, still waiting
        val result = s.onTick(-0.045, priceFor(-0.045), windowStart + 11_000) // 10s elapsed
        assertNotNull(result)
        assertEquals(-0.045, result!!.movePct, 0.0001)
        assertEquals(priceFor(-0.045), result.price, 0.0001)
    }

    @Test
    fun `GREEN primary - leaving the zone before firing discards the touch and allows a fresh one`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(-0.03, priceFor(-0.03), windowStart + 1_000)) // touch
        assertNull(s.onTick(0.01, priceFor(0.01), windowStart + 2_000))  // recovered out -> discarded
        assertFalse(s.fired)
        // fresh touch later in the same window
        assertNull(s.onTick(-0.04, priceFor(-0.04), windowStart + 20_000))
        val result = s.onTick(-0.04, priceFor(-0.04), windowStart + 31_000) // 10s later -> fires
        assertNotNull(result)
    }

    @Test
    fun `GREEN primary - overshooting past the outer bound on first contact is not a touch`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(-0.15, priceFor(-0.15), windowStart + 1_000)) // beyond -0.10, not in zone
        assertNull(s.onTick(-0.05, priceFor(-0.05), windowStart + 2_000)) // now a genuine touch
        val result = s.onTick(-0.05, priceFor(-0.05), windowStart + 13_000)
        assertNotNull(result)
    }

    @Test
    fun `RED primary mirrors the GREEN behavior on the positive side`() {
        val s = scanner()
        s.arm(Direction.RED, candleOpen)
        assertNull(s.onTick(0.03, priceFor(0.03), windowStart + 1_000)) // touch
        val result = s.onTick(0.06, priceFor(0.06), windowStart + 2_000) // deeper -> fire
        assertNotNull(result)
        assertEquals(Direction.RED, result!!.direction)
        assertEquals(0.06, result.movePct, 0.0001)
    }

    @Test
    fun `only ever fires once per candle`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(-0.03, priceFor(-0.03), windowStart + 1_000))
        assertNotNull(s.onTick(-0.06, priceFor(-0.06), windowStart + 2_000))
        // Even a further, deeper tick after firing must not fire again.
        assertNull(s.onTick(-0.09, priceFor(-0.09), windowStart + 3_000))
    }

    @Test
    fun `ticks before or after the minute-4 window are ignored`() {
        val s = scanner()
        s.arm(Direction.GREEN, candleOpen)
        assertNull(s.onTick(-0.05, priceFor(-0.05), windowStart - 1_000)) // still minute 3, too early
        assertNull(s.onTick(-0.05, priceFor(-0.05), windowEnd + 1_000))   // past minute 4, too late
        assertFalse(s.fired)
    }

    @Test
    fun `an unarmed scanner never fires regardless of price`() {
        val s = scanner()
        assertNull(s.onTick(-0.08, priceFor(-0.08), windowStart + 1_000))
        assertFalse(s.isArmed)
    }
}

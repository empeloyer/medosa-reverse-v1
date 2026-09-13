package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CoreSignalEngineEntryRangeTest {

    private lateinit var store: MarketDataStore
    private lateinit var database: StrategyDatabase

    /** A single always-eligible strategy whose only condition is a small move-from-open
     *  threshold, so these tests isolate the entry-range HARD CONSTRAINT (section 8)
     *  from strategy-specific logic. */
    private fun syntheticDatabase(): StrategyDatabase {
        val component = StrategyComponent(
            indicator = "micro_momentum",
            primitiveId = "test_micro_momentum",
            hypothesis = "continuation",
            params = mapOf("min_move_pct" to 0.001)
        )
        val strategy = StrategyDef(
            id = "TEST-001",
            marketConditionBucket = "test",
            regimeGateCode = "All",
            regimeGateDescription = "",
            logicDescription = "micro_momentum only",
            components = listOf(component),
            directionDescription = "",
            performance = StrategyPerformance(100, 55.0, 100, 55.0, 0.1, 1.0),
            walkForwardBlocks = emptyList(),
            maxSignalOverlapPct = 0.0,
            confidenceFlag = null
        )
        return StrategyDatabase(
            asset = "BTCUSDT",
            targetTimeframe = "5m",
            hardConstraints = HardConstraints(0.0..0.03, -0.03..0.0, outcomeTieCountsAsRed = true),
            financialModel = FinancialModelSpec(100.0, 1.0, 0.5, -1.0, 50.0),
            strategies = listOf(strategy)
        )
    }

    @Before
    fun setUp() {
        database = syntheticDatabase()
        store = MarketDataStore()
        // Enough mildly-oscillating 5m history for MarketRegimeClassifier's minimum lookbacks
        // (ADX needs 2*14+1=29, volatility percentile needs 14+1+100=115, momentum needs 10+1+200=211).
        var price = 50_000.0
        val base = 1_700_000_000_000L / 60_000L * 60_000L
        for (i in 0 until 220 * 5) {
            val wiggle = if (i % 2 == 0) 1.0 else -1.0
            val open = price
            val close = price + wiggle
            price = close
            val t = base + i * 60_000L
            store.addClosed1m(Candle(t, open, maxOf(open, close) + 0.5, minOf(open, close) - 0.5, close, 3.0, t + 59_999, true))
        }
    }

    private fun evaluate(movePct: Double): EngineResult {
        val candleOpen = 50_000.0
        val referencePrice = candleOpen * (1 + movePct / 100.0)
        return CoreSignalEngine.evaluateCheckpoint(
            database = database,
            store = store,
            candleOpenTimeMillis = System.currentTimeMillis(),
            candleOpen = candleOpen,
            checkpoint = Checkpoint.A,
            referencePrice = referencePrice,
            minute1Candle = null,
            minute2Candle = null,
            timestampMillis = System.currentTimeMillis(),
            statsProvider = { RecentWindowStats(0, 0.0) }
        )
    }

    @Test
    fun `price exactly at plus 0_03 percent is inside the Green range`() {
        val result = evaluate(0.03)
        assertNotNull("Expected a GREEN signal at the +0.03%% boundary (inclusive)", result.signal)
        assertEquals(Direction.GREEN, result.signal!!.direction)
    }

    @Test
    fun `price just above plus 0_03 percent is rejected`() {
        val result = evaluate(0.0300001)
        assertNull("Move beyond +0.03%% must not produce a signal", result.signal)
    }

    @Test
    fun `price exactly at minus 0_03 percent is inside the Red range`() {
        val result = evaluate(-0.03)
        assertNotNull("Expected a RED signal at the -0.03%% boundary (inclusive)", result.signal)
        assertEquals(Direction.RED, result.signal!!.direction)
    }

    @Test
    fun `price just below minus 0_03 percent is rejected`() {
        val result = evaluate(-0.0300001)
        assertNull("Move beyond -0.03%% must not produce a signal", result.signal)
    }

    @Test
    fun `price exactly at zero produces no signal because no component condition is met`() {
        val result = evaluate(0.0)
        assertNull(result.signal)
    }

    @Test
    fun `price far outside the permitted range produces no signal`() {
        val result = evaluate(1.5)
        assertNull(result.signal)
    }
}

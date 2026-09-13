package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class FinancialModelTest {

    private val database = StrategyDatabase(
        asset = "BTCUSDT",
        targetTimeframe = "5m",
        hardConstraints = HardConstraints(0.0..0.03, -0.03..0.0, outcomeTieCountsAsRed = true),
        financialModel = FinancialModelSpec(startCapitalUsd = 100.0, stakePerSignalUsd = 1.0, winUsd = 0.5, lossUsd = -1.0, breakevenWinRatePct = 66.7),
        strategies = emptyList()
    )

    private fun signal(direction: Direction, candleOpen: Double = 50_000.0) = Signal(
        signalId = "s1", candleId = "c1", candleOpenTimeMillis = 0, signalTimestampMillis = 0,
        candleOpen = candleOpen, signalPrice = candleOpen, direction = direction,
        activeStrategyId = "TEST-001", activeStrategyName = "test",
        marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
        strategyScore = 1.0, confidencePct = 55.0, entryMovePct = 0.01, checkpoint = Checkpoint.A
    )

    @Test
    fun `GREEN signal wins when candle closes above open`() {
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, signal(Direction.GREEN), finalClose = 50_010.0)
        assertEquals(SignalStatus.WON, status)
        assertEquals(0.5, pnl, 0.0001)
    }

    @Test
    fun `GREEN signal loses when candle closes below open`() {
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, signal(Direction.GREEN), finalClose = 49_990.0)
        assertEquals(SignalStatus.LOST, status)
        assertEquals(-1.0, pnl, 0.0001)
    }

    @Test
    fun `RED signal wins when candle closes below open`() {
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, signal(Direction.RED), finalClose = 49_990.0)
        assertEquals(SignalStatus.WON, status)
        assertEquals(0.5, pnl, 0.0001)
    }

    @Test
    fun `a tie (close equals open) counts as Red per the Strategy Database outcome rule`() {
        val greenResult = CoreSignalEngine.evaluateResult(database, signal(Direction.GREEN), finalClose = 50_000.0)
        assertEquals(SignalStatus.LOST, greenResult.first) // GREEN signal, tie resolves to Red -> loses

        val redResult = CoreSignalEngine.evaluateResult(database, signal(Direction.RED), finalClose = 50_000.0)
        assertEquals(SignalStatus.WON, redResult.first) // RED signal, tie resolves to Red -> wins
    }

    @Test
    fun `Bug 1_1 - when outcomeTieCountsAsRed is false, a tie counts as Green instead`() {
        // The file-level `database` fixture above is hardcoded to outcomeTieCountsAsRed
        // = true, which is exactly how Bug 1.1 stayed hidden: both branches of the old
        // buggy if/else returned Direction.RED, so this flag had no effect on behavior
        // and the "false" branch was never exercised by any test. This test exercises it.
        val tieIsGreenDb = database.copy(
            hardConstraints = database.hardConstraints.copy(outcomeTieCountsAsRed = false)
        )
        val greenResult = CoreSignalEngine.evaluateResult(tieIsGreenDb, signal(Direction.GREEN), finalClose = 50_000.0)
        assertEquals(SignalStatus.WON, greenResult.first) // GREEN signal, tie now resolves to Green -> wins

        val redResult = CoreSignalEngine.evaluateResult(tieIsGreenDb, signal(Direction.RED), finalClose = 50_000.0)
        assertEquals(SignalStatus.LOST, redResult.first) // RED signal, tie now resolves to Green -> loses
    }

    @Test
    fun `Checkpoint C (Reversal-Zone) signals use their own +2 win, -1 loss model, not the database's`() {
        // The file-level `database` fixture's financialModel is winUsd=0.5, lossUsd=-1.0
        // (the primary A/B model). A Checkpoint.C signal must ignore that and use +2/-1
        // regardless -- see CoreSignalEngine.evaluateResult's Checkpoint.C branch.
        val c = signal(Direction.GREEN).copy(checkpoint = Checkpoint.C)

        val winResult = CoreSignalEngine.evaluateResult(database, c, finalClose = 50_010.0)
        assertEquals(SignalStatus.WON, winResult.first)
        assertEquals(2.0, winResult.second, 0.0001) // not 0.5

        val lossResult = CoreSignalEngine.evaluateResult(database, c, finalClose = 49_990.0)
        assertEquals(SignalStatus.LOST, lossResult.first)
        assertEquals(-1.0, lossResult.second, 0.0001) // same magnitude as the primary loss, but via its own config
    }

    @Test
    fun `Checkpoint C win-loss direction check is identical to A-B - same direction as the candle actually closed wins`() {
        val redC = signal(Direction.RED).copy(checkpoint = Checkpoint.C)
        val (status, pnl) = CoreSignalEngine.evaluateResult(database, redC, finalClose = 49_990.0)
        assertEquals(SignalStatus.WON, status)
        assertEquals(2.0, pnl, 0.0001)
    }

    @Test
    fun `balance simulation matches the fixed financial model`() {
        var balance = database.financialModel.startCapitalUsd
        val outcomes = listOf(true, true, false, true, false, false) // WIN, WIN, LOSS, WIN, LOSS, LOSS
        for (won in outcomes) {
            balance += if (won) database.financialModel.winUsd else database.financialModel.lossUsd
        }
        // 100 + 0.5 + 0.5 - 1.0 + 0.5 - 1.0 - 1.0 = 98.5
        assertEquals(98.5, balance, 0.0001)
    }
}

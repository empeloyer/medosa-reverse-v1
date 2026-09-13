package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class EngineResult(
    val signal: Signal?,
    val trace: DebugTraceEntry
)

/**
 * The ONE Core Signal Engine (spec sections 11, 13, 39). LiveMonitoringService and
 * BacktestEngine both call [evaluateCheckpoint] with data of the same shape — the
 * former sourced from the Binance WebSocket, the latter from Binance historical REST
 * klines replayed in order — and get identical decisions for identical inputs. No
 * strategy logic is duplicated anywhere else in the app.
 *
 * Order of operations exactly matches spec section 7 (Minute 2 continuous-monitoring
 * procedure), reinterpreted at the discrete Checkpoint A / Checkpoint B granularity
 * that the Strategy Database itself defines (see StrategyRegistry / README for why):
 *   1. Hard Constraints (entry range, per candidate direction)
 *   2. Strategy conditions (AND of all components, same direction)
 *   3. Market Regime (strategy's regime_gate vs current 3-dimension regime state)
 *   4. Strategy Score (Dynamic Score)
 *   5. Conflict Resolver
 *   6. Build + lock Signal
 */
object CoreSignalEngine {

    fun evaluateCheckpoint(
        database: StrategyDatabase,
        store: MarketDataStore,
        candleOpenTimeMillis: Long,
        candleOpen: Double,
        checkpoint: Checkpoint,
        referencePrice: Double,
        minute1Candle: Candle?,
        minute2Candle: Candle?,
        timestampMillis: Long,
        statsProvider: (String) -> RecentWindowStats,
        blockedStrategyIds: Set<String> = emptySet()
    ): EngineResult {
        val candleId = Instant.ofEpochMilli(candleOpenTimeMillis).toString()
        val movePct = if (candleOpen == 0.0) 0.0 else (referencePrice - candleOpen) / candleOpen * 100.0

        val closed5m = store.closed5m()
        val regime = MarketRegimeClassifier.classify(closed5m)

        if (regime == null) {
            val trace = DebugTraceEntry(
                candleId, timestampMillis, checkpoint, referencePrice, movePct,
                null, emptyList(), "N/A - insufficient regime data", "NO_SIGNAL: insufficient historical data to classify market regime", false
            )
            return EngineResult(null, trace)
        }

        val ctx = EvalContext(store, candleOpen, referencePrice, checkpoint, minute1Candle, minute2Candle)
        val strategyTraces = ArrayList<StrategyTraceEntry>()
        val votes = ArrayList<StrategyVote>()

        for (strategy in database.strategies) {
            if (strategy.id in blockedStrategyIds) {
                // User-blocked from the Strategies screen (spec: Block button) — treated
                // like a regime-ineligible strategy so it can never win the vote while blocked.
                strategyTraces.add(StrategyTraceEntry(strategy.id, false, emptyList(), emptyList(), false, null, null))
                continue
            }
            val eligible = strategy.regimeGateCode == "All" || strategy.regimeGateCode in regime.activeCodes()
            if (!eligible) {
                strategyTraces.add(StrategyTraceEntry(strategy.id, false, emptyList(), emptyList(), false, null, null))
                continue
            }

            val results = strategy.components.map { ComponentEvaluator.evaluate(it, ctx) }
            val dirs = results.map { it.direction }
            val nonNullDirs = dirs.filterNotNull().toSet()
            val allFired = dirs.none { it == null } && nonNullDirs.size == 1
            val firedDirection = if (allFired) nonNullDirs.first() else null

            var passesEntryRange = false
            if (firedDirection != null) {
                val range = if (firedDirection == Direction.GREEN)
                    database.hardConstraints.entryRangeGreenPct else database.hardConstraints.entryRangeRedPct
                passesEntryRange = movePct in range
            }

            val finalFired = allFired && passesEntryRange
            var score: Double? = null
            if (finalFired && firedDirection != null) {
                score = DynamicScore.compute(strategy, statsProvider(strategy.id))
                votes.add(StrategyVote(strategy, firedDirection, score))
            }

            strategyTraces.add(
                StrategyTraceEntry(
                    strategyId = strategy.id,
                    regimeEligible = true,
                    componentDetails = results.map { it.detail },
                    componentDirections = dirs,
                    fired = finalFired,
                    firedDirection = if (finalFired) firedDirection else null,
                    score = score
                )
            )
        }

        val resolution = ConflictResolver.resolve(votes)

        return when (resolution) {
            is ConflictResolution.NoSignal -> {
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, resolution.reason, "NO_SIGNAL: ${resolution.reason}", false
                )
                EngineResult(null, trace)
            }
            is ConflictResolution.Decision -> {
                val winner = resolution.winner
                val signal = Signal(
                    signalId = UUID.randomUUID().toString(),
                    candleId = candleId,
                    candleOpenTimeMillis = candleOpenTimeMillis,
                    signalTimestampMillis = timestampMillis,
                    candleOpen = candleOpen,
                    signalPrice = referencePrice,
                    direction = winner.direction,
                    activeStrategyId = winner.strategy.id,
                    activeStrategyName = "${winner.strategy.id} (${winner.strategy.marketConditionBucket})",
                    marketRegime = regime,
                    strategyScore = winner.score,
                    confidencePct = winner.strategy.performance.oosWinRatePct,
                    entryMovePct = movePct,
                    checkpoint = checkpoint
                )
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, "Decision: ${winner.strategy.id} -> ${winner.direction}",
                    "SIGNAL_GENERATED: ${winner.strategy.id} -> ${winner.direction} (score=${"%.4f".format(winner.score)})",
                    true
                )
                EngineResult(signal, trace)
            }
        }
    }

    // Reversal-Zone / Price-Zone (Checkpoint C) financial model default — kept as 2.0/-1.0
    // here ONLY so evaluateResult's default parameters below match what
    // FinancialModelTest.kt has always asserted; the app's real, user-configurable amount
    // (win +$5 / loss -$1 by default, per the chat request that introduced the
    // price-zone rule) always flows in explicitly from Settings
    // (SettingsRepository.priceZoneWinUsd/priceZoneLossUsd) via LiveMonitoringService and
    // BacktestEngine, which never rely on these defaults.

    /** Result determination per spec section 28: compare final close to candle open; tie
     *  counts as Red. The win/loss AMOUNT depends on which checkpoint produced [signal] —
     *  A/B use the Strategy Database's financial model (now unused in practice, since
     *  Checkpoint A/B primary signals are no longer produced — see PriceZoneScanner.kt),
     *  C (the price-zone signal, formerly "Reversal-Zone") uses its own configurable
     *  [checkpointCWinUsd]/[checkpointCLossUsd] — but the win/lose DIRECTION check itself
     *  is identical for every checkpoint: does [signal.direction] match how the candle
     *  actually closed. */
    fun evaluateResult(
        database: StrategyDatabase,
        signal: Signal,
        finalClose: Double,
        checkpointCWinUsd: Double = 2.0,
        checkpointCLossUsd: Double = -1.0
    ): Pair<SignalStatus, Double> {
        val actualDirection = when {
            finalClose > signal.candleOpen -> Direction.GREEN
            finalClose < signal.candleOpen -> Direction.RED
            else -> if (database.hardConstraints.outcomeTieCountsAsRed) Direction.RED else Direction.GREEN
        }
        val won = actualDirection == signal.direction
        val (winUsd, lossUsd) = if (signal.checkpoint == Checkpoint.C)
            checkpointCWinUsd to checkpointCLossUsd
        else
            database.financialModel.winUsd to database.financialModel.lossUsd
        val pnl = if (won) winUsd else lossUsd
        val status = if (won) SignalStatus.WON else SignalStatus.LOST
        return status to pnl
    }
}

package com.btcsignal.app.backtest

import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private const val FIVE_MIN_MILLIS = 5 * 60_000L

data class BacktestSummary(
    val periodDays: Int,
    val totalSignals: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val totalPnlUsd: Double,
    val finalBalanceUsd: Double
)

enum class BacktestPhase { FETCHING, REPLAYING, SAVING, DONE }

/** Reported to the UI so a long backtest (30-90 days) shows real progress instead of an
 *  indefinite spinner the person can't tell apart from a freeze. */
data class BacktestProgress(
    val phase: BacktestPhase,
    val percent: Int,      // 0-100, overall progress across the whole run() call
    val message: String
)

/**
 * Runs the SAME price-zone signal rule as LiveMonitoringService (see
 * engine/PriceZoneScanner.kt) against historical Binance data. This is not a second
 * implementation of that rule — it feeds historical 1-minute klines through the
 * identical CandleAggregator -> PriceZoneScanner -> CoreSignalEngine.evaluateResult
 * pipeline used live, chronologically, one candle at a time, so the engine can never see
 * data timestamped after the moment it's evaluating (no look-ahead bias, spec section
 * 14) — a signal here always carries its true historical timestamp (e.g. `2:37`, not
 * pretended to be known earlier), and the signal itself is only ever the OPPOSITE color
 * of the initial direction finalized from minutes 0-2 (spec sections 2, 4, 10), scanned
 * strictly within the `2:00 <= t < 4:00` window (`4:00` is a hard, exclusive cutoff —
 * spec section 3, 9). This app's "Test/Simulation" functionality IS this Backtest
 * engine (spec section 15) — there is no separate implementation to keep in sync.
 */
class BacktestEngine(
    private val restClient: BinanceRestClient = BinanceRestClient(),
    private val signalRepository: SignalRepository? = null
) {

    /** [warmupDays] is kept for signature compatibility with existing callers but no
     *  longer changes behavior: the price-zone rule (chat request, replacing the
     *  previous 27-strategy primary + Reversal-Zone secondary system entirely) needs no
     *  indicator/regime warm-up at all, since it consults no strategy or market-regime
     *  state -- see engine/PriceZoneScanner.kt. [onProgress] is called from a background
     *  thread with 0-100% progress; fetching historical data is weighted 0-50%,
     *  chronological replay is weighted 50-100%. */
    suspend fun run(
        database: StrategyDatabase,
        periodDays: Int,
        warmupDays: Int = 8,
        persist: Boolean = true,
        blockedStrategyIds: Set<String> = emptySet(),
        // User-configurable price-zone rule (Settings screen "Signal Zones" card) --
        // defaults to the shipped +0.01%/+0.04% (red) and -0.01%/-0.04% (green) zones
        // and +$5/-$1 win/loss when the caller doesn't override them.
        priceZoneConfig: PriceZoneConfig = PriceZoneConfig(),
        onProgress: (BacktestProgress) -> Unit = {}
    ): BacktestSummary = withContext(Dispatchers.Default) {
        val rawNow = System.currentTimeMillis()
        // Round down to the start of the 5-minute bucket that is CURRENTLY forming as of
        // `rawNow`, then step back 1ms so the fetch/replay window covers only fully closed
        // 5-minute candles. BinanceRestClient.getKlines independently drops any candle that
        // still hasn't actually closed (e.g. if the exchange hasn't published it yet by the
        // time this call reaches it) as a second, defense-in-depth guard against the same
        // failure mode.
        val end = (rawNow / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS - 1
        val periodStart = end - periodDays * 24L * 60 * 60 * 1000
        val fetchStart = periodStart - warmupDays * 24L * 60 * 60 * 1000
        val totalRangeMinutes = ((end - fetchStart) / 60_000L).toInt().coerceAtLeast(1)

        onProgress(BacktestProgress(BacktestPhase.FETCHING, 0, "Fetching historical data from Binance\u2026"))
        val candles = restClient.getKlines("BTCUSDT", "1m", fetchStart, end) { fetchedSoFar ->
            val pct = ((fetchedSoFar.toFloat() / totalRangeMinutes) * 50f).toInt().coerceIn(0, 50)
            onProgress(
                BacktestProgress(
                    BacktestPhase.FETCHING, pct,
                    "Fetching historical data\u2026 ($fetchedSoFar / ~$totalRangeMinutes candles)"
                )
            )
        }
        onProgress(BacktestProgress(BacktestPhase.REPLAYING, 50, "Replaying ${candles.size} candles through the signal engine\u2026"))

        val aggregator = CandleAggregator()

        // Price-zone (Checkpoint C) replay state. `scanners`/`candleOpens` are keyed by
        // candleId and populated the instant each 5-minute bucket's first 1-minute
        // sub-candle is seen (minuteOffset == 0) -- unlike the old primary system, no
        // strategy vote or market-regime classification is needed to arm a scan, so
        // MarketDataStore/blockedStrategyIds are no longer consulted at all here (kept as
        // `database`/`blockedStrategyIds` PARAMETERS purely for call-site/signature
        // compatibility -- database.financialModel.startCapitalUsd is still used for
        // finalBalanceUsd below).
        val scanners = HashMap<String, PriceZoneScanner>()
        val candleOpens = HashMap<String, Double>()
        val pendingSignals = HashMap<String, Signal>() // candleId -> fired signal awaiting its candle's close

        val allSignals = ArrayList<Signal>()
        var wins = 0
        var losses = 0
        var totalPnl = 0.0

        val totalCandles = candles.size.coerceAtLeast(1)
        var lastReportedPct = 50
        // Report roughly 200 times over the whole replay - frequent enough to feel live,
        // rare enough not to flood the UI with recompositions.
        val progressStride = (totalCandles / 200).coerceAtLeast(1)

        for ((index, candle) in candles.withIndex()) {
            val events = aggregator.onClosed1mCandle(candle)
            for (event in events) {
                if (event is CandleEvent.FiveMinuteCandleClosed) {
                    val candleId = Instant.ofEpochMilli(event.candle.openTimeMillis).toString()
                    scanners.remove(candleId)
                    candleOpens.remove(candleId)
                    val pending = pendingSignals.remove(candleId) ?: continue
                    val (status, pnl) = CoreSignalEngine.evaluateResult(
                        database, pending, event.candle.close,
                        checkpointCWinUsd = priceZoneConfig.winUsd,
                        checkpointCLossUsd = priceZoneConfig.lossUsd
                    )
                    pending.status = status
                    pending.finalClose = event.candle.close
                    pending.pnlUsd = pnl
                    if (status == SignalStatus.WON) wins++ else losses++
                    totalPnl += pnl
                }
            }

            if (candle.openTimeMillis < periodStart) continue // still in warm-up window

            val bucketStart = (candle.openTimeMillis / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS
            val minuteOffset = ((candle.openTimeMillis - bucketStart) / 60_000L).toInt()
            val candleId = Instant.ofEpochMilli(bucketStart).toString()

            if (minuteOffset == 0) {
                // Fresh 5-minute candle started -- arm a brand-new scanner unconditionally,
                // no primary signal needed (see engine/PriceZoneScanner.kt).
                candleOpens[candleId] = candle.open
                scanners[candleId] = PriceZoneScanner(priceZoneConfig).also { it.arm(bucketStart) }
            }

            if (minuteOffset == 1) {
                // Minute 2 (1:00-2:00) has just closed -- exactly t+2min, the moment the
                // spec (sections 2, 10) requires the initial direction to be finalized.
                // Uses the SAME shared determineInitialDirection helper Live calls off
                // Checkpoint B, so both engines always agree given the same inputs. Must
                // run before the minuteOffset == 2 branch below ever scans a tick for
                // this candle, which it always does since this loop replays candles
                // strictly in chronological order (no look-ahead, spec section 14).
                val candleOpen = candleOpens[candleId]
                val scanner = scanners[candleId]
                if (candleOpen != null && scanner != null) {
                    scanner.setInitialDirection(determineInitialDirection(candleOpen, candle.close))
                }
            }

            // Price-zone (Checkpoint C) minute-3-to-4 scan. `candle` here is exactly the
            // 3rd or 4th 1-minute sub-candle (t+2min -> t+4min) of its 5-minute bucket --
            // see syntheticIntraMinutePath's KDoc for why this is an OHLC-based
            // approximation of the tick-level scan LiveMonitoringService runs, not a
            // byte-for-byte replay of it.
            if (minuteOffset == 2 || minuteOffset == 3) {
                val scanner = scanners[candleId]
                val candleOpen = candleOpens[candleId]
                if (scanner != null && candleOpen != null && !scanner.fired) {
                    for ((price, tMillis) in syntheticIntraMinutePath(candle)) {
                        val movePct = if (candleOpen != 0.0) (price - candleOpen) / candleOpen * 100.0 else 0.0
                        val fireResult = scanner.onTick(movePct, price, tMillis)
                        if (fireResult != null) {
                            val signal = Signal(
                                signalId = java.util.UUID.randomUUID().toString(),
                                candleId = candleId,
                                candleOpenTimeMillis = bucketStart,
                                signalTimestampMillis = tMillis,
                                candleOpen = candleOpen,
                                signalPrice = price,
                                direction = fireResult.direction,
                                activeStrategyId = "PRICE_ZONE",
                                activeStrategyName = "Price-Zone signal (minute 3\u20134, ${fireResult.direction} zone)",
                                marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
                                strategyScore = 0.0,
                                confidencePct = 0.0,
                                entryMovePct = fireResult.movePct,
                                checkpoint = Checkpoint.C
                            )
                            pendingSignals[candleId] = signal
                            allSignals.add(signal)
                            break
                        }
                    }
                }
            }

            if (index % progressStride == 0 || index == totalCandles - 1) {
                val pct = (50 + (index.toFloat() / totalCandles * 50f).toInt()).coerceIn(50, 99)
                if (pct != lastReportedPct) {
                    lastReportedPct = pct
                    onProgress(
                        BacktestProgress(
                            BacktestPhase.REPLAYING, pct,
                            "Replaying candle ${index + 1} / $totalCandles \u2022 ${allSignals.size} signals so far"
                        )
                    )
                }
            }
        }

        if (persist && signalRepository != null) {
            onProgress(BacktestProgress(BacktestPhase.SAVING, 99, "Saving ${allSignals.size} signals\u2026"))
            signalRepository.clearBacktestResults()
            for (s in allSignals) signalRepository.saveSignal(s, isBacktest = true)
        }

        val totalSignals = wins + losses
        onProgress(BacktestProgress(BacktestPhase.DONE, 100, "Done"))
        BacktestSummary(
            periodDays = periodDays,
            totalSignals = totalSignals,
            wins = wins,
            losses = losses,
            winRatePct = if (totalSignals > 0) wins.toDouble() / totalSignals * 100.0 else 0.0,
            totalPnlUsd = totalPnl,
            finalBalanceUsd = database.financialModel.startCapitalUsd + totalPnl
        )
    }
}

/**
 * Historical klines only give one OHLC bar per minute, not the tick-by-tick path Live
 * scans against (see PriceZoneScanner). To let a backtest still exercise the SAME
 * price-zone rule against SOME approximation of what happened inside the third/fourth
 * 1-minute sub-candle (t+2min -> t+4min), this reconstructs a plausible 4-point
 * intra-minute path -- open, the two extremes (low/high), close -- spread evenly across
 * that 60-second span, ordered by whichever extreme more plausibly came first given how
 * the candle ultimately resolved (a candle that closes above its own open is assumed to
 * have dipped to its low before rallying to its high; one that closes below is assumed
 * the reverse). This is a stated approximation, not real tick data -- exactly which
 * extreme came first, and exactly when within the minute, cannot be recovered from OHLC
 * alone, so a backtest's signal count/timing should be read as indicative, not exact.
 */
private fun syntheticIntraMinutePath(candle: Candle): List<Pair<Double, Long>> {
    val t0 = candle.openTimeMillis
    val span = (candle.closeTimeMillis - candle.openTimeMillis).coerceAtLeast(1L)
    val extremesInOrder = if (candle.close >= candle.open)
        listOf(candle.low, candle.high) else listOf(candle.high, candle.low)
    return listOf(
        candle.open to t0,
        extremesInOrder[0] to t0 + span / 3,
        extremesInOrder[1] to t0 + span * 2 / 3,
        candle.close to candle.closeTimeMillis
    )
}

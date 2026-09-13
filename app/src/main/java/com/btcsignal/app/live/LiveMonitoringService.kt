package com.btcsignal.app.live

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.compose.ui.geometry.Offset
import com.btcsignal.app.MainActivity
import com.btcsignal.app.R
import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.binance.BinanceStreamListener
import com.btcsignal.app.data.binance.BinanceWebSocketClient
import com.btcsignal.app.data.binance.ConnectionState
import com.btcsignal.app.data.local.AppDatabase
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.repository.SettingsRepository
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.*
import com.btcsignal.app.notifications.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The Live Engine (spec sections 25-27). Runs as a foreground service so monitoring can
 * continue while the app is backgrounded, subject to normal Android restrictions.
 * Wires: BinanceWebSocketClient -> MarketDataStore -> CandleAggregator ->
 * CoreSignalEngine -> SignalRepository (Room) -> NotificationHelper, all funneling
 * through LiveEngineState for the UI. This is the ONLY place the live path is wired;
 * BacktestEngine wires the same CoreSignalEngine independently for historical replay.
 */
class LiveMonitoringService : Service(), BinanceStreamListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var database: StrategyDatabase
    private val marketDataStore = MarketDataStore()
    private val aggregator = CandleAggregator()
    private lateinit var wsClient: BinanceWebSocketClient
    private val restClient = BinanceRestClient()
    private lateinit var signalRepo: SignalRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var prefs: SharedPreferences

    @Volatile private var resyncing = true
    @Volatile private var lastProcessedOpenTime = 0L

    /** Owns the price-zone scan (Checkpoint C, minute-3-to-4) for the CURRENT candle
     *  only — replaced with a fresh instance, armed immediately, every time a new
     *  5-minute candle starts (see the isNewBucket branch of onKlineUpdate). Unlike the
     *  old ReversalZoneScanner, arming needs no primary signal to already exist — see
     *  engine/PriceZoneScanner.kt for the full rule (chat request, 2026-09-12, which
     *  replaced the previous 27-strategy primary + Reversal-Zone secondary system
     *  entirely). onKlineUpdate is always invoked serially on the main thread (see
     *  BinanceWebSocketClient), so plain non-atomic state here is safe, same as
     *  lastResetBucketStart below. */
    private var priceZoneScanner = PriceZoneScanner()

    /** Latest price-zone rule settings, kept in sync by a dedicated collector started in
     *  onCreate(). onKlineUpdate -- where a fresh PriceZoneScanner is built for each new
     *  candle -- is a plain (non-suspend) callback, so it cannot itself call
     *  settingsRepo.settingsFlow.first(); this cached copy is what lets a value saved on
     *  the Settings screen apply starting with the very next 5-minute candle, without
     *  waiting on a suspend call at exactly the wrong moment. */
    @Volatile private var priceZoneConfig = PriceZoneConfig()

    /** The 5-minute bucket start we last froze/reset the live price-path log for (see
     *  onKlineUpdate). Exists purely to make that reset fire exactly once per real
     *  boundary crossing -- CandleAggregator.isNewBucket() alone stays true for every
     *  still-forming tick across the whole ~60s a bucket's first 1-minute sub-candle
     *  takes to close, because the aggregator's own internal bucketStart doesn't
     *  advance until then. onKlineUpdate is always invoked serially on the main thread
     *  (see BinanceWebSocketClient), so plain non-atomic state here is safe. */
    private var lastResetBucketStart = -1L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("live_service_prefs", MODE_PRIVATE)
        prefs.edit { putBoolean("was_running", true) }

        database = StrategyRegistry.load(applicationContext)
        signalRepo = SignalRepository(AppDatabase.get(applicationContext).signalDao())
        settingsRepo = SettingsRepository(applicationContext)
        notificationHelper = NotificationHelper(applicationContext)
        wsClient = BinanceWebSocketClient(this)

        startForeground(FOREGROUND_ID, buildForegroundNotification("Connecting to Binance\u2026"))

        // The persistent notification previously only got refreshed from a couple of
        // scattered call sites (a connection-state change, a freshly locked signal), so
        // its text quickly went stale: once the engine moved on to a later phase
        // (ANALYZING, WAITING, CANDLE_CLOSED...) with no call site nearby, the
        // notification just kept showing whatever text was last set - commonly "Status:
        // SYNCING" if a background resync happened to run and set that appState value
        // around the same moment the notification text was last composed. Instead, the
        // notification now continuously mirrors the actual current state by observing
        // it directly, so it can never drift from what the app is really doing.
        serviceScope.launch {
            combine(LiveEngineState.appState, LiveEngineState.currentSignal) { state, signal ->
                notificationTextFor(state, signal)
            }.collect { text -> updateForegroundNotification(text) }
        }

        // Keep priceZoneConfig current so the NEXT candle's fresh PriceZoneScanner (see
        // onKlineUpdate's isNewBucket branch) always picks up whatever Signal Zones
        // values were last Saved on the Settings screen -- a candle already in progress
        // keeps whatever config its own scanner was built with, exactly like every other
        // per-candle engine state in this class.
        serviceScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                priceZoneConfig = PriceZoneConfig(
                    redLowPct = s.priceZoneRedLowPct,
                    redHighPct = s.priceZoneRedHighPct,
                    greenLowPct = s.priceZoneGreenLowPct,
                    greenHighPct = s.priceZoneGreenHighPct,
                    winUsd = s.priceZoneWinUsd,
                    lossUsd = s.priceZoneLossUsd,
                    waitEnabled = s.priceZoneWaitEnabled
                )
            }
        }

        LiveEngineState.appState.value = AppState.SYNCING
        serviceScope.launch {
            warmUp()
            resyncing = false
            wsClient.connect()
        }
    }

    private fun notificationTextFor(state: AppState, signal: Signal?): String = when (state) {
        AppState.SIGNAL_LOCKED -> signal?.let { "Signal locked: ${it.direction} \u2022 ${it.activeStrategyId}" }
            ?: "Status: $state"
        else -> "Status: $state"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        prefs.edit { putBoolean("was_running", false) }
        wsClient.disconnect()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** Backfills enough 1-minute history via REST for every indicator's longest lookback
     *  before trusting live data (spec section 26: "resynchronize candle state before
     *  allowing a new signal"). 4h ADX needs 2*period+1=29 closed 4h candles => up to
     *  ~4.8 days; we pull 8 days for headroom. */
    private suspend fun warmUp() {
        val end = System.currentTimeMillis()
        val start = end - WARMUP_DAYS * 24L * 60 * 60 * 1000
        try {
            val candles = restClient.getKlines("BTCUSDT", "1m", start, end)
            for (c in candles) {
                marketDataStore.addClosed1m(c)
                val events = aggregator.onClosed1mCandle(c)
                processAggregatorEvents(events)
                lastProcessedOpenTime = c.openTimeMillis
            }
        } catch (e: Exception) {
            LiveEngineState.appState.value = AppState.ERROR
        }
    }

    override fun onKlineUpdate(candle: Candle) {
        LiveEngineState.livePrice.value = candle.close

        val tickBucketStart = aggregator.bucketStartFor(candle)

        if (aggregator.isNewBucket(candle) && tickBucketStart != lastResetBucketStart) {
            // Real boundary crossing into a new 5-minute candle -- the next 5-minute
            // candle has already started forming on Binance's side (its first 1-minute
            // sub-candle just opened). Guarded on lastResetBucketStart (and no longer on
            // !candle.isClosed) so this fires EXACTLY ONCE per boundary: on its own,
            // aggregator.isNewBucket(candle) stays true for every still-forming tick
            // across the whole ~60s this bucket's first 1-minute sub-candle takes to
            // close (CandleAggregator's own bucketStart only advances then, via
            // onClosed1mCandle below), and Binance pushes a kline update roughly every
            // 1-2s -- so this block used to re-run dozens of times per candle instead of
            // once. Each re-run clobbered candlePriceLog back down to a single point AND
            // overwrote lastClosedCandlePriceLog/lastClosedCandleOpenTime -- the frozen
            // copy handleCandleClosed relies on to snapshot the candle that just
            // finished -- with that same near-empty, mis-timestamped data, right as
            // handleCandleClosed was about to read it a minute later. That's what turned
            // History's saved "Price Move" chart into a sparse, disjointed line instead
            // of the real observed path.
            // Freeze the just-finished candle's full path (and record which candle it
            // belongs to) BEFORE candleOpenTimeMillis below gets overwritten with the
            // new candle's -- and before wiping candlePriceLog for the new candle (see
            // LiveEngineState.lastClosedCandlePriceLog KDoc for why this can't wait
            // until handleCandleClosed reads it later; by then it's too late, this same
            // clear would already have overwritten it with the new candle's ticks).
            // onKlineUpdate is always invoked serially on the main thread (see
            // BinanceWebSocketClient), so this freeze is guaranteed to run before any
            // later tick can start refilling the log for the new candle.
            lastResetBucketStart = tickBucketStart
            synchronized(LiveEngineState.candlePriceLog) {
                LiveEngineState.lastClosedCandlePriceLog = ArrayList(LiveEngineState.candlePriceLog)
                LiveEngineState.lastClosedCandleOpenTime =
                    LiveEngineState.candleOpenTimeMillis.value.takeIf { it > 0 }
                LiveEngineState.candlePriceLog.clear()
                LiveEngineState.candlePriceLog.add(Offset(0f, candle.open.toFloat()))
            }
            LiveEngineState.candleOpen.value = candle.open
            LiveEngineState.candleOpenTimeMillis.value = tickBucketStart
            LiveEngineState.currentMovePct.value =
                if (candle.open != 0.0) (candle.close - candle.open) / candle.open * 100.0 else 0.0
            LiveEngineState.candlePhase.value = CandlePhase.MINUTE_1
            LiveEngineState.appState.value = AppState.ANALYZING
            LiveEngineState.currentSignal.value = null
            LiveEngineState.secondarySignal.value = null
            // Fresh scanner for the new candle, armed immediately -- unlike the old
            // ReversalZoneScanner this never waits on a primary signal (there is no
            // primary anymore, see engine/PriceZoneScanner.kt).
            priceZoneScanner = PriceZoneScanner(priceZoneConfig)
            priceZoneScanner.arm(tickBucketStart)
        } else if (tickBucketStart == LiveEngineState.candleOpenTimeMillis.value) {
            // A later tick within the bucket we already have open -- including the rest
            // of minute 1, before CandleAggregator's own bucketStart has caught up to
            // it. Deliberately NOT re-derived from aggregator.currentCandleOpen()/
            // currentCandleOpenTimeMillis() (see the else branch below): during exactly
            // that lag window those still point at the PREVIOUS candle, so reading them
            // here used to stomp the correct values set above back to stale ones on
            // every tick for the rest of the minute -- which is also what fed the
            // strictly-increasing elapsed-time guard in the price-log block below a
            // bogus, far-out-of-range timestamp that then silently suppressed all
            // further logging for the remainder of the candle.
            val open = LiveEngineState.candleOpen.value
            LiveEngineState.currentMovePct.value = if (open != 0.0) (candle.close - open) / open * 100.0 else 0.0
        } else {
            // Cold start only: the aggregator (via warmUp()) already has a bucket open
            // that LiveEngineState hasn't recorded yet. Safe to trust it here only
            // because it already matches this tick's own bucket -- unlike the lag
            // window above, there's no risk of it still pointing at a previous candle.
            aggregator.currentCandleOpen()
                ?.takeIf { aggregator.currentCandleOpenTimeMillis() == tickBucketStart }
                ?.let { open ->
                    LiveEngineState.candleOpen.value = open
                    LiveEngineState.candleOpenTimeMillis.value = tickBucketStart
                    LiveEngineState.currentMovePct.value =
                        if (open != 0.0) (candle.close - open) / open * 100.0 else 0.0
                }
        }

        // Log this tick into the current candle's chart-snapshot path, independent of
        // whether the Live screen is on-screen (see candlePriceLog KDoc).
        val openTime = LiveEngineState.candleOpenTimeMillis.value
        if (openTime > 0) {
            val elapsed = (System.currentTimeMillis() - openTime).coerceAtLeast(0L)
            synchronized(LiveEngineState.candlePriceLog) {
                // Deliberately no size cap / trim-from-front here. This array is frozen
                // verbatim as the permanent History snapshot the instant the candle
                // closes (see handleCandleClosed + lastClosedCandlePriceLog), and it is
                // fully clear()'d every 5 minutes by the isNewBucket branch above, so its
                // lifetime -- and therefore its size -- is inherently bounded to one
                // candle's worth of ticks regardless of tick rate (a few thousand Offsets
                // at most, negligible on a mobile device). A prior version of this code
                // capped it at 600 and dropped the OLDEST point once exceeded -- but the
                // oldest point is always the one nearest candle open / the Target line,
                // so on a busy stream (Binance can push several kline updates per second)
                // that cap silently deleted the beginning of the path well before the
                // candle closed, leaving History snapshots that start mid-candle, far
                // from x=0 and the Target line, showing only a short, often flat tail.
                // Do not reintroduce a cap on this specific array.
                // Guard against the elapsed-time axis ever running backwards (e.g. a
                // device clock step/NTP correction between two ticks), which would draw
                // the purple path doubling back on itself as a jagged spike instead of
                // moving steadily left-to-right.
                val lastElapsed = LiveEngineState.candlePriceLog.lastOrNull()?.x ?: -1f
                if (elapsed.toFloat() > lastElapsed) {
                    LiveEngineState.candlePriceLog.add(Offset(elapsed.toFloat(), candle.close.toFloat()))
                }
            }
        }

        // THE signal rule (Checkpoint C, minute-3-to-4 scan; chat request, replaces the
        // previous 27-strategy primary + Reversal-Zone secondary system entirely). Fed
        // unconditionally on every tick -- PriceZoneScanner itself ignores anything
        // outside its armed window / after already firing this candle (see its KDoc), so
        // this is a no-op on every tick except the one, at most, that matters. Guarded on
        // `resyncing` so a post-reconnect catch-up tick can never lock a brand-new signal
        // off incomplete state (section 26) -- the same guard handleCheckpoint used to
        // apply for the old primary signal.
        if (!resyncing) {
            val fireResult = priceZoneScanner.onTick(
                LiveEngineState.currentMovePct.value, candle.close, System.currentTimeMillis()
            )
            if (fireResult != null) {
                serviceScope.launch { handlePriceZoneFire(fireResult) }
            }
        }

        if (!candle.isClosed) return
        if (candle.openTimeMillis <= lastProcessedOpenTime) return // duplicate guard (section 27)
        lastProcessedOpenTime = candle.openTimeMillis

        marketDataStore.addClosed1m(candle)
        val events = aggregator.onClosed1mCandle(candle)

        // Finalize the initial direction (spec sections 2 & 10) synchronously, right
        // here on the same thread/callback as onTick above -- deliberately NOT inside
        // the async processAggregatorEvents launch below. Checkpoint B fires the
        // instant minute 2's 1-minute candle closes, i.e. exactly at `2:00`, the same
        // moment the signal window opens -- computing it asynchronously left a window
        // where a tick already inside the opposite zone at/just after `2:00` could be
        // judged by onTick before setInitialDirection had actually run, silently
        // missing a valid entry. events is already fully computed above, so reading it
        // here costs nothing extra and closes that race for good.
        for (event in events) {
            if (event is CandleEvent.CheckpointReached && event.checkpoint == Checkpoint.B) {
                priceZoneScanner.setInitialDirection(
                    determineInitialDirection(event.candleOpen, event.referencePrice)
                )
            }
        }

        serviceScope.launch {
            processAggregatorEvents(events)
        }
    }

    /**
     * Applies aggregator events wherever they're produced. Previously `warmUp()` and
     * `resyncGap()` called `aggregator.onClosed1mCandle(c)` purely to keep the
     * aggregator's internal bucket state in sync, but threw away the returned events.
     * Any `FiveMinuteCandleClosed` event generated while catching up on REST history
     * (an app restart, a Doze-mode pause, a dropped WebSocket reconnecting after a gap)
     * was silently lost — so a signal that had already been locked for that candle
     * before the gap never had its outcome (WON/LOST) recorded and stayed ACTIVE
     * forever, even though the candle it depended on had long since closed. Routing
     * every call site through this same function means a gap-filled candle close is
     * resolved exactly like a live one. `handleCheckpoint` still separately guards on
     * `resyncing` so no NEW signal is ever issued from stale catch-up data - only
     * existing ones get their result recorded.
     */
    private suspend fun processAggregatorEvents(events: List<CandleEvent>) {
        for (event in events) {
            when (event) {
                is CandleEvent.PhaseChanged -> {
                    LiveEngineState.candlePhase.value = event.phase
                    LiveEngineState.appState.value = when (event.phase) {
                        CandlePhase.MINUTE_1, CandlePhase.MINUTE_2 -> AppState.ANALYZING
                        CandlePhase.PREDICTION_WINDOW_CLOSED -> AppState.PREDICTION_WINDOW_CLOSED
                        CandlePhase.CANDLE_CLOSED -> AppState.CANDLE_CLOSED
                    }
                    if (event.phase == CandlePhase.MINUTE_1) LiveEngineState.reset()
                }
                is CandleEvent.CheckpointReached -> {} // no-op: the price-zone rule needs no per-checkpoint strategy evaluation (see engine/PriceZoneScanner.kt)
                is CandleEvent.FiveMinuteCandleClosed -> handleCandleClosed(event.candle)
            }
        }
    }

    /** No longer called (see the CandleEvent.CheckpointReached no-op branch in
     *  processAggregatorEvents above) -- the app no longer evaluates any 27-strategy
     *  primary vote at all, per the chat request that introduced the price-zone rule
     *  (engine/PriceZoneScanner.kt). Left in place, unused, rather than deleted, in case
     *  the primary strategy system is ever wanted again; CoreSignalEngine.evaluateCheckpoint
     *  itself is untouched and still fully tested (CoreSignalEngineTest.kt). */
    @Suppress("unused")
    private suspend fun handleCheckpoint(event: CandleEvent.CheckpointReached) {
        if (resyncing) return // never signal off incomplete post-reconnect state (section 26)
        val candleId = java.time.Instant.ofEpochMilli(event.candleOpenTimeMillis).toString()
        if (signalRepo.isCandleLocked(candleId, isBacktest = false)) return // signal lock (section 9)

        // Precompute rolling stats for every strategy up front (suspend), so the engine
        // itself stays synchronous and identical between Live and Backtest call sites.
        val statsCache = HashMap<String, RecentWindowStats>()
        for (s in database.strategies) {
            statsCache[s.id] = signalRepo.recentWindowStatsFor(s.id, database.financialModel.breakevenWinRatePct)
        }
        val settings = settingsRepo.settingsFlow.first()

        val result = CoreSignalEngine.evaluateCheckpoint(
            database = database,
            store = marketDataStore,
            candleOpenTimeMillis = event.candleOpenTimeMillis,
            candleOpen = event.candleOpen,
            checkpoint = event.checkpoint,
            referencePrice = event.referencePrice,
            minute1Candle = event.minute1,
            minute2Candle = event.minute2,
            timestampMillis = event.timestampMillis,
            statsProvider = { id -> statsCache[id] ?: RecentWindowStats(0, 0.0) },
            blockedStrategyIds = settings.blockedStrategyIds
        )
        LiveEngineState.pushTrace(result.trace)
        result.trace.regime?.let { LiveEngineState.marketRegime.value = it }
    }

    /** Fires the instant PriceZoneScanner.onTick reports a qualifying minute-3-to-4
     *  entry (Checkpoint C) — the ONLY signal type the app produces now (chat request,
     *  2026-09-12). Builds and locks a Signal directly from the current candle's own
     *  state; no primary signal is needed or consulted at all (contrast the old
     *  handleReversalZoneFire, which required LiveEngineState.currentSignal to already
     *  be set). Win/loss is judged later, on candle close, using its own timestamp and
     *  (via CoreSignalEngine.evaluateResult's Checkpoint.C branch) the currently
     *  configured priceZoneWinUsd/priceZoneLossUsd from Settings. */
    private suspend fun handlePriceZoneFire(fireResult: PriceZoneScanner.FireResult) {
        val candleOpenTimeMillis = LiveEngineState.candleOpenTimeMillis.value
        val candleOpen = LiveEngineState.candleOpen.value
        if (candleOpenTimeMillis <= 0 || candleOpen == 0.0) return // defensive: no candle open yet
        val candleId = java.time.Instant.ofEpochMilli(candleOpenTimeMillis).toString()
        if (signalRepo.isCandleLocked(candleId, isBacktest = false)) return // signal lock (section 9) -- at most one per candle
        val settings = settingsRepo.settingsFlow.first()
        val signal = Signal(
            signalId = UUID.randomUUID().toString(),
            candleId = candleId,
            candleOpenTimeMillis = candleOpenTimeMillis,
            signalTimestampMillis = System.currentTimeMillis(),
            candleOpen = candleOpen,
            signalPrice = fireResult.price,
            direction = fireResult.direction,
            activeStrategyId = "PRICE_ZONE",
            activeStrategyName = "Price-Zone signal (minute 3\u20134, ${fireResult.direction} zone)",
            marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
            strategyScore = 0.0,
            confidencePct = 0.0,
            entryMovePct = fireResult.movePct,
            checkpoint = Checkpoint.C
        )
        signalRepo.saveSignal(signal, isBacktest = false)
        LiveEngineState.currentSignal.value = signal
        LiveEngineState.appState.value = AppState.SIGNAL_LOCKED
        if (settings.notificationsEnabled && signalRepo.markNotifiedIfNeeded(signal.signalId)) {
            notificationHelper.notifySignal(signal, settings.soundEnabled, settings.vibrationEnabled)
        }
    }

    private suspend fun handleCandleClosed(candle: Candle) {
        val candleId = java.time.Instant.ofEpochMilli(candle.openTimeMillis).toString()
        // A candle now carries at most ONE still-ACTIVE live signal (the price-zone
        // signal, if the minute-3-to-4 scan fired one) -- see SignalDao KDoc.
        val activeSignals = signalRepo.getActiveLiveSignalsForCandle(candleId)
        if (activeSignals.isNotEmpty()) {
            // Persist this candle's chart-snapshot path onto every signal row resolved
            // here (see LiveEngineState.lastClosedCandlePriceLog KDoc / PricePathSnapshot.kt)
            // so History can redraw it later. Prefer the frozen copy captured for exactly
            // this candle; only fall back to whatever's currently live if it wasn't
            // captured for this candle (e.g. a gap-filled/resynced close where no live
            // ticks were ever observed for it) rather than risk mis-attributing another
            // candle's path. Computed once and reused for every signal below -- it's the
            // same candle's path regardless of which signal it's attached to.
            val pathSnapshot = synchronized(LiveEngineState.candlePriceLog) {
                if (LiveEngineState.lastClosedCandleOpenTime == candle.openTimeMillis) {
                    ArrayList(LiveEngineState.lastClosedCandlePriceLog)
                } else {
                    ArrayList(LiveEngineState.candlePriceLog)
                }
            }
            val encodedPath = encodePricePath(pathSnapshot)
            val settings = settingsRepo.settingsFlow.first()

            for (existing in activeSignals) {
                val signalDirection = Direction.valueOf(existing.direction)
                val (status, pnl) = CoreSignalEngine.evaluateResult(
                    database,
                    Signal(
                        signalId = existing.signalId, candleId = existing.candleId,
                        candleOpenTimeMillis = existing.candleOpenTimeMillis,
                        signalTimestampMillis = existing.signalTimestampMillis,
                        candleOpen = existing.candleOpen, signalPrice = existing.signalPrice,
                        direction = signalDirection, activeStrategyId = existing.activeStrategyId,
                        activeStrategyName = existing.activeStrategyName,
                        // Result evaluation only needs candleOpen/direction/checkpoint (for
                        // picking the financial model) -- see CoreSignalEngine.evaluateResult;
                        // regime is not re-derived here.
                        marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
                        strategyScore = existing.strategyScore, confidencePct = existing.confidencePct,
                        entryMovePct = existing.entryMovePct, checkpoint = Checkpoint.valueOf(existing.checkpoint)
                    ),
                    candle.close,
                    checkpointCWinUsd = settings.priceZoneWinUsd,
                    checkpointCLossUsd = settings.priceZoneLossUsd
                )
                signalRepo.markResult(existing.signalId, status, candle.close, pnl)
                signalRepo.savePriceSnapshot(existing.signalId, encodedPath)
            }
        }
        LiveEngineState.candlePhase.value = CandlePhase.CANDLE_CLOSED
        LiveEngineState.appState.value = AppState.WAITING
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        LiveEngineState.appState.value = when (state) {
            ConnectionState.CONNECTING -> AppState.CONNECTING
            ConnectionState.CONNECTED -> {
                if (resyncing.not() && lastProcessedOpenTime > 0) {
                    // Reconnected mid-session: resync any gap before trusting live data again.
                    serviceScope.launch { resyncGap() }
                }
                AppState.CONNECTED
            }
            ConnectionState.DISCONNECTED -> AppState.DISCONNECTED
            ConnectionState.ERROR -> AppState.ERROR
        }
    }

    private suspend fun resyncGap() {
        resyncing = true
        LiveEngineState.appState.value = AppState.SYNCING
        try {
            val start = lastProcessedOpenTime + 60_000L
            val end = System.currentTimeMillis()
            if (end > start) {
                val gapCandles = restClient.getKlines("BTCUSDT", "1m", start, end)
                for (c in gapCandles) {
                    if (c.openTimeMillis <= lastProcessedOpenTime) continue
                    marketDataStore.addClosed1m(c)
                    val events = aggregator.onClosed1mCandle(c)
                    processAggregatorEvents(events)
                    lastProcessedOpenTime = c.openTimeMillis
                }
            }
        } catch (e: Exception) {
            LiveEngineState.appState.value = AppState.ERROR
        } finally {
            resyncing = false
        }
    }

    override fun onError(message: String) {
        LiveEngineState.appState.value = AppState.ERROR
    }

    private fun buildForegroundNotification(text: String): Notification {
        notificationHelper.ensureChannels(soundEnabled = true, vibrationEnabled = true)
        val pendingIntent = androidx.core.app.TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@LiveMonitoringService, MainActivity::class.java))
            getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BTCUSDT Signal monitoring")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(FOREGROUND_ID, buildForegroundNotification(text))
    }

    companion object {
        private const val FOREGROUND_ID = 42
        private const val WARMUP_DAYS = 8L
    }
}

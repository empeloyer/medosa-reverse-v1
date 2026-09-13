package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Direction

/**
 * Configurable zone edges + PnL amounts + the 5-second confirmation toggle for the
 * price-zone signal rule (spec: "BTC 5-Minute Signal Logic — Exact Modification
 * Specification"). A RED signal fires when price moves +[redLowPct]%..+[redHighPct]%
 * away from candle open, but ONLY if the candle's own initial direction (minutes 0-2,
 * see [InitialDirection]) was GREEN; a GREEN signal fires on -[greenHighPct]%..
 * -[greenLowPct]%, only if the initial direction was RED (spec section 4 — the actual
 * signal is always OPPOSITE the initial direction). Win = +$5, loss = -$1 by default.
 * All seven values are user-editable in Settings ("Signal Zones" card,
 * [com.btcsignal.app.data.repository.SettingsRepository.setPriceZoneConfig]) and apply
 * to both Live and Backtest once Saved.
 */
data class PriceZoneConfig(
    val redLowPct: Double = 0.01,
    val redHighPct: Double = 0.04,
    val greenLowPct: Double = 0.01,
    val greenHighPct: Double = 0.04,
    val winUsd: Double = 5.0,
    val lossUsd: Double = -1.0,
    // Spec section 8: "5s Waiting" Settings toggle. OFF (default) fires the instant
    // price enters the opposite zone; ON delays every candidate fire by exactly 5
    // seconds and only actually fires if price is STILL inside the zone at that mark
    // (section 8.2) — see PriceZoneScanner.onTick's wait state machine below.
    val waitEnabled: Boolean = false
)

/** The candle's own color/direction as determined ONLY from price action during the
 *  first two minutes (spec section 2). GREEN/RED drive which zone is watched during
 *  the signal window (section 4); NONE means minute-2 close landed exactly on candle
 *  open — no valid direction — and permanently disables signal generation for that
 *  candle (spec section 10): "Do not attempt to guess the direction... do not generate
 *  a signal based only on the later price movement." */
enum class InitialDirection { GREEN, RED, NONE }

/**
 * Determines the initial candle direction (spec sections 2 & 10) by comparing the
 * price at the end of minute 2 (t+2min, i.e. the close of the candle's 2nd 1-minute
 * sub-candle) to the 5-minute candle's own open. This is the SINGLE shared
 * implementation (spec section 19) used by both Live (LiveMonitoringService, called the
 * instant minute 2's 1-minute candle closes) and Backtest (BacktestEngine, called from
 * the close of the bucket's 2nd historical 1-minute candle) so both always agree given
 * the same inputs.
 */
fun determineInitialDirection(candleOpen: Double, priceAtMinute2Close: Double): InitialDirection = when {
    priceAtMinute2Close > candleOpen -> InitialDirection.GREEN
    priceAtMinute2Close < candleOpen -> InitialDirection.RED
    else -> InitialDirection.NONE
}

/**
 * THE entire signal rule per the spec ("BTC 5-Minute Signal Logic — Exact Modification
 * Specification"):
 *
 *  1. Minutes 0-2 (`0:00` to `2:00`): no signal, ever — only used, via
 *     [setInitialDirection], to record whether the candle opened GREEN, RED, or with
 *     NO DIRECTION (section 2, 10).
 *  2. The signal window is `2:00 <= elapsed_time < 4:00` — a HARD, exclusive cutoff at
 *     `4:00` (section 3, 9). [arm] computes this window; [onTick] enforces it.
 *  3. The signal, when it fires, is always OPPOSITE the initial direction (section 4):
 *     initial GREEN -> watch only the RED zone; initial RED -> watch only the GREEN
 *     zone; initial NONE -> never watch anything, never fire (section 10).
 *  4. Exactly ONE signal — and one notification — per 5-minute candle, ever (section
 *     7). Once [fired] is true, every later tick this candle is ignored.
 *  5. "5s Waiting" (section 8): when [PriceZoneConfig.waitEnabled] is OFF, the very
 *     first qualifying tick fires immediately. When ON, the first qualifying tick only
 *     starts a 5-second confirmation timer (does NOT fire); the tick that lands at or
 *     after that 5-second mark decides the outcome purely from ITS OWN price: still
 *     inside the zone -> fire using that tick's price; outside -> cancel (not counted),
 *     and monitoring continues for a fresh entry (section 8.2). Because every tick is
 *     independently subject to the `< windowEnd` cutoff in step 2 above, a confirmation
 *     that would only be checked at/after `4:00` simply never gets a qualifying tick to
 *     check it on, so the wait can never smuggle a signal past the `4:00` cutoff
 *     (section 8, "must never extend the signal window beyond 4:00").
 *
 * One instance is owned per in-progress candle by the caller (LiveMonitoringService /
 * BacktestEngine) and must be replaced with a fresh, re-armed instance at the start of
 * every new 5-minute candle.
 */
class PriceZoneScanner(private val config: PriceZoneConfig = PriceZoneConfig()) {
    private var windowStartMillis: Long = -1
    private var windowEndMillis: Long = -1
    private var initialDirection: InitialDirection? = null

    /** Timestamp a candidate entry into the (single, opposite) target zone was first
     *  observed, while waiting out [PriceZoneConfig.waitEnabled]'s 5-second confirm
     *  window; -1 when there is no pending attempt. Irrelevant when waitEnabled is OFF. */
    private var pendingEntryMillis: Long = -1L

    var fired: Boolean = false
        private set

    val isArmed: Boolean get() = windowStartMillis >= 0

    data class FireResult(val direction: Direction, val movePct: Double, val price: Double)

    /** Called once, unconditionally, at the start of every 5-minute candle (candle
     *  open time, NOT the `2:00` signal-window start). The initial direction is not
     *  known yet at this point — call [setInitialDirection] separately once minute 2
     *  closes, before any in-window tick needs to be judged. */
    fun arm(candleOpenTimeMillis: Long) {
        windowStartMillis = candleOpenTimeMillis + 2 * 60_000L
        windowEndMillis = candleOpenTimeMillis + 4 * 60_000L
        fired = false
        initialDirection = null
        pendingEntryMillis = -1L
    }

    /** Must be called exactly once per candle, the instant minute 2 (the candle's 2nd
     *  1-minute sub-candle) closes (spec section 2: "must be finalized/available by the
     *  end of Minute 2"), before [onTick] is asked to judge any tick timestamped at or
     *  after `2:00`. See [determineInitialDirection] for how the direction itself is
     *  computed. */
    fun setInitialDirection(direction: InitialDirection) {
        initialDirection = direction
    }

    /** Returns a [FireResult] the instant the opposite-direction zone condition is
     *  satisfied (and, if configured, confirmed) during the armed `2:00 <= t < 4:00`
     *  window; null on every other tick — outside the window, before arming, before the
     *  initial direction is known, when the initial direction is NONE, after already
     *  firing this candle, or simply not currently relevant to the wait state machine. */
    fun onTick(movePct: Double, price: Double, tMillis: Long): FireResult? {
        if (!isArmed || fired) return null
        if (tMillis < windowStartMillis || tMillis >= windowEndMillis) return null // hard 4:00 cutoff, exclusive

        val initial = initialDirection ?: return null // not yet determined -- never guess (section 10)
        if (initial == InitialDirection.NONE) return null // no valid direction -- no signal opportunity at all

        // The signal is always the OPPOSITE color of the initial direction (section 4).
        val targetDirection = if (initial == InitialDirection.GREEN) Direction.RED else Direction.GREEN
        val inTargetZone = when (targetDirection) {
            Direction.RED -> movePct in config.redLowPct..config.redHighPct
            Direction.GREEN -> movePct in -config.greenHighPct..-config.greenLowPct
        }

        if (!config.waitEnabled) {
            if (!inTargetZone) return null
            fired = true
            return FireResult(targetDirection, movePct, price)
        }

        // 5-second confirmation state machine (section 8.2).
        if (pendingEntryMillis < 0) {
            if (inTargetZone) pendingEntryMillis = tMillis // first entry -- start the timer, do NOT fire yet
            return null
        }
        if (tMillis - pendingEntryMillis < 5_000L) return null // still waiting out the 5 seconds

        // This tick lands at/after the 5-second mark -- confirm or cancel using ITS OWN price.
        pendingEntryMillis = -1L
        if (!inTargetZone) return null // cancelled -- not counted; a later fresh entry may still start a new attempt
        fired = true
        return FireResult(targetDirection, movePct, price)
    }
}

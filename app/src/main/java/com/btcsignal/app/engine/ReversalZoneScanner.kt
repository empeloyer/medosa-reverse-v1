package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Direction

/**
 * Configuration for the Reversal-Zone secondary entry (user request, chat handoff
 * "دقیقه ۳ اسکن" — see ReversalZoneScanner KDoc below for the full rule). Not sourced
 * from strategies_parameters.json on purpose: this is a new execution-layer feature
 * layered on top of an already-locked primary signal, not a 32nd discovered strategy, so
 * it does not belong in — and must never be confused with — the Strategy Database that
 * "do not invent or modify strategies" governs.
 *
 * The GREEN and RED zone edges are independent (own chat request: configurable
 * separately in Settings as "Rev Green" / "Rev Red") rather than a single shared
 * inner/outer pair mirrored by sign — defaults happen to match on both sides (0.03% /
 * 0.10%, the same magnitude as the primary A/B entry range) but nothing enforces they
 * stay equal once a person edits one side in Settings.
 */
data class ReversalZoneConfig(
    /** Green zone's edge nearest candle open: the zone runs from -greenInnerPct down to
     *  -greenOuterPct (distance from candle open). Configurable in Settings ("Rev Green"). */
    val greenInnerPct: Double = 0.03,
    /** Green zone's edge farthest from candle open. */
    val greenOuterPct: Double = 0.10,
    /** Red zone's edge nearest candle open: the zone runs from +redInnerPct up to
     *  +redOuterPct. Configurable in Settings ("Rev Red"). */
    val redInnerPct: Double = 0.03,
    /** Red zone's edge farthest from candle open. */
    val redOuterPct: Double = 0.10,
    val waitMillis: Long = 10_000L,
    val winUsd: Double = 2.0,
    val lossUsd: Double = -1.0
)

/**
 * Reversal-Zone secondary entry.
 *
 * Once a primary signal has locked for a candle (Checkpoint A or B, by t+2min at the
 * latest), this scans the price continuously through the WHOLE of minute 4 of that same
 * candle (t+3min -> t+4min) for a second, independent entry opportunity on the side of
 * candle-open OPPOSITE the primary signal's own entry range — e.g. with the default
 * [ReversalZoneConfig], a primary GREEN signal (entered somewhere in +0%..+0.03%) watches
 * minute 4 for the price dipping down into -0.03%..-0.10% from candle open; a primary RED
 * signal watches for it rallying up into +0.03%..+0.10%. Both sides are configurable
 * independently in Settings ("Rev Green" / "Rev Red") — the figures above are just the
 * shipped defaults. The direction (color) of this second signal is always the SAME as
 * the primary's — a same-direction re-entry during a pullback, never a reversed bet —
 * confirmed explicitly by the user: "نتیجه درست برابر است با جهت تشخیص داده شده تا
 * دقیقه دوم" (the result is judged against the direction already determined by minute 2).
 * The window itself was moved from minute 2-3 to minute 3-4 per a later chat request
 * ("سیگنال ریورسال رو از فقط یک دقیقه تو ۳ بکن دقیقه ۳ و ۴") — everything else about the
 * rule (same-direction judgment, firing logic, financial model) is unchanged.
 *
 * Firing rule, exactly as specified over chat:
 *  1. The very first tick where price enters the zone (e.g. movePct <= -0.03% for a
 *     GREEN primary, and no more negative than -0.10%) arms a 10-second watch timer — it
 *     does NOT fire immediately.
 *  2. While that timer runs, any later tick that pushes movePct further into the zone
 *     than every tick seen since the touch (a new deeper extreme, e.g. -0.05% then
 *     -0.08%) fires immediately at that price — no need to wait out the rest of the 10s.
 *  3. If 10 seconds pass with no new extreme, fire using whatever price is current at
 *     that moment, as long as it is still within the zone.
 *  4. If price leaves the zone (recovers back past the inner edge, or overshoots past
 *     the outer edge) before either 2 or 3 fires, that touch is discarded and scanning
 *     re-arms — a fresh touch later in the same minute-4 window can still fire.
 *  5. At most ONE secondary signal is ever produced per candle. Firing is NEVER deferred
 *     to the end of minute 4 — it happens the instant rule 2 or 3 is satisfied, which can
 *     be well before t+4min (e.g. at t+3:30).
 *  6. If the window (t+3min -> t+4min) ends with nothing having fired, no secondary
 *     signal is produced for that candle.
 *
 * Win/loss for a fired secondary signal is +$2 / -$1 (see [ReversalZoneConfig]), NOT the
 * primary +$0.5 / -$1 financial model — see `CoreSignalEngine.evaluateResult`'s
 * `Checkpoint.C` branch. Outcome is still just "final close vs candle open", identical to
 * every other signal (`CoreSignalEngine.evaluateResult`); only which direction counts as
 * a win differs — and per the user, that direction is the primary's, i.e. this class
 * never invents a new prediction, it only times a second entry for the existing one.
 *
 * One instance is owned per in-progress candle by the caller (LiveMonitoringService /
 * BacktestEngine) and must be replaced with a fresh instance at the start of every new
 * 5-minute candle.
 */
class ReversalZoneScanner(
    private val config: ReversalZoneConfig = ReversalZoneConfig()
) {
    private var primaryDirection: Direction? = null
    private var windowStartMillis: Long = -1
    private var windowEndMillis: Long = -1
    private var touchTimeMillis: Long? = null
    private var worstMovePct: Double? = null

    var fired: Boolean = false
        private set

    val isArmed: Boolean get() = primaryDirection != null

    data class FireResult(val direction: Direction, val movePct: Double, val price: Double)

    /** Called once, the instant the primary A/B signal locks for this candle. The scan
     *  window is always t+3min -> t+4min of the candle regardless of whether the primary
     *  fired at Checkpoint A (t+1min) or B (t+2min) — moved from the original t+2min ->
     *  t+3min window per the user's later request ("دقیقه ۳ و ۴" instead of "دقیقه ۲ و
     *  ۳"). */
    fun arm(direction: Direction, candleOpenTimeMillis: Long) {
        primaryDirection = direction
        windowStartMillis = candleOpenTimeMillis + 3 * 60_000L
        windowEndMillis = candleOpenTimeMillis + 4 * 60_000L
    }

    /** Feed one price tick (raw price, its percentage move from candle open, and its
     *  timestamp). Returns a [FireResult] the moment this tick satisfies the firing rule
     *  above, else null. Safe to call unconditionally on every tick — ticks outside the
     *  armed window, before arming, or after already firing are simply ignored. [price]
     *  is carried straight through into [FireResult] so the caller never needs to re-read
     *  a possibly-since-moved "current price" from elsewhere at fire time. */
    fun onTick(movePct: Double, price: Double, timestampMillis: Long): FireResult? {
        val direction = primaryDirection ?: return null
        if (fired) return null
        if (timestampMillis < windowStartMillis || timestampMillis > windowEndMillis) return null

        val inZone = when (direction) {
            Direction.GREEN -> movePct in (-config.greenOuterPct)..(-config.greenInnerPct)
            Direction.RED -> movePct in config.redInnerPct..config.redOuterPct
        }

        val touchedAt = touchTimeMillis
        if (touchedAt == null) {
            if (inZone) {
                touchTimeMillis = timestampMillis
                worstMovePct = movePct
            }
            return null
        }

        if (!inZone) {
            // Left the zone on either side before firing — discard this touch; a fresh
            // one can still start later in the same window (rule 4).
            touchTimeMillis = null
            worstMovePct = null
            return null
        }

        val deeperThanBefore = when (direction) {
            Direction.GREEN -> movePct < (worstMovePct ?: movePct)
            Direction.RED -> movePct > (worstMovePct ?: movePct)
        }
        if (deeperThanBefore) {
            worstMovePct = movePct
            fired = true
            return FireResult(direction, movePct, price)
        }

        if (timestampMillis - touchedAt >= config.waitMillis) {
            fired = true
            return FireResult(direction, movePct, price)
        }
        return null
    }
}

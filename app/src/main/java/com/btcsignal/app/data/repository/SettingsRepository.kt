package com.btcsignal.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "btc_signal_settings")

/**
 * Blocking a strategy here (Strategies screen "Block" button, [SettingsRepository
 * .setStrategyBlocked]) excludes it from `CoreSignalEngine`'s vote entirely (see
 * `CoreSignalEngine.evaluateCheckpoint`'s `blockedStrategyIds` check) — it can never win
 * a primary Checkpoint A/B signal while blocked. Since a Reversal-Zone (Checkpoint C)
 * re-entry can only ever be armed off of an already-fired primary (see
 * `ReversalZoneScanner.arm` / `LiveMonitoringService.evaluateCheckpoint`), blocking a
 * strategy here also fully and permanently stops it from producing ANY Reversal-Zone
 * re-entry — not just from being shown/counted in the "Reversal-Zone Strategy Usage"
 * list, but from firing at all, on both Live and Backtest.
 *
 * These 21 ids are exactly the strategies that showed a net-negative Reversal-Zone PnL
 * in the user's own backtest/live run (chat handoff, 2026-09-12 — the "red" rows the
 * Reversal-Zone Strategy Usage list used to show before it was changed to list only
 * profitable origin strategies). Shipped as the DEFAULT for a fresh install of this
 * build only — i.e. this is what a person sees before ever touching the Strategies
 * screen, not a change to what's already stored for someone upgrading in place (a
 * pre-existing stored preference in DataStore always wins over this default, see
 * `settingsFlow` below). Any of the 21 can still be individually un-blocked from the
 * Strategies screen at any time; this is a starting point, not a hardcoded restriction.
 */
private val DEFAULT_BLOCKED_STRATEGY_IDS: Set<String> = setOf(
    "STRAT-001", "STRAT-002", "STRAT-007", "STRAT-009", "STRAT-017", "STRAT-025",
    "STRAT-N1B01", "STRAT-N1B02", "STRAT-N1B03",
    "STRAT-N2H02", "STRAT-N2H03",
    "STRAT-N4D02", "STRAT-N4D03",
    "STRAT-N5L01", "STRAT-N5L02",
    "STRAT-N6W01", "STRAT-N6W02",
    "STRAT-N7A01", "STRAT-N7A02", "STRAT-N7A03",
    "STRAT-N9S01"
)

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val blockedStrategyIds: Set<String> = DEFAULT_BLOCKED_STRATEGY_IDS,
    // Reversal-Zone (minute-3-to-4, Checkpoint C) zone edges, user-editable in Settings
    // as "Rev Green" / "Rev Red" (chat request). Defaults match ReversalZoneConfig's own
    // shipped defaults (-0.03%..-0.10% for Green, +0.03%..+0.10% for Red) so a person who
    // never opens this Settings section gets identical behavior to before this was
    // configurable. Applied to BOTH Live (LiveMonitoringService) and Backtest
    // (BacktestEngine.run) once saved.
    val revGreenInnerPct: Double = 0.03,
    val revGreenOuterPct: Double = 0.10,
    val revRedInnerPct: Double = 0.03,
    val revRedOuterPct: Double = 0.10,
    // THE signal rule ("BTC 5-Minute Signal Logic" spec): the candle's initial direction
    // is determined ONLY from price action during minutes 0-2 (never during the signal
    // window itself), then the actual signal is always the OPPOSITE color, and can only
    // fire during the `2:00 <= t < 4:00` window (`4:00` is a hard, exclusive cutoff) --
    // an initial GREEN candle looks only for a RED signal when price moves
    // +priceZoneRedLowPct%..+priceZoneRedHighPct% away from candle open; an initial RED
    // candle looks only for GREEN on -priceZoneGreenHighPct%..-priceZoneGreenLowPct%. A
    // candle with no determinable initial direction gets no signal opportunity at all.
    // No signal (or notification) is ever produced outside the window, and at most one
    // per candle. See engine/PriceZoneScanner.kt for the full rule and
    // CoreSignalEngine.evaluateResult for how winUsd/lossUsd below are applied.
    // User-editable in Settings ("Signal Zones" card) and applied to BOTH Live
    // (LiveMonitoringService) and Backtest (BacktestEngine.run) once saved.
    val priceZoneRedLowPct: Double = 0.01,
    val priceZoneRedHighPct: Double = 0.04,
    val priceZoneGreenLowPct: Double = 0.01,
    val priceZoneGreenHighPct: Double = 0.04,
    val priceZoneWinUsd: Double = 5.0,
    val priceZoneLossUsd: Double = -1.0,
    // "5s Waiting" (spec section 8): OFF fires the instant price enters the opposite
    // zone; ON only fires if price is STILL inside the zone exactly 5 seconds after
    // first entering it (see engine/PriceZoneScanner.kt). Applied to both Live
    // (LiveMonitoringService) and Backtest (BacktestEngine.run) once saved, same as
    // every other price-zone field above.
    val priceZoneWaitEnabled: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val BLOCKED_STRATEGY_IDS = stringSetPreferencesKey("blocked_strategy_ids")
        val REV_GREEN_INNER_PCT = doublePreferencesKey("rev_green_inner_pct")
        val REV_GREEN_OUTER_PCT = doublePreferencesKey("rev_green_outer_pct")
        val REV_RED_INNER_PCT = doublePreferencesKey("rev_red_inner_pct")
        val REV_RED_OUTER_PCT = doublePreferencesKey("rev_red_outer_pct")
        val PRICE_ZONE_RED_LOW_PCT = doublePreferencesKey("price_zone_red_low_pct")
        val PRICE_ZONE_RED_HIGH_PCT = doublePreferencesKey("price_zone_red_high_pct")
        val PRICE_ZONE_GREEN_LOW_PCT = doublePreferencesKey("price_zone_green_low_pct")
        val PRICE_ZONE_GREEN_HIGH_PCT = doublePreferencesKey("price_zone_green_high_pct")
        val PRICE_ZONE_WIN_USD = doublePreferencesKey("price_zone_win_usd")
        val PRICE_ZONE_LOSS_USD = doublePreferencesKey("price_zone_loss_usd")
        val PRICE_ZONE_WAIT_ENABLED = booleanPreferencesKey("price_zone_wait_enabled")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            soundEnabled = prefs[Keys.SOUND] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            blockedStrategyIds = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: DEFAULT_BLOCKED_STRATEGY_IDS,
            revGreenInnerPct = prefs[Keys.REV_GREEN_INNER_PCT] ?: 0.03,
            revGreenOuterPct = prefs[Keys.REV_GREEN_OUTER_PCT] ?: 0.10,
            revRedInnerPct = prefs[Keys.REV_RED_INNER_PCT] ?: 0.03,
            revRedOuterPct = prefs[Keys.REV_RED_OUTER_PCT] ?: 0.10,
            priceZoneRedLowPct = prefs[Keys.PRICE_ZONE_RED_LOW_PCT] ?: 0.01,
            priceZoneRedHighPct = prefs[Keys.PRICE_ZONE_RED_HIGH_PCT] ?: 0.04,
            priceZoneGreenLowPct = prefs[Keys.PRICE_ZONE_GREEN_LOW_PCT] ?: 0.01,
            priceZoneGreenHighPct = prefs[Keys.PRICE_ZONE_GREEN_HIGH_PCT] ?: 0.04,
            priceZoneWinUsd = prefs[Keys.PRICE_ZONE_WIN_USD] ?: 5.0,
            priceZoneLossUsd = prefs[Keys.PRICE_ZONE_LOSS_USD] ?: -1.0,
            priceZoneWaitEnabled = prefs[Keys.PRICE_ZONE_WAIT_ENABLED] ?: false
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    /** Toggles a strategy's block state (Strategies screen "Block" button). A blocked
     *  strategy is skipped by CoreSignalEngine's vote entirely, on BOTH Live
     *  (LiveMonitoringService) and Backtest (BacktestState/BacktestEngine) -- it cannot
     *  produce a new primary signal, and therefore also cannot produce a Reversal-Zone
     *  re-entry (Checkpoint C), which only ever arms off of an already-fired primary --
     *  until unblocked again. Falls back to [DEFAULT_BLOCKED_STRATEGY_IDS], not
     *  emptySet(), when no block key has ever been written yet, so un-blocking a single
     *  strategy on a fresh install (where the *effective* set is already the 21
     *  defaults) toggles only that one id rather than silently persisting an explicit
     *  empty set and un-blocking all 21 at once. */
    suspend fun setStrategyBlocked(strategyId: String, blocked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: DEFAULT_BLOCKED_STRATEGY_IDS
            prefs[Keys.BLOCKED_STRATEGY_IDS] = if (blocked) current + strategyId else current - strategyId
        }
    }

    /** Persists all four Reversal-Zone range edges at once (Settings screen "Save"
     *  button, chat request) -- one atomic write rather than four, so a Live candle
     *  boundary or a Backtest run reading settingsFlow mid-save can never observe a
     *  half-updated combination (e.g. a new green-inner paired with the old green-outer). */
    suspend fun setReversalZoneRanges(
        greenInnerPct: Double,
        greenOuterPct: Double,
        redInnerPct: Double,
        redOuterPct: Double
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REV_GREEN_INNER_PCT] = greenInnerPct
            prefs[Keys.REV_GREEN_OUTER_PCT] = greenOuterPct
            prefs[Keys.REV_RED_INNER_PCT] = redInnerPct
            prefs[Keys.REV_RED_OUTER_PCT] = redOuterPct
        }
    }

    /** Persists the entire price-zone signal rule at once (Settings screen "Save"
     *  button, chat request) -- one atomic write, so a Live candle boundary or a
     *  Backtest run reading settingsFlow mid-save can never observe a half-updated
     *  combination (e.g. a new red-zone edge paired with the old win/loss amounts). See
     *  engine/PriceZoneScanner.kt for how these seven values are used. */
    suspend fun setPriceZoneConfig(
        redLowPct: Double,
        redHighPct: Double,
        greenLowPct: Double,
        greenHighPct: Double,
        winUsd: Double,
        lossUsd: Double,
        waitEnabled: Boolean
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PRICE_ZONE_RED_LOW_PCT] = redLowPct
            prefs[Keys.PRICE_ZONE_RED_HIGH_PCT] = redHighPct
            prefs[Keys.PRICE_ZONE_GREEN_LOW_PCT] = greenLowPct
            prefs[Keys.PRICE_ZONE_GREEN_HIGH_PCT] = greenHighPct
            prefs[Keys.PRICE_ZONE_WIN_USD] = winUsd
            prefs[Keys.PRICE_ZONE_LOSS_USD] = lossUsd
            prefs[Keys.PRICE_ZONE_WAIT_ENABLED] = waitEnabled
        }
    }
}

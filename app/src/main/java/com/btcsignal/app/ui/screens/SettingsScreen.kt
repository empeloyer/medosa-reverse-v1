package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.GothicSwitch
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabSettingsColor
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val notificationHelper = remember { AppContainer.notificationHelper(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GothicPageHeader(title = "Settings", accent = TabSettingsColor)

        SectionCard("Notifications") {
            SettingRow("Notifications", settings.notificationsEnabled) {
                scope.launch { settingsRepo.setNotificationsEnabled(it) }
            }
            SettingRow("Sound", settings.soundEnabled) {
                scope.launch { settingsRepo.setSoundEnabled(it) }
            }
            SettingRow("Vibration", settings.vibrationEnabled) {
                scope.launch { settingsRepo.setVibrationEnabled(it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GothicButton(
                    text = "Test Notification",
                    role = GothicRole.PURPLE,
                    filled = false,
                    compact = true,
                    onClick = {
                        notificationHelper.sendTestNotification(settings.soundEnabled, settings.vibrationEnabled)
                    }
                )
                GothicButton(
                    text = "Test Sound",
                    role = GothicRole.PURPLE,
                    filled = false,
                    compact = true,
                    onClick = {
                        notificationHelper.sendTestNotification(soundEnabled = true, vibrationEnabled = settings.vibrationEnabled)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SignalZoneCard(settingsRepo = settingsRepo, scope = scope)
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        GothicSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * THE entire signal rule, editable ("BTC 5-Minute Signal Logic" spec, replacing the
 * previous "Rev Green"/"Rev Red" Reversal-Zone editor along with the 27-strategy primary
 * system it used to sit alongside): during minutes 0-2 the app only determines the
 * candle's own initial direction (no signal); then, ONLY between `2:00` and `4:00`
 * (`4:00` itself is excluded), it watches for the OPPOSITE color — an initially GREEN
 * candle fires RED the first time price moves into the Red Zone (+Low%..+High% from
 * candle open); an initially RED candle fires GREEN on the Green Zone (-Low%..-High%). A
 * candle with no determinable initial direction gets no signal at all. Shipped defaults
 * +0.01%..+0.04% for both zones, win +$5 / loss -$1, 5s Waiting OFF — all seven values
 * editable here, applied to both Live and Backtest once Saved.
 *
 * Deliberately NOT bound live to `settingsRepo.settingsFlow` the way the switches above
 * are: these are typed numeric fields, so every keystroke would otherwise round-trip
 * through DataStore before the next character could be typed. Local text state is seeded
 * once from the persisted values (LaunchedEffect(Unit) below) and only written back to
 * SettingsRepository when the user taps Save.
 */
@Composable
private fun SignalZoneCard(
    settingsRepo: com.btcsignal.app.data.repository.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var loaded by remember { mutableStateOf(false) }
    var redLowText by remember { mutableStateOf("0.01") }
    var redHighText by remember { mutableStateOf("0.04") }
    var greenLowText by remember { mutableStateOf("0.01") }
    var greenHighText by remember { mutableStateOf("0.04") }
    var winText by remember { mutableStateOf("5") }
    var lossText by remember { mutableStateOf("-1") }
    var waitEnabled by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val initial = settingsRepo.settingsFlow.first()
        redLowText = formatPct(initial.priceZoneRedLowPct)
        redHighText = formatPct(initial.priceZoneRedHighPct)
        greenLowText = formatPct(initial.priceZoneGreenLowPct)
        greenHighText = formatPct(initial.priceZoneGreenHighPct)
        winText = formatPct(initial.priceZoneWinUsd)
        lossText = formatPct(initial.priceZoneLossUsd)
        waitEnabled = initial.priceZoneWaitEnabled
        loaded = true
    }

    fun onEdited() {
        justSaved = false
        errorText = null
    }

    SectionCard("Signal Zones (Minute 3\u20134)") {
        Text(
            "The ENTIRE signal rule: minutes 0-2 only determine the candle's initial " +
                "direction (no signal). From 2:00 up to (not including) 4:00, the app " +
                "watches for the OPPOSITE color only \u2014 an initially GREEN candle fires " +
                "RED on the Red Zone, an initially RED candle fires GREEN on the Green " +
                "Zone. At most one signal per candle, none outside 2:00\u20134:00. Applies " +
                "to both Live and Backtest once saved.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(12.dp))

        if (!loaded) {
            Text("Loading\u2026", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            Text("Red Zone", style = MaterialTheme.typography.titleMedium, color = RedSignal)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PctField(
                    label = "Low %", value = redLowText, modifier = Modifier.weight(1f),
                    onValueChange = { redLowText = it; onEdited() }
                )
                PctField(
                    label = "High %", value = redHighText, modifier = Modifier.weight(1f),
                    onValueChange = { redHighText = it; onEdited() }
                )
            }
            val redLowPreview = redLowText.toDoubleOrNull()
            val redHighPreview = redHighText.toDoubleOrNull()
            if (redLowPreview != null && redHighPreview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zone: +${formatPct(redLowPreview)}% to +${formatPct(redHighPreview)}% from candle open",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Green Zone", style = MaterialTheme.typography.titleMedium, color = GreenSignal)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PctField(
                    label = "Low %", value = greenLowText, modifier = Modifier.weight(1f),
                    onValueChange = { greenLowText = it; onEdited() }
                )
                PctField(
                    label = "High %", value = greenHighText, modifier = Modifier.weight(1f),
                    onValueChange = { greenHighText = it; onEdited() }
                )
            }
            val greenLowPreview = greenLowText.toDoubleOrNull()
            val greenHighPreview = greenHighText.toDoubleOrNull()
            if (greenLowPreview != null && greenHighPreview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zone: -${formatPct(greenLowPreview)}% to -${formatPct(greenHighPreview)}% from candle open",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Win / Loss ($)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PctField(
                    label = "Win $", value = winText, modifier = Modifier.weight(1f),
                    onValueChange = { winText = it; onEdited() }
                )
                PctField(
                    label = "Loss $", value = lossText, modifier = Modifier.weight(1f),
                    onValueChange = { lossText = it; onEdited() }
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("5s Waiting", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When ON, a candidate signal only fires if price is still inside " +
                            "the zone exactly 5 seconds after first entering it.",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary
                    )
                }
                GothicSwitch(checked = waitEnabled, onCheckedChange = { waitEnabled = it; onEdited() })
            }

            errorText?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = RedSignal)
            }
            if (justSaved && errorText == null) {
                Spacer(Modifier.height(10.dp))
                Text("Saved \u2713", style = MaterialTheme.typography.bodySmall, color = GreenSignal)
            }

            Spacer(Modifier.height(12.dp))
            GothicButton(
                text = "Save",
                role = GothicRole.CYAN,
                onClick = {
                    val redLow = redLowText.toDoubleOrNull()
                    val redHigh = redHighText.toDoubleOrNull()
                    val greenLow = greenLowText.toDoubleOrNull()
                    val greenHigh = greenHighText.toDoubleOrNull()
                    val win = winText.toDoubleOrNull()
                    val loss = lossText.toDoubleOrNull()
                    if (redLow == null || redHigh == null || greenLow == null || greenHigh == null || win == null || loss == null) {
                        errorText = "Enter a valid number in every field."
                    } else if (redLow <= 0 || redHigh <= 0 || greenLow <= 0 || greenHigh <= 0) {
                        errorText = "Zone edges must be greater than 0."
                    } else if (redLow >= redHigh) {
                        errorText = "Red Zone: Low must be less than High."
                    } else if (greenLow >= greenHigh) {
                        errorText = "Green Zone: Low must be less than High."
                    } else if (win <= 0) {
                        errorText = "Win must be greater than 0."
                    } else if (loss >= 0) {
                        errorText = "Loss must be less than 0."
                    } else {
                        errorText = null
                        scope.launch {
                            settingsRepo.setPriceZoneConfig(
                                redLowPct = redLow,
                                redHighPct = redHigh,
                                greenLowPct = greenLow,
                                greenHighPct = greenHigh,
                                winUsd = win,
                                lossUsd = loss,
                                waitEnabled = waitEnabled
                            )
                            justSaved = true
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PctField(label: String, value: String, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

/** Trims a trailing ".0" (e.g. "0.1" instead of "0.10" round-tripping oddly) while still
 *  showing genuinely fractional values as typed -- purely a display nicety for the
 *  seeded/preview text, never used for the persisted or compared numeric value. */
private fun formatPct(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

package com.theo.distractionblocker.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theo.distractionblocker.core.prefs.PendingChange
import com.theo.distractionblocker.core.prefs.SettingKey
import com.theo.distractionblocker.core.prefs.SettingsRepository
import com.theo.distractionblocker.insta.InstaWebViewActivity
import com.theo.distractionblocker.xiaomi.SystemScreens

// TopAppBar est encore marque experimental dans Material3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: ConfigViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Les messages du ViewModel (changement differe, annulation) remontent ici.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Distraction Blocker") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceStatusSection(state)
            BlockingSection(state, viewModel)
            AntiCheatSection(state, viewModel)
            InstagramSection()
            XiaomiSection()
            RestrictedSettingsSection()
        }
    }
}

// --- Statut du service ------------------------------------------------------

@Composable
private fun ServiceStatusSection(state: ConfigUiState) {
    val context = LocalContext.current
    SectionCard(title = "Service de blocage") {
        Text(
            text = if (state.accessibilityEnabled) {
                "Actif : les blocages s'appliquent."
            } else {
                "Inactif : aucun blocage ne fonctionne. Rien ne marchera tant que " +
                    "le service d'accessibilité n'est pas activé."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.accessibilityEnabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Button(
            onClick = {
                if (!SystemScreens.openAccessibilitySettings(context)) {
                    context.toast("Écran d'accessibilité introuvable")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir les réglages d'accessibilité")
        }
    }
}

// --- Blocages ---------------------------------------------------------------

@Composable
private fun BlockingSection(state: ConfigUiState, viewModel: ConfigViewModel) {
    SectionCard(title = "Blocages") {
        SwitchRow(
            label = "Spotlight + Stories (Snapchat)",
            description = "Renvoie vers un écran autorisé dès que l'onglet Spotlight " +
                "ou Stories est détecté. Les écrans visés sont définis dans " +
                "detection_config.json.",
            checked = state.settings.blockSnapSpotlight,
            onCheckedChange = { viewModel.setSwitch(SettingKey.BLOCK_SNAP_SPOTLIGHT, it) },
        )
        HorizontalDivider()
        SwitchRow(
            label = "Quota TikTok",
            description = "Éjecte de TikTok quand le temps du jour est épuisé.",
            checked = state.settings.tiktokQuotaEnabled,
            onCheckedChange = { viewModel.setSwitch(SettingKey.TIKTOK_QUOTA_ENABLED, it) },
        )
        QuotaField(state, viewModel)
        UsageRow(state, viewModel)
        HorizontalDivider()
        SwitchRow(
            label = "YouTube Shorts",
            description = "Sort du lecteur Shorts dès qu'il est détecté. " +
                "Le reste de YouTube n'est pas touché.",
            checked = state.settings.blockYoutubeShorts,
            onCheckedChange = { viewModel.setSwitch(SettingKey.BLOCK_YOUTUBE_SHORTS, it) },
        )
        HorizontalDivider()
        SwitchRow(
            label = "Instagram officiel",
            description = "Bloque totalement l'app Instagram, au profit de la WebView.",
            checked = state.settings.blockInstagramOfficial,
            onCheckedChange = { viewModel.setSwitch(SettingKey.BLOCK_INSTAGRAM_OFFICIAL, it) },
        )
    }
}

@Composable
private fun QuotaField(state: ConfigUiState, viewModel: ConfigViewModel) {
    // Etat local du champ : on ne veut pas creer un changement (et donc, en mode
    // anti-triche, une attente de 10 minutes) a chaque frappe au clavier.
    var text by remember(state.settings.tiktokQuotaMinutes) {
        mutableStateOf(state.settings.tiktokQuotaMinutes.toString())
    }
    val parsed = text.toIntOrNull()
    val valid = parsed != null &&
        parsed in SettingsRepository.MIN_QUOTA_MINUTES..SettingsRepository.MAX_QUOTA_MINUTES
    val changed = valid && parsed != state.settings.tiktokQuotaMinutes

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter(Char::isDigit).take(3) },
            label = { Text("Quota (minutes)") },
            isError = text.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = { parsed?.let(viewModel::setQuotaMinutes) },
            enabled = changed,
        ) {
            Text("Enregistrer")
        }
    }
}

@Composable
private fun UsageRow(state: ConfigUiState, viewModel: ConfigViewModel) {
    var confirming by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Utilisé aujourd'hui", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatDuration(state.usageMs) +
                    " / " + formatDuration(state.settings.tiktokQuotaMs),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        OutlinedButton(onClick = { confirming = true }) { Text("Reset") }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remettre le compteur à zéro ?") },
            text = {
                Text(
                    "Le temps TikTok déjà consommé aujourd'hui sera effacé. " +
                        if (state.settings.antiCheatEnabled) {
                            "Le mode anti-triche est actif : la remise à zéro ne " +
                                "prendra effet que dans " +
                                SettingsRepository.antiCheatDelayLabel + "."
                        } else {
                            "C'est immédiat."
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTodayUsage()
                    confirming = false
                }) { Text("Remettre à zéro") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Annuler") }
            },
        )
    }
}

// --- Anti-triche ------------------------------------------------------------

@Composable
private fun AntiCheatSection(state: ConfigUiState, viewModel: ConfigViewModel) {
    SectionCard(title = "Mode anti-triche") {
        SwitchRow(
            label = "Délai avant application",
            description = "Quand il est actif, seuls les assouplissements attendent " +
                SettingsRepository.antiCheatDelayLabel + " : désactiver un blocage, " +
                "rallonger le quota, remettre le compteur à zéro, ou désactiver ce " +
                "mode. Renforcer une limite s'applique tout de suite.",
            checked = state.settings.antiCheatEnabled,
            onCheckedChange = { viewModel.setSwitch(SettingKey.ANTI_CHEAT_ENABLED, it) },
        )
        Text(
            text = "À savoir : ce mode ne ralentit que les réglages de cette app. " +
                "Désactiver le service d'accessibilité ou désinstaller l'app reste " +
                "instantané. Annuler une demande en attente est immédiat aussi : " +
                "cela ne fait que conserver l'état actuel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.pendingChanges.isNotEmpty()) {
            HorizontalDivider()
            Text("En attente", style = MaterialTheme.typography.titleSmall)
            state.pendingChanges.forEach { change ->
                PendingChangeRow(change, state.nowMs, viewModel)
            }
        }
    }
}

@Composable
private fun PendingChangeRow(
    change: PendingChange,
    nowMs: Long,
    viewModel: ConfigViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${change.key.label} -> ${change.valueLabel}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatCountdown(change.remainingMs(nowMs)),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Annuler est immédiat : ca ne fait que garder l'etat actuel.
        TextButton(onClick = { viewModel.cancelPending(change.key) }) { Text("Annuler") }
    }
}

// --- Instagram --------------------------------------------------------------

@Composable
private fun InstagramSection() {
    val context = LocalContext.current
    SectionCard(title = "Instagram") {
        Text(
            text = "Version WebView, sans Reels ni publicités. Les DM et les posts " +
                "des comptes suivis restent accessibles.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                context.startActivity(Intent(context, InstaWebViewActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir Instagram")
        }
    }
}

// --- Xiaomi -----------------------------------------------------------------

@Composable
private fun XiaomiSection() {
    val context = LocalContext.current
    SectionCard(title = "Configuration Xiaomi") {
        Text(
            text = "HyperOS tue les services en arrière-plan plus agressivement " +
                "qu'Android de base. Ces trois réglages évitent que les blocages " +
                "s'arrêtent tout seuls.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SetupStep(
            title = "1. Démarrage automatique",
            detail = "Sans ça, les blocages ne repartent pas après un redémarrage " +
                "du téléphone. Cherche Distraction Blocker et active l'interrupteur.",
            buttonLabel = "Ouvrir",
            onClick = {
                if (!SystemScreens.openAutostartSettings(context)) {
                    context.toast(
                        "Écran introuvable. À la main : Sécurité > Autorisations > " +
                            "Démarrage automatique",
                    )
                }
            },
        )
        SetupStep(
            title = "2. Économiseur de batterie : aucune restriction",
            detail = "C'est le réglage qui compte le plus. Choisis " +
                "\"Aucune restriction\" pour cette app.",
            buttonLabel = "Ouvrir",
            onClick = {
                if (!SystemScreens.openBatterySaverSettings(context)) {
                    context.toast(
                        "Écran introuvable. À la main : Batterie > Économiseur de " +
                            "batterie de l'application",
                    )
                }
            },
        )
        SetupStep(
            title = "3. Verrouiller dans le multitâche",
            detail = "Aucun réglage ne permet de le faire à distance : ouvre le " +
                "multitâche (les récents), appuie longuement sur la vignette de " +
                "Distraction Blocker et touche le cadenas. Ça empêche le " +
                "\"tout fermer\" de tuer l'app.",
            buttonLabel = null,
            onClick = {},
        )
    }
}

// --- Paramètres restreints --------------------------------------------------

@Composable
private fun RestrictedSettingsSection() {
    val context = LocalContext.current
    SectionCard(title = "Paramètres restreints (Android 13+)") {
        Text(
            text = "Une app installée hors Play Store n'a pas le droit d'être " +
                "activée dans l'accessibilité tant que la restriction n'est pas " +
                "levée. Si le bouton est grisé dans les réglages d'accessibilité :",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "1. Ouvre la fiche de l'application (bouton ci-dessous)\n" +
                "2. Menu ... en haut à droite\n" +
                "3. \"Autoriser les paramètres restreints\"\n" +
                "4. Reviens dans les réglages d'accessibilité : le service est " +
                "maintenant activable",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (!SystemScreens.openAppDetails(context)) {
                    context.toast("Fiche de l'application introuvable")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir la fiche de l'application")
        }
    }
}

// --- Briques reutilisables --------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SetupStep(
    title: String,
    detail: String,
    buttonLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (buttonLabel != null) {
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

// --- Formatage --------------------------------------------------------------

/** "0 min", "12 min", "1 h 05". */
private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h ${minutes.toString().padStart(2, '0')}" else "$minutes min"
}

/**
 * Compte a rebours : mm:ss, ou h:mm:ss au-dela d'une heure. Sans le palier des
 * heures, un delai d'une heure s'afficherait "60:00", qui se lit mal.
 */
private fun formatCountdown(ms: Long): String {
    val totalSeconds = ms / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

private fun android.content.Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

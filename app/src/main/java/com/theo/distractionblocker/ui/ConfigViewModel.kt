package com.theo.distractionblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theo.distractionblocker.blocking.AccessibilityStatus
import com.theo.distractionblocker.core.prefs.PendingChange
import com.theo.distractionblocker.core.prefs.SettingKey
import com.theo.distractionblocker.core.prefs.Settings
import com.theo.distractionblocker.core.prefs.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Tout ce dont l'ecran de config a besoin pour se dessiner. */
data class ConfigUiState(
    val settings: Settings,
    val pendingChanges: List<PendingChange>,
    val usageMs: Long,
    val accessibilityEnabled: Boolean,
    /** Horloge rafraichie chaque seconde, pour les comptes a rebours. */
    val nowMs: Long,
) {
    companion object {
        val INITIAL = ConfigUiState(
            settings = Settings.DEFAULT,
            pendingChanges = emptyList(),
            usageMs = 0L,
            accessibilityEnabled = false,
            nowMs = 0L,
        )
    }
}

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository.get(application)

    /** Messages ponctuels pour la Snackbar (ex : "differe de 10 minutes"). */
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = noticeChannel.receiveAsFlow()

    /**
     * Battement d'une seconde. Il sert a trois choses : faire vivre les comptes
     * a rebours, relire l'etat du service d'accessibilité (qui peut changer
     * hors de l'app, sans notification), et faire tomber les changements
     * differes arrives a echeance.
     */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }

    val state: StateFlow<ConfigUiState> = combine(
        repo.settings,
        repo.pendingChanges,
        repo.todayUsageMs,
        ticker,
    ) { settings, pending, usage, now ->
        ConfigUiState(
            settings = settings,
            pendingChanges = pending,
            usageMs = usage,
            accessibilityEnabled = AccessibilityStatus.isEnabled(getApplication()),
            nowMs = now,
        )
    }.stateIn(
        scope = viewModelScope,
        // WhileSubscribed : on arrete le battement quand l'ecran n'est plus
        // visible, avec 5 s de grace pour ne pas tout relancer a la rotation.
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ConfigUiState.INITIAL,
    )

    init {
        // Le service de blocage fait la meme chose de son cote ; ici, c'est pour
        // que l'ecran soit a jour meme si le service n'est pas actif.
        viewModelScope.launch {
            while (true) {
                repo.applyDuePendingChanges()
                delay(1_000L)
            }
        }
    }

    fun setSwitch(key: SettingKey, value: Boolean) = request(key, value)

    fun setQuotaMinutes(minutes: Int) = request(
        SettingKey.TIKTOK_QUOTA_MINUTES,
        minutes.coerceIn(SettingsRepository.MIN_QUOTA_MINUTES, SettingsRepository.MAX_QUOTA_MINUTES),
    )

    fun resetTodayUsage() = request(SettingKey.RESET_TIKTOK_USAGE, true)

    fun cancelPending(key: SettingKey) {
        viewModelScope.launch {
            repo.cancelPending(key)
            noticeChannel.send("Demande annulée")
        }
    }

    /**
     * Toute modification passe par ici. Si l'anti-triche est actif, le depot
     * renvoie une echeance au lieu d'appliquer : on le dit a l'utilisateur,
     * sinon le réglage semblerait simplement ignore.
     */
    private fun request(key: SettingKey, value: Any) {
        viewModelScope.launch {
            val scheduledAt = repo.requestChange(key, value)
            if (scheduledAt != null) {
                noticeChannel.send(
                    "Anti-triche actif : appliqué dans " +
                        "${SettingsRepository.ANTI_CHEAT_DELAY_MINUTES} minutes",
                )
            }
        }
    }
}

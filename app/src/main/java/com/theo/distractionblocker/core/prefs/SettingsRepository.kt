package com.theo.distractionblocker.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.theo.distractionblocker.core.time.DayKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// Cree (une seule fois par process) le DataStore "settings.preferences_pb".
// C'est une extension sur Context : le delegue gere le singleton pour nous.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Source de verite unique pour les reglages, le compteur TikTok du jour et les
 * changements en attente du mode anti-triche.
 *
 * Regle du mode anti-triche : quand il est actif, TOUT changement de reglage
 * (y compris desactiver l'anti-triche lui-meme) est mis en attente pendant
 * [ANTI_CHEAT_DELAY_MINUTES] minutes au lieu d'etre applique. Annuler une
 * demande en attente, en revanche, est immediat : ca ne fait que conserver
 * l'etat deja applique, donc ca ne peut rien relacher.
 */
class SettingsRepository private constructor(private val context: Context) {

    // --- Cles DataStore ----------------------------------------------------
    private val keySnap = booleanPreferencesKey(SettingKey.BLOCK_SNAP_SPOTLIGHT.storageName)
    private val keyQuotaEnabled = booleanPreferencesKey(SettingKey.TIKTOK_QUOTA_ENABLED.storageName)
    private val keyQuotaMinutes = intPreferencesKey(SettingKey.TIKTOK_QUOTA_MINUTES.storageName)
    private val keyInsta = booleanPreferencesKey(SettingKey.BLOCK_INSTAGRAM_OFFICIAL.storageName)
    private val keyAntiCheat = booleanPreferencesKey(SettingKey.ANTI_CHEAT_ENABLED.storageName)
    private val keyPending = stringPreferencesKey("pending_changes")
    private val keyUsageDay = stringPreferencesKey("tiktok_usage_day")
    private val keyUsageMs = longPreferencesKey("tiktok_usage_ms")

    // --- Lectures ----------------------------------------------------------

    /** Reglages effectivement appliques (hors changements encore en attente). */
    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    /** Changements demandes mais pas encore murs, tries par echeance. */
    val pendingChanges: Flow<List<PendingChange>> =
        context.dataStore.data.map { prefs -> prefs.readPending().sortedBy { it.applyAtEpochMs } }

    /**
     * Temps TikTok consomme aujourd'hui, en ms. Si la date stockee n'est pas
     * celle du jour, on renvoie 0 : c'est le "reset a minuit".
     */
    val todayUsageMs: Flow<Long> = context.dataStore.data.map { prefs ->
        if (prefs[keyUsageDay] == DayKey.today()) prefs[keyUsageMs] ?: 0L else 0L
    }

    suspend fun currentSettings(): Settings = settings.first()

    suspend fun currentUsageMs(): Long = todayUsageMs.first()

    // --- Ecritures ---------------------------------------------------------

    /**
     * Demande un changement de reglage. Applique tout de suite si l'anti-triche
     * est inactif, sinon mis en attente. Renvoie l'echeance (epoch ms) si le
     * changement a ete differe, null s'il est deja applique.
     */
    suspend fun requestChange(
        key: SettingKey,
        value: Any,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        var scheduledAt: Long? = null
        context.dataStore.edit { prefs ->
            if (prefs[keyAntiCheat] == true) {
                val applyAt = nowMs + ANTI_CHEAT_DELAY_MINUTES * 60_000L
                val pending = prefs.readPendingJson()
                pending.put(
                    key.storageName,
                    JSONObject().put(JSON_VALUE, value).put(JSON_APPLY_AT, applyAt),
                )
                prefs[keyPending] = pending.toString()
                scheduledAt = applyAt
            } else {
                prefs.applyValue(key, value)
            }
        }
        return scheduledAt
    }

    /** Abandonne une demande en attente. Immediat, par conception. */
    suspend fun cancelPending(key: SettingKey) {
        context.dataStore.edit { prefs ->
            val pending = prefs.readPendingJson()
            pending.remove(key.storageName)
            prefs[keyPending] = pending.toString()
        }
    }

    /**
     * Applique les changements en attente dont l'echeance est passee.
     * Appele regulierement par l'UI et par le service de blocage : c'est ce qui
     * fait tomber les changements differes, sans alarme a programmer.
     */
    suspend fun applyDuePendingChanges(nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            val due = prefs.readPending().filter { it.applyAtEpochMs <= nowMs }
            if (due.isEmpty()) return@edit
            val pending = prefs.readPendingJson()
            due.forEach { change ->
                prefs.applyValue(change.key, change.value)
                pending.remove(change.key.storageName)
            }
            prefs[keyPending] = pending.toString()
        }
    }

    /** Ajoute du temps TikTok au compteur du jour (remis a zero si on a change de jour). */
    suspend fun addTiktokUsage(deltaMs: Long) {
        if (deltaMs <= 0L) return
        context.dataStore.edit { prefs ->
            val today = DayKey.today()
            val base = if (prefs[keyUsageDay] == today) prefs[keyUsageMs] ?: 0L else 0L
            prefs[keyUsageDay] = today
            prefs[keyUsageMs] = base + deltaMs
        }
    }

    // --- Conversions internes ----------------------------------------------

    private fun Preferences.toSettings() = Settings(
        blockSnapSpotlight = this[keySnap] ?: Settings.DEFAULT.blockSnapSpotlight,
        tiktokQuotaEnabled = this[keyQuotaEnabled] ?: Settings.DEFAULT.tiktokQuotaEnabled,
        tiktokQuotaMinutes = this[keyQuotaMinutes] ?: Settings.DEFAULT.tiktokQuotaMinutes,
        blockInstagramOfficial = this[keyInsta] ?: Settings.DEFAULT.blockInstagramOfficial,
        antiCheatEnabled = this[keyAntiCheat] ?: Settings.DEFAULT.antiCheatEnabled,
    )

    private fun Preferences.readPendingJson(): JSONObject =
        this[keyPending]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()

    private fun Preferences.readPending(): List<PendingChange> {
        val json = readPendingJson()
        return json.keys().asSequence().mapNotNull { name ->
            val key = SettingKey.fromStorageName(name) ?: return@mapNotNull null
            val entry = json.optJSONObject(name) ?: return@mapNotNull null
            val raw = entry.opt(JSON_VALUE) ?: return@mapNotNull null
            PendingChange(key, raw, entry.optLong(JSON_APPLY_AT))
        }.toList()
    }

    /**
     * Ecrit reellement la valeur. RESET_TIKTOK_USAGE n'est pas un reglage mais
     * une action : on remet le compteur du jour a zero.
     */
    private fun MutablePreferences.applyValue(key: SettingKey, raw: Any) {
        when (key) {
            SettingKey.BLOCK_SNAP_SPOTLIGHT -> this[keySnap] = raw as Boolean
            SettingKey.TIKTOK_QUOTA_ENABLED -> this[keyQuotaEnabled] = raw as Boolean
            SettingKey.BLOCK_INSTAGRAM_OFFICIAL -> this[keyInsta] = raw as Boolean
            SettingKey.ANTI_CHEAT_ENABLED -> this[keyAntiCheat] = raw as Boolean
            // JSONObject peut rendre un Integer ou un Long : on passe par Number.
            SettingKey.TIKTOK_QUOTA_MINUTES ->
                this[keyQuotaMinutes] =
                    (raw as Number).toInt().coerceIn(MIN_QUOTA_MINUTES, MAX_QUOTA_MINUTES)
            SettingKey.RESET_TIKTOK_USAGE -> {
                this[keyUsageDay] = DayKey.today()
                this[keyUsageMs] = 0L
            }
        }
    }

    companion object {
        /** Delai impose par le mode anti-triche avant qu'un changement s'applique. */
        const val ANTI_CHEAT_DELAY_MINUTES = 10

        const val MIN_QUOTA_MINUTES = 1
        const val MAX_QUOTA_MINUTES = 600

        private const val JSON_VALUE = "v"
        private const val JSON_APPLY_AT = "at"

        @Volatile
        private var instance: SettingsRepository? = null

        /** Un seul DataStore par process, sinon DataStore leve une exception. */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}

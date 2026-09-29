package com.theo.distractionblocker.core.prefs

/** État effectif des réglages, tel qu'appliqué par le service de blocage. */
data class Settings(
    val blockSnapSpotlight: Boolean,
    val tiktokQuotaEnabled: Boolean,
    val tiktokQuotaMinutes: Int,
    val blockInstagramOfficial: Boolean,
    val antiCheatEnabled: Boolean,
) {
    val tiktokQuotaMs: Long get() = tiktokQuotaMinutes * 60_000L

    companion object {
        /** Tout est désactivé au premier lancement : on active ce qu'on veut. */
        val DEFAULT = Settings(
            blockSnapSpotlight = false,
            tiktokQuotaEnabled = false,
            tiktokQuotaMinutes = 30,
            blockInstagramOfficial = false,
            antiCheatEnabled = false,
        )
    }
}

/**
 * Chaque réglage modifiable, plus l'action "remise à zéro du compteur".
 * Les [storageName] servent à la fois de clés DataStore et de clés dans le
 * JSON des changements en attente (mode anti-triche).
 */
enum class SettingKey(val storageName: String, val label: String) {
    BLOCK_SNAP_SPOTLIGHT("block_snap_spotlight", "Blocage Spotlight et Stories (Snapchat)"),
    TIKTOK_QUOTA_ENABLED("tiktok_quota_enabled", "Quota TikTok"),
    TIKTOK_QUOTA_MINUTES("tiktok_quota_minutes", "Durée du quota TikTok"),
    BLOCK_INSTAGRAM_OFFICIAL("block_instagram_official", "Blocage d'Instagram officiel"),
    ANTI_CHEAT_ENABLED("anti_cheat_enabled", "Mode anti-triche"),
    RESET_TIKTOK_USAGE("reset_tiktok_usage", "Remise à zéro du temps TikTok"),
    ;

    companion object {
        fun fromStorageName(name: String): SettingKey? = entries.find { it.storageName == name }
    }
}

/**
 * Changement demandé mais pas encore appliqué, parce que le mode anti-triche
 * impose un délai. [value] est un Boolean ou un Int selon la clé.
 */
data class PendingChange(
    val key: SettingKey,
    val value: Any,
    val applyAtEpochMs: Long,
) {
    /** Millisecondes restantes avant application, 0 si c'est déjà mûr. */
    fun remainingMs(nowMs: Long): Long = (applyAtEpochMs - nowMs).coerceAtLeast(0L)

    /** Libellé de la valeur visée, pour l'afficher dans l'UI. */
    val valueLabel: String
        get() = when (value) {
            is Boolean -> if (value) "activé" else "désactivé"
            else -> value.toString()
        }
}

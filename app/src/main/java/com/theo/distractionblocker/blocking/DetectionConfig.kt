package com.theo.distractionblocker.blocking

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "DetectionConfig"

/**
 * Tout ce qui depend du DOM interne des applis surveillees (identifiants de vues
 * Snapchat, noms de paquets TikTok) vit dans un fichier JSON editable sur le
 * telephone, PAS dans le code.
 *
 * Emplacement sur le telephone :
 *   /sdcard/Android/data/com.theo.distractionblocker/files/detection_config.json
 *
 * Ce dossier est accessible sans aucune permission, par adb push ou par un
 * gestionnaire de fichiers. Au premier lancement, la version par defaut livree
 * dans assets/ y est copiee. Modifier le fichier suffit : le service le
 * recharge tout seul des que sa date de modification change (voir [Loader]).
 */
data class DetectionConfig(
    val snapchat: SnapConfig,
    val tiktokPackages: Set<String>,
    val instagramPackage: String,
) {
    /**
     * Comment reconnaitre l'onglet Spotlight. Les trois strategies sont
     * cumulatives : la premiere qui matche declenche le blocage.
     */
    data class SnapConfig(
        val packageName: String,
        /** "home" -> GLOBAL_ACTION_HOME, "back" -> GLOBAL_ACTION_BACK. */
        val action: String,
        /** Identifiants de vues qui n'existent QUE sur la page Spotlight. */
        val blockedScreenViewIds: List<String>,
        /** Identifiants du bouton d'onglet Spotlight : matche s'il est selectionne. */
        val blockedScreenTabViewIds: List<String>,
        /** content-desc a chercher (comparaison insensible a la casse, "contient"). */
        val blockedScreenContentDescriptions: List<String>,
        /** Heuristique de dernier recours : chercher un texte visible. Faux positifs possibles. */
        val useTextHeuristic: Boolean,
        val blockedScreenTexts: List<String>,
    )

    companion object {
        const val FILE_NAME = "detection_config.json"
        private const val ASSET_NAME = "detection_config.json"

        /**
         * Fichier editable, dans le dossier "files" externe de l'app.
         * getExternalFilesDir(null) peut renvoyer null si le stockage externe
         * est indisponible : on retombe alors sur le stockage interne (moins
         * pratique a editer, mais l'app continue de fonctionner).
         */
        fun configFile(context: Context): File {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            return File(dir, FILE_NAME)
        }

        /** Copie la config par defaut depuis assets/ si le fichier n'existe pas encore. */
        fun ensureFileExists(context: Context): File {
            val file = configFile(context)
            if (!file.exists()) {
                runCatching {
                    file.parentFile?.mkdirs()
                    context.assets.open(ASSET_NAME).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }.onFailure { Log.w(TAG, "Copie de la config par defaut impossible", it) }
            }
            return file
        }

        /** Valeurs de repli, utilisees si le JSON est absent ou mal forme. */
        val FALLBACK = DetectionConfig(
            snapchat = SnapConfig(
                packageName = "com.snapchat.android",
                action = "home",
                blockedScreenViewIds = emptyList(),
                blockedScreenTabViewIds = emptyList(),
                blockedScreenContentDescriptions = emptyList(),
                useTextHeuristic = false,
                blockedScreenTexts = emptyList(),
            ),
            tiktokPackages = setOf(
                "com.zhiliaoapp.musically",
                "com.ss.android.ugc.trill",
                "com.zhiliaoapp.musically.go",
            ),
            instagramPackage = "com.instagram.android",
        )

        fun parse(json: String): DetectionConfig {
            val root = JSONObject(json)
            val snap = root.optJSONObject("snapchat") ?: JSONObject()
            val tiktok = root.optJSONObject("tiktok") ?: JSONObject()
            val insta = root.optJSONObject("instagram") ?: JSONObject()
            return DetectionConfig(
                snapchat = SnapConfig(
                    packageName = snap.optString("packageName", FALLBACK.snapchat.packageName),
                    action = snap.optString("action", FALLBACK.snapchat.action),
                    blockedScreenViewIds = snap.optJSONArray("blockedScreenViewIds").toStringList(),
                    blockedScreenTabViewIds = snap.optJSONArray("blockedScreenTabViewIds").toStringList(),
                    blockedScreenContentDescriptions =
                        snap.optJSONArray("blockedScreenContentDescriptions").toStringList(),
                    useTextHeuristic = snap.optBoolean("useTextHeuristic", false),
                    blockedScreenTexts = snap.optJSONArray("blockedScreenTexts").toStringList(),
                ),
                tiktokPackages = tiktok.optJSONArray("packageNames")
                    .toStringList()
                    .takeIf { it.isNotEmpty() }
                    ?.toSet()
                    ?: FALLBACK.tiktokPackages,
                instagramPackage = insta.optString("packageName", FALLBACK.instagramPackage),
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
        }
    }

    /**
     * Charge la config et la recharge quand le fichier change sur le disque.
     * On compare la date de modification plutot que d'observer le fichier :
     * c'est appele depuis le tick periodique du service, donc une fois toutes
     * les quelques secondes au maximum.
     */
    class Loader(private val context: Context) {
        private var lastModified = -1L
        private var cached: DetectionConfig = FALLBACK

        /** Config courante, rechargee si le fichier a change depuis le dernier appel. */
        fun current(): DetectionConfig {
            val file = ensureFileExists(context)
            val stamp = if (file.exists()) file.lastModified() else 0L
            if (stamp != lastModified) {
                lastModified = stamp
                cached = runCatching { parse(file.readText()) }
                    .onFailure { Log.w(TAG, "detection_config.json illisible, valeurs de repli", it) }
                    .getOrDefault(FALLBACK)
            }
            return cached
        }
    }
}

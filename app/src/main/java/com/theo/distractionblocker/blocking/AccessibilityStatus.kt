package com.theo.distractionblocker.blocking

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Le service d'accessibilite est-il actif ?
 *
 * Une app ne peut pas activer son propre service d'accessibilite : seul
 * l'utilisateur peut, dans les reglages systeme. On se contente donc de lire la
 * liste des services actives, qui est un reglage global lisible.
 */
object AccessibilityStatus {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        // La valeur est une liste de composants separes par ':', ecrits sous une
        // forme qui varie (nom de classe absolu ou relatif). unflattenFromString
        // normalise les deux.
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }
}

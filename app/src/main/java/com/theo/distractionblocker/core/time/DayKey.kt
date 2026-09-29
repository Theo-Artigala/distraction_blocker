package com.theo.distractionblocker.core.time

import java.time.LocalDate

/**
 * Clé de jour ("2026-09-29") utilisée pour savoir si le compteur TikTok
 * stocké appartient encore à aujourd'hui.
 *
 * Le "reset à minuit" n'est pas une tâche planifiée : on compare simplement la
 * date stockée à la date du jour à chaque lecture. Pas d'alarme à programmer,
 * pas de réveil à minuit, et ça survit aux redémarrages.
 */
object DayKey {
    /** Date locale du téléphone, au format ISO. */
    fun today(): String = LocalDate.now().toString()
}

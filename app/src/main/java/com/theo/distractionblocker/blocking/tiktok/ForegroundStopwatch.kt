package com.theo.distractionblocker.blocking.tiktok

import android.os.SystemClock

/**
 * Chronometre qui ne tourne que pendant les periodes explicitement marquees
 * actives. Le service appelle [setActive] quand TikTok arrive ou quitte le
 * premier plan (et quand l'ecran s'allume ou s'eteint), et [harvest]
 * periodiquement pour recuperer le temps a ajouter au compteur du jour.
 *
 * Deux details qui comptent :
 *
 * - On utilise [SystemClock.elapsedRealtime] (temps depuis le boot, monotone),
 *   pas System.currentTimeMillis() : ce dernier saute si l'horloge est ajustee
 *   (NTP, changement manuel, fuseau) et donnerait des durees negatives ou
 *   fantaisistes.
 * - Chaque recuperation remet le point de depart a maintenant, donc appeler
 *   [harvest] plusieurs fois ne compte jamais deux fois le meme intervalle.
 */
class ForegroundStopwatch(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    /** Instant du debut de la periode active en cours, null si a l'arret. */
    private var activeSince: Long? = null

    val isRunning: Boolean get() = activeSince != null

    /**
     * Demarre ou arrete le chrono.
     * @return le temps ecoule (ms) depuis le dernier releve, a ajouter au total.
     */
    fun setActive(active: Boolean): Long {
        val now = clock()
        val elapsed = activeSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        activeSince = if (active) now else null
        return elapsed
    }

    /**
     * Releve le temps accumule depuis le dernier appel, sans arreter le chrono.
     * @return 0 si le chrono est a l'arret.
     */
    fun harvest(): Long = if (isRunning) setActive(true) else 0L
}

package com.theo.distractionblocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.theo.distractionblocker.R

private const val TAG = "KeepAliveService"

/**
 * Service de premier plan dont le seul role est d'exister.
 *
 * HyperOS / MIUI tue les process en arriere-plan beaucoup plus agressivement
 * qu'Android de base, y compris les services d'accessibilite. Un process qui
 * heberge un foreground service passe dans une categorie de priorite bien plus
 * haute, ce qui reduit nettement les chances qu'il soit tue.
 *
 * Il ne fait aucun travail : le blocage est entierement dans
 * [com.theo.distractionblocker.blocking.BlockerAccessibilityService], qui vit
 * dans le meme process.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    // START_STICKY : si le systeme nous tue quand meme, il recree le service
    // des qu'il a de la place.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Service non lie : rien a exposer aux clients. */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_MIN : pas de son, pas de vibration, pas d'icone dans la
        // barre de statut. La notification n'est visible qu'en deroulant le
        // volet, c'est le minimum legal pour un foreground service.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1

        /**
         * Demarre le service. Depuis Android 12, demarrer un foreground service
         * depuis l'arriere-plan leve ForegroundServiceStartNotAllowedException ;
         * on l'avale, parce que les deux chemins normaux (l'ouverture de l'app
         * et BOOT_COMPLETED) sont autorises, et qu'un echec ici ne casse pas le
         * blocage, il le rend juste plus fragile face a MIUI.
         */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Demarrage du foreground service refuse", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}

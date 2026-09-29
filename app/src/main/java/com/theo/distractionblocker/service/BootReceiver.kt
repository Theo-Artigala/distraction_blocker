package com.theo.distractionblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relance le foreground service apres un redemarrage.
 *
 * Le service d'accessibilite, lui, est relance par le systeme tout seul (le
 * reglage est persistant). Mais le [KeepAliveService] ne se rallume pas de
 * lui-meme, et sur MIUI il faut en plus avoir autorise le demarrage
 * automatique de l'app, sinon ce receiver n'est jamais appele : voir la section
 * "Configuration Xiaomi" de l'ecran de config.
 *
 * Les receivers BOOT_COMPLETED font partie des cas autorises a demarrer un
 * foreground service depuis l'arriere-plan.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON ->
                KeepAliveService.start(context)
        }
    }

    private companion object {
        /** Diffusion propre a MIUI, envoyee a la place de BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}

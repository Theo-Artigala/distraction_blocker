package com.theo.distractionblocker.xiaomi

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

private const val TAG = "SystemScreens"

/**
 * Ouvre les ecrans de reglages systeme dont l'app a besoin.
 *
 * Les ecrans MIUI/HyperOS ne font pas partie d'Android : ce sont des activites
 * de `com.miui.securitycenter` et `com.miui.powerkeeper`, dont les noms changent
 * d'une version a l'autre et qui n'existent pas du tout ailleurs. On essaie donc
 * plusieurs candidats dans l'ordre, puis on retombe sur l'ecran Android standard
 * equivalent, et enfin sur la fiche de l'application.
 *
 * Chaque fonction renvoie true si un ecran a effectivement ete ouvert, pour que
 * l'UI puisse afficher "a faire a la main" quand ce n'est pas le cas.
 */
object SystemScreens {

    /** Reglages > Accessibilite : c'est la qu'on active le service de blocage. */
    fun openAccessibilitySettings(context: Context): Boolean =
        launchFirstAvailable(context, listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))

    /**
     * Fiche de l'application. Sert a deux choses :
     * - lever "Parametres restreints" (menu ... en haut a droite)
     * - acceder aux reglages de batterie de l'app
     */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        return launchFirstAvailable(context, listOf(intent))
    }

    /**
     * MIUI : "Demarrage automatique". Sans ca, l'app ne recoit pas
     * BOOT_COMPLETED et le foreground service ne repart pas apres un
     * redemarrage du telephone.
     */
    fun openAutostartSettings(context: Context): Boolean = launchFirstAvailable(
        context,
        listOf(
            componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // Variante presente sur certaines versions de HyperOS.
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        ),
    )

    /**
     * MIUI : "Economiseur de batterie" par application, ou il faut choisir
     * "Aucune restriction". C'est le reglage qui compte le plus contre les morts
     * de service sur HyperOS.
     *
     * On retombe sur l'ecran Android d'optimisation de batterie, qui existe
     * partout, si l'ecran MIUI est introuvable.
     */
    fun openBatterySaverSettings(context: Context): Boolean = launchFirstAvailable(
        context,
        listOf(
            // Ecran MIUI dedie, pre-filtre sur notre app quand il l'accepte.
            componentIntent(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ).putExtra("package_name", context.packageName)
                .putExtra("package_label", "Distraction Blocker"),
            componentIntent(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity",
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        ),
    )

    /** Construit un Intent vers une activite precise d'une autre application. */
    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    /**
     * Lance le premier Intent que le systeme sait resoudre.
     *
     * resolveActivity ne suffit pas toujours sur MIUI : une activite peut etre
     * listee mais refuser le lancement (SecurityException si elle n'est pas
     * exportee). D'ou le try/catch autour de startActivity.
     */
    private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            // Obligatoire quand on lance une activite depuis un Context non-Activity.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Activite absente : ${intent.component}", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Activite non exportee : ${intent.component}", e)
            }
        }
        return false
    }
}

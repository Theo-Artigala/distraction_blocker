package com.theo.distractionblocker.blocking

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.theo.distractionblocker.R
import com.theo.distractionblocker.blocking.snap.BlockedScreenDetector
import com.theo.distractionblocker.blocking.tiktok.ForegroundStopwatch
import com.theo.distractionblocker.core.prefs.Settings
import com.theo.distractionblocker.core.prefs.SettingsRepository
import com.theo.distractionblocker.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BlockerService"

/**
 * Le service d'accessibilite : il recoit un evenement a chaque fois qu'une
 * fenetre change, en deduit l'application au premier plan, et applique les
 * blocages.
 *
 * Pour quelqu'un qui vient du web : c'est l'equivalent d'un listener global sur
 * "la fenetre active a change", fourni par le systeme. On ne peut pas savoir
 * quelle app est au premier plan autrement (Android ne l'expose pas aux apps
 * normales), d'ou le passage par l'accessibilite.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private lateinit var repo: SettingsRepository
    private lateinit var configLoader: DetectionConfig.Loader

    /**
     * Portee de coroutines liee a la duree de vie du service. Dispatchers.Main
     * parce qu'on touche a l'UI (Toast) et que les appels accessibilite doivent
     * rester sur le thread principal du service.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Copie locale des reglages, tenue a jour par un collect. @Volatile parce
     * qu'elle est ecrite par la coroutine et lue depuis onAccessibilityEvent.
     */
    @Volatile
    private var settings: Settings = Settings.DEFAULT

    @Volatile
    private var config: DetectionConfig = DetectionConfig.FALLBACK

    private val stopwatch = ForegroundStopwatch()

    /** Paquet de la derniere fenetre applicative vue au premier plan. */
    private var foregroundPackage: String? = null
    private var screenOn = true

    /** Temps TikTok du jour, en ms, cache pour ne pas relire DataStore a chaque tick. */
    @Volatile
    private var usageMs = 0L

    /** Anti-rebond : collapse une rafale d'evenements en une seule action. */
    private var lastActionAtRealtime = 0L

    /** Idem pour la detection d'ecran, qui tourne sur des evenements tres frequents. */
    private var lastScreenCheckAtRealtime = 0L

    /**
     * L'ecran eteint n'emet pas d'evenement d'accessibilite : sans ce receiver,
     * le chrono continuerait de tourner si on verrouille le telephone alors que
     * TikTok est au premier plan.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenStateChanged(false)
                Intent.ACTION_SCREEN_ON -> onScreenStateChanged(true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = SettingsRepository.get(this)
        configLoader = DetectionConfig.Loader(this)
        config = configLoader.current()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = powerManager.isInteractive

        // ContextCompat impose le flag NOT_EXPORTED exige depuis Android 14
        // pour les receivers enregistres a la main.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch { repo.settings.collect { settings = it } }
        scope.launch { repo.todayUsageMs.collect { usageMs = it } }
        scope.launch { tickLoop() }

        // Le foreground service sert uniquement a rendre le process moins
        // interessant a tuer pour HyperOS.
        KeepAliveService.start(this)
        Log.i(TAG, "Service de blocage connecte")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Desactivation du service dans les reglages : on solde le temps en
        // cours pour ne pas perdre la minute entamee.
        flushStopwatch()
        KeepAliveService.stop(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Appele quand le systeme demande au service de se taire. On n'emet
        // aucun retour vocal ou haptique, il n'y a donc rien a interrompre.
    }

    /**
     * Boucle periodique : c'est elle qui fait avancer le compteur TikTok, qui
     * recharge detection_config.json s'il a change, et qui fait tomber les
     * changements de reglage differes par le mode anti-triche.
     */
    private suspend fun tickLoop() {
        var ticks = 0L
        while (true) {
            delay(TICK_MS)
            ticks++

            // Chaque seconde : tout ce qui est gratuit et urgent. C'est le
            // filet de securite quand un evenement d'accessibilite est manque,
            // ce que MIUI fait regulierement.
            refreshForegroundFromActiveWindow()
            enforceTiktokQuotaIfNeeded()
            enforceScreenBlockerIfNeeded()

            // Toutes les 5 s : ce qui touche au disque. Ecrire le compteur
            // chaque seconde serait du gaspillage pour une precision inutile.
            if (ticks % PERSIST_EVERY_N_TICKS == 0L) {
                config = configLoader.current()
                repo.applyDuePendingChanges()
                flushStopwatch()
            }
        }
    }

    /**
     * Recale le paquet au premier plan sur la fenetre reellement active.
     *
     * Les evenements d'accessibilite peuvent etre manques (MIUI en filtre, une
     * fenetre peut ne pas etre vue comme applicative). Sans ce recalage, un
     * foregroundPackage fige sur TikTok ferait tourner le chrono a vide, ou au
     * contraire empecherait le blocage de repartir.
     */
    private fun refreshForegroundFromActiveWindow() {
        val actual = rootInActiveWindow?.packageName?.toString() ?: return
        if (actual != foregroundPackage) {
            Log.i(TAG, "Recalage du premier plan : $foregroundPackage -> $actual")
            onForegroundPackageChanged(actual)
        }
    }

    /** Ecrit le temps accumule dans DataStore et met a jour le cache local. */
    private fun flushStopwatch() {
        val delta = stopwatch.harvest()
        if (delta <= 0L) return
        usageMs += delta
        scope.launch { repo.addTiktokUsage(delta) }
    }

    private fun onScreenStateChanged(on: Boolean) {
        screenOn = on
        val delta = stopwatch.setActive(on && isTiktok(foregroundPackage))
        if (delta > 0L) {
            usageMs += delta
            scope.launch { repo.addTiktokUsage(delta) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // On ne change de "paquet au premier plan" que pour une vraie
                // fenetre d'application : sinon le clavier virtuel, un toast ou
                // le volet de notifications arreteraient le chrono a tort.
                if (isApplicationWindow(event.windowId)) {
                    onForegroundPackageChanged(packageName)
                }
                applyBlocking(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Changer d'onglet dans Snapchat ne produit pas toujours un
                // WINDOW_STATE_CHANGED : il faut aussi ecouter le contenu.
                // Ces evenements arrivent en rafale, d'ou l'anti-rebond.
                if (activeScreenBlocker(packageName) != null) {
                    applyBlocking(packageName)
                }
            }
        }
    }

    private fun onForegroundPackageChanged(packageName: String) {
        if (packageName == foregroundPackage) return
        foregroundPackage = packageName

        Log.i(TAG, "Premier plan : $packageName")

        val shouldRun = screenOn && isTiktok(packageName)
        val delta = stopwatch.setActive(shouldRun)
        if (delta > 0L) {
            usageMs += delta
            scope.launch { repo.addTiktokUsage(delta) }
        }
    }

    /** Aiguillage : quel blocage s'applique a ce paquet ? */
    private fun applyBlocking(packageName: String) {
        if (packageName == config.instagramPackage && settings.blockInstagramOfficial) {
            goHome("Instagram officiel bloque")
            return
        }

        val screen = activeScreenBlocker(packageName)
        if (screen != null) {
            checkBlockedScreen(screen)
            return
        }

        if (isTiktok(packageName)) {
            enforceTiktokQuotaIfNeeded()
        }
    }

    /**
     * La configuration d'ecrans bloques qui s'applique a ce paquet, ou null.
     *
     * Snapchat (Spotlight, Stories) et YouTube (Shorts) posent exactement le
     * meme probleme : reconnaitre un ecran precis dans une application par
     * ailleurs autorisee. Ils partagent donc le meme detecteur, seuls les
     * identifiants et l'interrupteur different.
     */
    private fun activeScreenBlocker(packageName: String?): DetectionConfig.ScreenConfig? = when {
        packageName == null -> null
        packageName == config.snapchat.packageName && settings.blockSnapSpotlight -> config.snapchat
        packageName == config.youtube.packageName && settings.blockYoutubeShorts -> config.youtube
        else -> null
    }

    /**
     * Verifie l'ecran courant depuis le tick, en plus des evenements.
     *
     * Indispensable : un ecran deja charge et immobile n'emet plus aucun
     * evenement d'accessibilite. Sans ce controle periodique, y atterrir
     * autrement qu'en naviguant (par exemple en y etant depose par un retour
     * arriere) ne declenchait rien du tout, et on y restait.
     */
    private fun enforceScreenBlockerIfNeeded() {
        val screen = activeScreenBlocker(foregroundPackage) ?: return
        checkBlockedScreen(screen)
    }

    private fun checkBlockedScreen(screen: DetectionConfig.ScreenConfig) {
        // Une application non calibree ne peut rien detecter : inutile de
        // parcourir son arbre d'accessibilite chaque seconde pour rien.
        if (!screen.isCalibrated) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenCheckAtRealtime < SCREEN_CHECK_INTERVAL_MS) return
        lastScreenCheckAtRealtime = now

        if (!BlockedScreenDetector.isOnBlockedScreen(rootInActiveWindow, screen)) return

        if (screen.action.equals("back", ignoreCase = true)) {
            // Retour arriere : ramene a l'ecran precedent de l'application,
            // moins brutal que de la quitter.
            // BACK n'est PAS idempotent : l'enchainer ferait sortir de
            // l'application entierement, d'ou un anti-rebond plus long.
            performBlockingAction(
                GLOBAL_ACTION_BACK,
                BACK_COOLDOWN_MS,
                "Ecran bloque (${screen.packageName}), retour arriere",
            )
        } else {
            goHome("Ecran bloque (${screen.packageName})")
        }
    }

    /** Ejecte de TikTok si le quota du jour est epuise. */
    private fun enforceTiktokQuotaIfNeeded() {
        if (!settings.tiktokQuotaEnabled) return
        if (!isTiktok(foregroundPackage)) return
        if (usageMs < settings.tiktokQuotaMs) return

        // Le toast n'est affiche qu'avec le retour a l'accueil, donc au plus une
        // fois par periode d'anti-rebond.
        if (goHome("Quota TikTok epuise")) {
            Toast.makeText(this, R.string.quota_reached_toast, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Retour a l'accueil. L'anti-rebond est volontairement tres court : appuyer
     * sur HOME alors qu'on est deja sur l'accueil ne fait rien, donc une action
     * en trop est sans consequence, alors qu'une action manquante te laisse
     * entrer dans l'app. Le compromis penche donc du cote "trop" plutot que
     * "pas assez".
     */
    private fun goHome(reason: String): Boolean =
        performBlockingAction(GLOBAL_ACTION_HOME, HOME_COOLDOWN_MS, reason)

    /**
     * Execute une action globale. [cooldownMs] ne sert qu'a collapser une
     * rafale d'evenements en une seule action, pas a limiter le blocage dans le
     * temps. Renvoie true si l'action a bien ete declenchee.
     */
    private fun performBlockingAction(action: Int, cooldownMs: Long, reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAtRealtime < cooldownMs) {
            Log.i(TAG, "Blocage ignore (anti-rebond) : $reason")
            return false
        }
        lastActionAtRealtime = now
        val performed = performGlobalAction(action)
        Log.i(TAG, "Blocage applique : $reason (performGlobalAction=$performed)")
        return performed
    }

    private fun isTiktok(packageName: String?): Boolean =
        packageName != null && packageName in config.tiktokPackages

    /**
     * Vrai si la fenetre est une fenetre d'application (pas un clavier, pas une
     * surcouche systeme). Necessite flagRetrieveInteractiveWindows dans
     * res/xml/accessibility_service_config.xml pour que getWindows() reponde.
     */
    private fun isApplicationWindow(windowId: Int): Boolean {
        val window = windows.firstOrNull { it.id == windowId }
            ?: return true // Liste indisponible : on fait confiance a l'evenement.
        return window.type == AccessibilityWindowInfo.TYPE_APPLICATION
    }

    private companion object {
        /** Periode du tick : recalage du premier plan et controle du quota. */
        const val TICK_MS = 1_000L

        /**
         * Un tick sur 5 ecrit sur le disque. Le franchissement initial du quota
         * peut donc etre detecte avec jusqu'a 5 s de retard, mais une fois le
         * quota depasse, le blocage reagit en une seconde.
         */
        const val PERSIST_EVERY_N_TICKS = 5L

        /**
         * Anti-rebond du retour a l'accueil. Assez court pour qu'aucune
         * reouverture humaine ne passe au travers (lancer une app prend deja
         * plus que ca), assez long pour ne pas rejouer l'action sur chaque
         * evenement d'une meme rafale.
         */
        const val HOME_COOLDOWN_MS = 300L

        /**
         * Anti-rebond du retour arriere. Plus court qu'il n'y parait necessaire,
         * parce que l'enchainement est ici VOULU : quitter Spotlight depose dans
         * Stories, egalement bloque, et il faut un second BACK pour atteindre un
         * ecran autorise. 700 ms collapse toujours une rafale d'evenements (qui
         * arrivent en quelques dizaines de ms) tout en resolvant la chaine en
         * moins d'une seconde et demie.
         */
        const val BACK_COOLDOWN_MS = 700L

        /** Duree minimale entre deux inspections de l'arbre d'accessibilite. */
        const val SCREEN_CHECK_INTERVAL_MS = 400L
    }
}

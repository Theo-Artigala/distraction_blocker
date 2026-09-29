# Distraction Blocker

App Android perso pour limiter Snapchat Spotlight, TikTok et Instagram. 100 % locale :
aucun réseau sauf le chargement d'Instagram dans la WebView, aucun backend, aucun tracking.

## Compiler et installer

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

L'APK sort dans `app/build/outputs/apk/debug/app-debug.apk` (~9 Mo).

Prérequis : JDK 17 et le SDK Android 34. Sur cette machine ils sont dans
`C:\Users\theoa\dev-tools`, et `local.properties` pointe déjà dessus. Si tu compiles
ailleurs, adapte `local.properties` (`sdk.dir=...`).

> **Note sur cette machine** : Cloudflare WARP intercepte le TLS, et le JDK ne connaît pas
> son autorité de certification. Sans contournement, Gradle et `sdkmanager` échouent en
> `SSLHandshakeException`. C'est réglé par `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT`
> dans `~/.gradle/gradle.properties`, qui fait lire à Java le magasin de certificats Windows.
> Pour `sdkmanager`, passer `JAVA_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`.

`assembleRelease` marche aussi, signé avec la clé de debug. Elle est locale à la machine
(`~/.android/debug.keystore`) : si tu la perds, il faudra désinstaller avant de réinstaller,
donc tu perdras les cookies Instagram de la WebView.

## Activer le service d'accessibilité

Rien ne fonctionne avant cette étape. L'écran de config affiche « Inactif » en rouge tant
que ce n'est pas fait.

1. Ouvre l'app → **Ouvrir les réglages d'accessibilité**
2. Applications installées → Distraction Blocker → active l'interrupteur

### Si l'interrupteur est grisé (Android 13+, « Paramètres restreints »)

Android refuse qu'une app installée hors Play Store touche à l'accessibilité, tant que la
restriction n'est pas levée manuellement :

1. Dans l'app → **Ouvrir la fiche de l'application**
2. Menu `⋮` en haut à droite → **Autoriser les paramètres restreints**
3. Retour dans les réglages d'accessibilité : l'interrupteur est activable

## Configuration Xiaomi (HyperOS / MIUI)

HyperOS tue les services en arrière-plan bien plus agressivement qu'Android de base, service
d'accessibilité compris. Les trois réglages sont dans la section « Configuration Xiaomi » de
l'app, avec un bouton qui ouvre l'écran quand l'intent est résoluble.

| Réglage | Pourquoi | Chemin manuel |
|---|---|---|
| Démarrage automatique | Sans lui, l'app ne reçoit pas `BOOT_COMPLETED` : les blocages ne repartent pas après un redémarrage | Sécurité → Autorisations → Démarrage automatique |
| Économiseur de batterie : **Aucune restriction** | Le réglage qui compte le plus contre les morts de service | Batterie → Économiseur de batterie de l'application |
| Verrouiller dans le multitâche | Empêche le « tout fermer » des récents de tuer l'app | Récents → appui long sur la vignette → cadenas |

Le verrouillage multitâche ne peut pas être déclenché par intent : c'est manuel, d'où
l'absence de bouton.

L'app fait tourner un foreground service (`KeepAliveService`) dont le seul rôle est
d'exister : un process qui héberge un foreground service est bien moins susceptible d'être
tué. Sa notification est en `IMPORTANCE_MIN` : pas de son, pas d'icône dans la barre de
statut, visible seulement en déroulant le volet.

## Calibrer la détection Snapchat (Spotlight et Stories)

Les identifiants de vues de Snapchat ne sont pas documentés et changent à chaque version.
Toute la détection est isolée dans `SnapScreenDetector.kt` et pilotée par un fichier JSON
éditable sur le téléphone : aucun recompilage n'est nécessaire pour la recalibrer.

**Fichier à éditer** (créé au premier lancement) :

```
/sdcard/Android/data/com.theo.distractionblocker/files/detection_config.json
```

Le service le recharge tout seul dans les 5 secondes, sans redémarrage ni réinstallation.

### Relever les identifiants

Un dump par écran à bloquer, plus un dump de référence sur un écran autorisé.

```bash
# 1. Un dump par écran VISE (Spotlight, puis Stories) :
adb shell uiautomator dump /sdcard/spotlight.xml && adb pull /sdcard/spotlight.xml
adb shell uiautomator dump /sdcard/stories.xml   && adb pull /sdcard/stories.xml

# 2. Un dump de REFERENCE sur un écran autorisé (Chat, Caméra) :
adb shell uiautomator dump /sdcard/autre.xml && adb pull /sdcard/autre.xml

# 3. Ce qui est présent sur l'écran visé et ABSENT de la référence :
#    ce sont les candidats.
ids() { grep -o 'resource-id="[^"]*"' "$1" | sed 's/resource-id="//;s/"$//' \
        | grep -v '^$' | sort -u; }
comm -23 <(ids spotlight.xml) <(ids autre.xml)
comm -23 <(ids stories.xml)   <(ids autre.xml)
```

Tous les candidats ne se valent pas. Préfère un **conteneur de page** à un **élément de
contenu** : `spotlight_container` existe tant que la page Spotlight est affichée, alors
qu'une tuile comme `df_large_story` disparaît si l'onglet n'a rien à montrer.

Et méfie-toi des identifiants trop génériques, même exclusifs : `opera_viewer` a été écarté
parce que c'est le lecteur média de Snapchat, présent aussi sur les stories des amis en
plein écran — il éjecterait en pleine lecture.

### Où mettre ce que tu as trouvé

Trois stratégies, cumulatives, de la plus fiable à la plus risquée :

| Clé JSON | Quand l'utiliser | Risque |
|---|---|---|
| `blockedScreenViewIds` | Un `resource-id` qui n'existe **que** sur un écran à bloquer (résultat de l'étape 3) | Aucun faux positif. À privilégier. |
| `blockedScreenTabViewIds` | Le `resource-id` d'un bouton d'onglet, présent en permanence dans la barre du bas. Ne déclenche que si le nœud est `selected` ou `checked` | **Inutilisable sur cette version de Snapchat** : aucun nœud n'est marqué `selected`, pas même l'onglet actif |
| `blockedScreenContentDescriptions` | Un `content-desc` à chercher dans l'arbre. Exige aussi la sélection | Même limite : sans marquage de sélection, impossible de distinguer « tu y es » de « voici le bouton pour y aller » |
| `useTextHeuristic: true` | Dernier recours : cherche un texte visible, **sans** exiger de sélection | Élevé : un libellé « Spotlight » ailleurs t'éjecte à tort. `false` par défaut. |

`"action"` vaut `"home"` (retour à l'accueil du téléphone) ou `"back"` (retour arrière dans
Snapchat). **`back` est le bon choix** : Snapchat restaure le dernier onglet ouvert, donc
avec `home` on est éjecté à chaque lancement de l'app et elle devient inutilisable.

Le blocage ne dépend pas que des événements d'accessibilité : le service réinspecte l'écran
chaque seconde. Sans ça, atterrir sur un écran bloqué autrement qu'en naviguant — par
exemple en y étant déposé par un retour arrière — ne déclenchait rien, parce qu'un écran
déjà chargé et immobile n'émet plus aucun événement.

### Vérifier les paquets TikTok

```bash
adb shell pm list packages | grep -i -e tiktok -e musically -e trill
```

Les trois variantes connues sont déjà dans `detection_config.json` (`tiktok.packageNames`) :
TikTok monde, l'ancienne version internationale et TikTok Lite.

## Mettre à jour les sélecteurs Instagram

Instagram change son DOM régulièrement. **Aucune logique Kotlin n'est à toucher** : tout est
dans deux fichiers.

| Fichier | Contenu |
|---|---|
| `app/src/main/assets/insta_rules.css` | Masquage purement structurel (onglet Reels, liens `/reels/`) |
| `app/src/main/assets/insta_rules.js` | Bloc `CONFIG` en haut du fichier : tous les sélecteurs, libellés et réglages, plus les interrupteurs `features` pour désactiver une règle qui casse |

Ces fichiers sont dans l'APK : après modification, `./gradlew assembleDebug && adb install -r ...`.
Il faut **fermer la WebView depuis le multitâche** avant de la relancer, sinon l'ancien
script reste en mémoire.

### Les règles en place, et pourquoi elles sont écrites comme ça

Trois d'entre elles évitent délibérément les sélecteurs de structure, parce que c'est ce qui
casse à chaque refonte d'Instagram. Elles partent d'un point d'ancrage stable et déduisent le
reste.

**Explorer : ne garder que la barre de recherche** (`CONFIG.explore`). Plutôt que de traquer
les vignettes Reels une par une — ce qui obligeait à deviner de combien de parents remonter
pour masquer une cellule — le script trouve la barre de recherche, remonte jusqu'au `body` et
masque les frères et sœurs à chaque étage. Il ne faut donc qu'**un** sélecteur au lieu de
connaître la grille.

Deux garde-fous s'y ajoutent, parce qu'une isolation naïve faisait disparaître la navigation :
est préservé tout ce qui est en `position: fixed` ou `sticky` (du chrome d'interface, pas du
contenu défilant), et tout ce qui contient au moins deux liens vers les destinations
principales (`/`, `/explore`, `/direct`). La barre du bas d'Instagram n'étant ni un `<nav>` ni
un `role="navigation"`, les seuls sélecteurs explicites ne suffisaient pas.

L'isolation est **levée dès que tu touches la recherche**. Instagram affiche ses résultats
dans un conteneur qui n'est pas un ancêtre de la barre : il était donc masqué dès son
apparition, et la barre semblait morte. Le but étant de supprimer la grille, pas d'empêcher de
chercher, le compromis est de tout réafficher au premier contact. L'isolation revient à la
navigation suivante.

**Reel reçu en DM : lecture oui, défilement non** (`CONFIG.reelViewer`). Même principe : on
part de la `<video>` qui occupe l'écran, on remonte au premier conteneur qui déborde vraiment,
et on le fige avec `touch-action: none` — ce qui neutralise le geste de défilement sans
désactiver les taps, donc lecture, pause et fermeture continuent de répondre. Le verrou ne
s'applique que si la vidéo couvre au moins 60 % de la hauteur d'écran, pour ne pas bloquer le
défilement d'une conversation contenant une vignette.

**Diagnostic.** Passer `CONFIG.debug` à `true` fait journaliser, à chaque changement de page,
le nombre de posts détectés, de liens Reels, de sélecteurs de fil trouvés, et le sélecteur
retenu pour la barre de recherche. Visible sans navigateur :

```bash
adb logcat -s InstaWebView
```

C'est le moyen le plus rapide de voir lequel de tes sélecteurs a cessé de matcher. À remettre
à `false` ensuite, c'est très bavard.

### Trouver les bons sélecteurs avec chrome://inspect

1. Branche le téléphone en USB, débogage USB activé
2. Sur le PC : Chrome → `chrome://inspect/#devices`
3. Ouvre la WebView Instagram depuis l'app — elle apparaît dans la liste (le débogage WebView
   n'est activé qu'en build debug)
4. **inspect** → DevTools complets sur la page du téléphone
5. Teste ton sélecteur dans la Console **avant** de l'écrire dans le fichier :
   ```js
   document.querySelectorAll('ton selecteur').length
   document.querySelectorAll('ton selecteur')
     .forEach(n => n.style.outline = '3px solid red')
   ```
6. Pour itérer sans réinstaller : mets `window.__DB_CSS__ = ''`, puis colle tout le contenu
   de `insta_rules.js` dans la console
7. Pour voir ce que le JS attrape sans rien masquer : mets `debug: true` dans `CONFIG`, et
   remplace la règle `.db-hidden` du CSS par la variante en commentaire (contour rouge)

Ordre de préférence pour un sélecteur, du plus au moins durable :
`aria-label` / `role` → `href` → `data-*` → structure → classe.
Les classes du type `x1i10hfl` ou `_aacl` sont générées et changent à chaque déploiement.

## Mode anti-triche

Le délai est **asymétrique**, et c'est tout l'intérêt :

- **Durcir une limite s'applique immédiatement** — activer un blocage, raccourcir le quota,
  activer l'anti-triche lui-même. Retarder un durcissement n'aurait aucun sens : le délai
  existe pour décourager de céder, pas pour pénaliser l'envie de mieux faire.
- **Assouplir attend 1 heure**, avec un compte à rebours — désactiver un blocage, rallonger
  le quota, remettre le compteur TikTok à zéro, désactiver l'anti-triche.

Deux conséquences utiles : annuler une demande en attente est immédiat (ça ne fait que
conserver l'état actuel), et un durcissement annule automatiquement un assouplissement en
attente sur le même réglage — sinon celui-ci viendrait défaire le durcissement une heure plus
tard sans qu'on l'ait redemandé.

Le délai est la constante `ANTI_CHEAT_DELAY_MINUTES` dans `SettingsRepository.kt` (60 minutes). Le libellé affiché en découle automatiquement.

Il n'y a aucune alarme programmée : les changements différés « tombent » parce que l'UI et le
service appellent `applyDuePendingChanges()` chaque seconde. Un changement mûri pendant que
le téléphone était éteint s'applique donc dès la prochaine ouverture.

## Structure

```
app/src/main/
├── assets/
│   ├── detection_config.json     # ce qui dépend du DOM de Snapchat / des paquets TikTok
│   ├── insta_rules.css           # masquage structurel Instagram
│   └── insta_rules.js            # toute la logique de nettoyage Instagram
├── res/xml/
│   ├── accessibility_service_config.xml
│   └── file_paths.xml            # FileProvider, pour les photos prises depuis la WebView
└── java/com/theo/distractionblocker/
    ├── blocking/
    │   ├── BlockerAccessibilityService.kt   # détection du premier plan + application des blocages
    │   ├── AccessibilityStatus.kt           # le service est-il activé ?
    │   ├── DetectionConfig.kt               # lecture/rechargement du JSON éditable
    │   ├── snap/SnapScreenDetector.kt       # toute la détection Snapchat, isolée ici
    │   └── tiktok/ForegroundStopwatch.kt    # chrono qui ne tourne qu'au premier plan
    ├── core/
    │   ├── prefs/  Settings.kt, SettingsRepository.kt   # DataStore + anti-triche
    │   └── time/   DayKey.kt                            # reset à minuit
    ├── insta/
    │   ├── InstaWebViewActivity.kt          # WebView, upload/download, permissions
    │   └── InstaRules.kt                    # transport des assets vers la WebView
    ├── service/
    │   ├── KeepAliveService.kt               # foreground service anti-HyperOS
    │   └── BootReceiver.kt
    ├── ui/                                   # Compose : MainActivity, ConfigScreen, ConfigViewModel
    └── xiaomi/SystemScreens.kt               # ouverture des écrans MIUI
```

## Limites connues

**Fragilité aux mises à jour des apps.** C'est la limite structurelle : le blocage repose sur
la structure interne d'apps que je ne contrôle pas.

**Le blocage Spotlight est calibré mais désactivé.** Testé le 29/09/2026 et abandonné après essai, pour une raison de conception et non de technique : la détection fonctionne parfaitement (`spotlight_container`, vérifié par comparaison de deux dumps), mais aucune des deux actions disponibles ne donne un résultat utile.

- Avec `home`, Snapchat restaure le dernier onglet ouvert : on retombe sur Spotlight à chaque lancement, donc on est éjecté à chaque lancement et l'app devient inutilisable.
- Avec `back`, on atterrit dans les Stories — un autre fil à défilement infini. La distraction est déplacée, pas supprimée.

La calibration est conservée dans `detection_config.json` : réactiver le switch suffit à la remettre en service. Une piste si l'envie revient : distinguer « Snapchat s'est ouvert sur Spotlight » (restauration d'onglet, donc `back`) de « je viens de toucher l'onglet Spotlight » (choix délibéré, donc `home`), en regardant le délai écoulé depuis le passage de Snapchat au premier plan.

- **Snapchat** : une mise à jour peut renommer les `resource-id` du jour au lendemain. Le
  blocage Spotlight s'arrête alors silencieusement — aucune erreur, ça ne bloque simplement
  plus. Refaire un `uiautomator dump`. C'est pour ça que la config est un fichier éditable sur
  le téléphone et pas une constante Kotlin.
- **Instagram** : idem pour le DOM web. Les publicités et les Reels réapparaissent. Le fil
  « Abonnements » forcé est le plus fragile des quatre : `?variant=following` peut avoir
  disparu du web, et le clic sur le sélecteur de fil dépend d'un libellé traduit. Mets
  `features.forceFollowingFeed: false` si ça gêne.

**Délai de réaction du blocage.** L'accessibilité est notifiée *après* coup :

- `notificationTimeout=100 ms` dans la config du service : c'est la borne basse.
- En pratique, entre 100 et 600 ms s'écoulent entre l'ouverture de TikTok et le retour à
  l'accueil. Tu verras l'app s'ouvrir avant d'être éjecté.
- Pour Spotlight, la détection est limitée à une inspection toutes les 400 ms, et un
  anti-rebond de 2 s empêche d'enchaîner les actions. Le pire cas est donc ~1 s.
- Le quota TikTok est vérifié à chaque tick de 5 s : tu peux dépasser de 5 secondes.

**L'anti-triche ne protège que les réglages de cette app.** Désactiver le service
d'accessibilité, forcer l'arrêt ou désinstaller l'app reste instantané. C'est un ralentisseur,
pas une serrure — le choix a été fait de ne pas escalader (détecter et bloquer l'ouverture des
réglages système), pour ne pas risquer de te verrouiller hors de tes propres réglages en cas
de bug.

**Le compteur TikTok ne survit pas à la mort du service.** Le temps n'avance que pendant que
le service d'accessibilité est vivant. Si HyperOS le tue, ou après un redémarrage, le temps
écoulé pendant l'interruption n'est pas compté — le quota se « recharge » donc partiellement.
La config Xiaomi réduit le risque sans l'éliminer. Le rendre fiable demanderait
`UsageStatsManager` et la permission « Accès aux données d'utilisation », écarté pour l'instant.
Au plus, 5 secondes de temps déjà consommé sont perdues à chaque arrêt du service (période de
flush vers le disque).

**Autres points :**

- Le reset de minuit est basé sur la date locale. Si tu es sur TikTok à 23 h 58, le compteur
  repart à zéro en pleine session.
- Le volet de notifications déroulé par-dessus TikTok ne stoppe pas le chrono (c'est une
  fenêtre système, pas applicative) : léger sur-comptage, volontaire.
- Les médias Instagram servis en `blob:` ne sont pas téléchargeables — `DownloadManager` ne
  sait pas les traiter. L'app affiche un message au lieu d'échouer en silence.
- Pas de thème clair.

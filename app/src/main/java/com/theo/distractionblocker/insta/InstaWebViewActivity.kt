package com.theo.distractionblocker.insta

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.theo.distractionblocker.BuildConfig
import java.io.File

private const val TAG = "InstaWebView"

/**
 * Instagram dans une WebView, debarrasse des Reels et des publicités.
 *
 * Deux couches de filtrage, complementaires :
 *
 * - Kotlin (ici) intercepte les VRAIES navigations : c'est
 *   [WebViewClient.shouldOverrideUrlLoading], qui permet de ne meme pas charger
 *   une page Reels.
 * - JavaScript (assets/insta_rules.js) gere tout le reste : les navigations
 *   internes de l'application React (pushState), le scroll infini, le masquage.
 *   Le Kotlin ne connait aucun selecteur CSS.
 *
 * Ce qui n'est PAS touche : les DM, les profils, les posts des comptes suivis.
 */
class InstaWebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var rules: InstaRules

    /** Callback de l'input file en attente, fourni par la WebView. */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** Fichier cible d'une prise de photo en cours, null sinon. */
    private var cameraOutputUri: Uri? = null

    /** Action a rejouer une fois la reponse de l'utilisateur aux permissions connue. */
    private var pendingPermissionAction: ((Boolean) -> Unit)? = null

    /**
     * Resultat du selecteur de fichiers. Il est IMPERATIF de rappeler le
     * callback meme en cas d'annulation : sinon l'input file de la page reste
     * bloque et ne rouvrira plus jamais.
     */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            fileChooserCallback = null
            callback.onReceiveValue(extractSelectedUris(result.resultCode, result.data))
            cameraOutputUri = null
        }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingPermissionAction
            pendingPermissionAction = null
            action?.invoke(grants.values.all { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules = InstaRules(this)
        if (!rules.isUsable) {
            // Sans les regles, la WebView afficherait Instagram nu, Reels compris.
            Toast.makeText(this, "Règles insta_rules.js illisibles", Toast.LENGTH_LONG).show()
        }

        // Indispensable pour chrome://inspect. Uniquement en build debug : en
        // release, ouvrir sa session Instagram au premier cable USB serait idiot.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this).also { setContentView(it) }
        configureWebView()
        configureCookies()

        onBackPressedDispatcher.addCallback(this) {
            // Le bouton retour doit naviguer dans l'historique de la page avant
            // de fermer l'activite.
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        // restoreState renvoie null quand il n'y a rien a restaurer (premier
        // lancement, ou etat trop vieux) : sans ce repli, la WebView resterait
        // blanche.
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            webView.loadUrl(HOME_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Conserve l'historique de navigation a la rotation.
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Ecrit les cookies sur le disque tout de suite : sinon un process tue
        // par MIUI te deconnecte d'Instagram.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // --- Configuration -----------------------------------------------------

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            // localStorage / sessionStorage : Instagram ne fonctionne pas sans.
            domStorageEnabled = true
            // Sans user agent de vrai Chrome, Instagram sert une page degradee
            // ou refuse la connexion (la WebView annonce "; wv" par defaut).
            userAgentString = CHROME_USER_AGENT
            // La page gere elle-meme son viewport mobile.
            useWideViewPort = false
            loadWithOverviewMode = false
            // Aucune raison de laisser la page lire des fichiers locaux.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                if (!isInstagram(url)) {
                    // Liens sortants (boutique, lien en bio) : dans le vrai
                    // navigateur, sinon on se retrouve coince hors d'Instagram
                    // dans une WebView sans barre d'adresse.
                    return openExternally(url)
                }
                if (isBlockedPath(url.path)) {
                    Log.i(TAG, "Navigation Reels bloquee : $url")
                    view.loadUrl(HOME_URL)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectRules(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // Une seule selection a la fois : on annule la precedente pour
                // ne pas laisser un callback orphelin.
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                openFileChooser(fileChooserParams)
                return true
            }

            /**
             * Renvoie la console JavaScript de la page dans logcat. C'est le
             * seul moyen de voir ce que insta_rules.js attrape quand on n'a pas
             * chrome://inspect sous la main : adb logcat -s InstaWebView.
             */
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "JS[${message.messageLevel()}] ${message.message()}")
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // getUserMedia : Instagram web demande la camera (et le micro
                // pour une video) quand on cree une story depuis le navigateur.
                val needed = request.resources.mapNotNull { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                        else -> null
                    }
                }
                if (needed.isEmpty()) {
                    request.deny()
                    return
                }
                withPermissions(needed.toTypedArray()) { granted ->
                    if (granted) request.grant(request.resources) else request.deny()
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun configureCookies() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Instagram s'appuie sur des cookies poses par d'autres domaines du
            // groupe : sans ca, la session ne tient pas.
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    /** Injecte CSS + JS. Le script est idempotent, un double appel est sans effet. */
    private fun injectRules(view: WebView) {
        if (!rules.isUsable) return
        view.evaluateJavascript(rules.buildInjectionScript(), null)
    }

    // --- Navigation --------------------------------------------------------

    private fun isInstagram(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host == "instagram.com" || host.endsWith(".instagram.com")
    }

    /**
     * Chemins refuses avant meme le chargement.
     *
     * Cette liste double celle de `reelPathPrefixes` dans assets/insta_rules.js :
     * le JS attrape les navigations internes de la SPA, le Kotlin attrape les
     * vraies navigations pour eviter que le Reel s'affiche une fraction de
     * seconde. Si tu modifies l'une, modifie l'autre.
     */
    private fun isBlockedPath(path: String?): Boolean {
        val value = path ?: return false
        return value.startsWith("/reel/") || value.startsWith("/reels")
    }

    private fun openExternally(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Aucune app pour ouvrir $uri", e)
            false // on laisse la WebView s'en charger
        }
    }

    // --- Envoi de photos ---------------------------------------------------

    /**
     * Ouvre un selecteur qui propose a la fois la galerie et l'appareil photo,
     * ce qui couvre les deux façons de poster une story ou un post.
     */
    private fun openFileChooser(params: WebChromeClient.FileChooserParams) {
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val mimeTypes = params.acceptTypes.filter { it.isNotBlank() }.toTypedArray()

        val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.isEmpty()) "image/*" else mimeTypes.first()
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }

        // L'app declare la permission CAMERA : Android exige alors qu'elle soit
        // accordee pour lancer ACTION_IMAGE_CAPTURE, meme si c'est l'app Appareil
        // photo qui fait le travail.
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            launchChooser(pickIntent, buildCameraIntent())
        } else {
            withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                launchChooser(pickIntent, if (granted) buildCameraIntent() else null)
            }
        }
    }

    private fun launchChooser(pickIntent: Intent, cameraIntent: Intent?) {
        val chooser = Intent.createChooser(pickIntent, "Choisir une photo").apply {
            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }
        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Aucun selecteur de fichiers disponible", e)
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    /**
     * Prepare une prise de photo. L'app Appareil photo ne peut pas ecrire dans
     * notre dossier prive : on lui passe une URI de FileProvider, qui lui donne
     * un droit d'ecriture temporaire sur ce seul fichier.
     */
    private fun buildCameraIntent(): Intent? {
        val dir = File(getExternalFilesDir(null) ?: filesDir, CAPTURE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Dossier de captures impossible a creer")
            return null
        }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrElse {
            Log.w(TAG, "FileProvider en echec", it)
            return null
        }
        cameraOutputUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /**
     * Trois cas de retour : plusieurs fichiers (clipData), un seul (data), ou
     * une photo qui vient d'être prise (l'intent de retour est vide, le
     * resultat est dans le fichier qu'on avait fourni).
     */
    private fun extractSelectedUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK) return null

        data?.clipData?.let { clip ->
            val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            if (uris.isNotEmpty()) return uris.toTypedArray()
        }
        data?.data?.let { return arrayOf(it) }

        val captured = cameraOutputUri ?: return null
        // Photo annulée : le fichier existe mais reste vide.
        val hasContent = runCatching {
            contentResolver.openInputStream(captured)?.use { it.read() != -1 } ?: false
        }.getOrDefault(false)
        return if (hasContent) arrayOf(captured) else null
    }

    // --- Telechargements ---------------------------------------------------

    private fun downloadFile(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (!URLUtil.isNetworkUrl(url)) {
            // Instagram sert parfois des blob: ; DownloadManager ne sait pas les
            // traiter, et il n'existe pas de contournement simple.
            Toast.makeText(this, "Ce média ne peut pas être téléchargé", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            // Les medias Instagram ne sont servis qu'a une session authentifiee.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess {
                Toast.makeText(this, "Téléchargement de $fileName", Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Log.w(TAG, "Téléchargement refuse", it)
                Toast.makeText(this, "Téléchargement impossible", Toast.LENGTH_SHORT).show()
            }
    }

    // --- Permissions -------------------------------------------------------

    /** Demande les permissions manquantes, puis rejoue [action] avec le verdict. */
    private fun withPermissions(permissions: Array<String>, action: (Boolean) -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action(true)
            return
        }
        pendingPermissionAction = action
        permissionsLauncher.launch(missing.toTypedArray())
    }

    private companion object {
        const val HOME_URL = "https://www.instagram.com/"

        /** Sous-dossier de getExternalFilesDir, declare dans res/xml/file_paths.xml. */
        const val CAPTURE_DIR = "captures"

        /**
         * User agent d'un Chrome mobile recent. A rafraichir de temps en temps :
         * ouvre https://www.whatismybrowser.com/detect/what-is-my-user-agent/
         * dans le Chrome du téléphone et recopie la chaine.
         */
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}

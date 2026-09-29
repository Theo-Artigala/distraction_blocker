package com.theo.distractionblocker.insta

import android.content.Context
import android.util.Log
import org.json.JSONObject

private const val TAG = "InstaRules"

/**
 * Lit les deux fichiers de regles dans assets/ et fabrique le bout de
 * JavaScript a injecter dans la WebView.
 *
 * Tout le contenu (selecteurs, libelles, strategies) vit dans
 * assets/insta_rules.css et assets/insta_rules.js. Cette classe ne fait que du
 * transport : elle ne connait aucun selecteur, et il n'y a donc jamais besoin
 * de toucher au Kotlin quand Instagram change son DOM.
 */
class InstaRules(context: Context) {

    private val css: String = context.readAsset(FILE_CSS)
    private val js: String = context.readAsset(FILE_JS)

    /**
     * Script complet a passer a evaluateJavascript.
     *
     * Le CSS est transporte dans une variable JS plutot qu'injecte directement :
     * ca evite tout probleme d'echappement, et c'est le JS qui decide OU et QUAND
     * poser la balise style (il la repose si React nettoie le head).
     *
     * [JSONObject.quote] produit un litteral de chaine JS valide, guillemets
     * compris, en echappant sauts de ligne, guillemets et antislashs. Sans ca,
     * la moindre apostrophe dans le CSS casserait le script injecte.
     */
    fun buildInjectionScript(): String = buildString {
        append("window.__DB_CSS__ = ")
        append(JSONObject.quote(css))
        append(";\n")
        append(js)
    }

    /** true si les deux fichiers de regles ont bien ete lus. */
    val isUsable: Boolean get() = js.isNotBlank()

    private companion object {
        const val FILE_CSS = "insta_rules.css"
        const val FILE_JS = "insta_rules.js"

        fun Context.readAsset(name: String): String =
            runCatching { assets.open(name).bufferedReader().use { it.readText() } }
                .onFailure { Log.e(TAG, "Lecture de l'asset $name impossible", it) }
                .getOrDefault("")
    }
}

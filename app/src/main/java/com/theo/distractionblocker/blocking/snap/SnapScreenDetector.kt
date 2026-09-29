package com.theo.distractionblocker.blocking.snap

import android.view.accessibility.AccessibilityNodeInfo
import com.theo.distractionblocker.blocking.DetectionConfig

/**
 * Toute la reconnaissance de l'onglet Spotlight est ici, et nulle part ailleurs.
 *
 * Je ne peux pas connaitre les identifiants reels : ils changent a chaque
 * version de Snapchat et ne sont pas documentes. Le README explique comment les
 * relever avec `adb shell uiautomator dump`, et ils se configurent dans
 * detection_config.json (voir [DetectionConfig]).
 *
 * Trois strategies, de la plus fiable a la plus risquee :
 *
 * 1. [DetectionConfig.SnapConfig.blockedScreenViewIds] : un identifiant de vue
 *    qui n'existe que sur la page Spotlight. Sa simple presence suffit. C'est la
 *    strategie a privilegier : zero faux positif.
 * 2. [DetectionConfig.SnapConfig.blockedScreenTabViewIds] : l'identifiant du bouton
 *    d'onglet Spotlight, present en permanence dans la barre du bas. On ne
 *    declenche que s'il est marque "selected".
 * 3. [DetectionConfig.SnapConfig.blockedScreenContentDescriptions] puis le texte
 *    (si useTextHeuristic est vrai) : on parcourt l'arbre. Pratique quand
 *    Snapchat n'expose aucun resource-id utile, mais un libelle "Spotlight"
 *    peut trainer ailleurs que sur la page Spotlight, d'ou les faux positifs.
 *    Desactive par defaut.
 */
object SnapScreenDetector {

    /**
     * Profondeur maximale d'exploration de l'arbre. La hierarchie de Snapchat
     * est profonde ; sans borne, on paierait un parcours complet a chaque
     * evenement de contenu, qui arrivent par dizaines par seconde.
     */
    private const val MAX_DEPTH = 18

    /**
     * @param root racine de la fenetre active (rootInActiveWindow).
     * @return true si l'ecran affiche, selon la config, l'onglet Spotlight.
     */
    fun isOnBlockedScreen(root: AccessibilityNodeInfo?, config: DetectionConfig.SnapConfig): Boolean {
        if (root == null) return false

        // Strategie 1 : une vue qui n'existe que sur Spotlight.
        for (viewId in config.blockedScreenViewIds) {
            if (root.findAccessibilityNodeInfosByViewId(viewId).isNotEmpty()) return true
        }

        // Strategie 2 : l'onglet Spotlight, mais seulement s'il est selectionne.
        for (viewId in config.blockedScreenTabViewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.any { it.isSelected || it.isCheckedSafe() }) return true
        }

        // Strategie 3 : content-desc, puis texte si l'heuristique est activee.
        val descriptions = config.blockedScreenContentDescriptions
        val texts = if (config.useTextHeuristic) config.blockedScreenTexts else emptyList()
        if (descriptions.isEmpty() && texts.isEmpty()) return false

        return findMatchingNode(root, descriptions, texts, depth = 0)
    }

    /**
     * Snapchat marque parfois l'onglet actif avec "checked" plutot qu'avec
     * "selected". On teste les deux, mais seulement pour les noeuds cochables :
     * isChecked vaut false sur tout le reste, ce qui est deja le comportement
     * voulu.
     */
    private fun AccessibilityNodeInfo.isCheckedSafe(): Boolean = isCheckable && isChecked

    /** Parcours en profondeur borne, a la recherche d'un libelle correspondant. */
    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        descriptions: List<String>,
        texts: List<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_DEPTH) return false

        val desc = node.contentDescription?.toString()
        if (desc != null && descriptions.any { desc.contains(it, ignoreCase = true) }) {
            // Un content-desc "Spotlight" sur un onglet NON selectionne signifie
            // "voici le bouton pour y aller", pas "tu y es". On exige donc la
            // selection. Pour un matching plus laxiste, utiliser l'heuristique
            // de texte.
            if (node.isSelected || node.isCheckedSafe()) return true
        }

        val text = node.text?.toString()
        if (text != null && texts.any { text.contains(it, ignoreCase = true) }) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findMatchingNode(child, descriptions, texts, depth + 1)) return true
        }
        return false
    }
}

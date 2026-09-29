/* ===========================================================================
 * insta_rules.js — toute la logique de nettoyage d'Instagram.
 *
 * Le code Kotlin ne fait que trois choses :
 *   1. lire insta_rules.css et le poser dans window.__DB_CSS__
 *   2. lire ce fichier et l'evaluer
 *   3. bloquer /reel et /reels au niveau des vraies navigations
 * Tout le reste (quels selecteurs, quels mots, quelle strategie) est ici.
 *
 * ---------------------------------------------------------------------------
 * COMMENT RETROUVER LES BONS SELECTEURS QUAND INSTAGRAM CHANGE SON DOM
 * ---------------------------------------------------------------------------
 * 1. Branche le telephone en USB, active le debogage USB.
 * 2. Sur le PC, ouvre Chrome et va sur chrome://inspect/#devices
 * 3. Lance la WebView Instagram depuis l'app. Elle apparait dans la liste
 *    (le debogage WebView est active par le Kotlin uniquement en build debug).
 * 4. Clique "inspect" : tu as les DevTools complets sur la page du telephone.
 * 5. Dans l'onglet Elements, clique sur la cible avec l'inspecteur.
 * 6. Dans la Console, teste ton selecteur AVANT de l'ecrire ici :
 *       document.querySelectorAll('ton selecteur').length
 *    Puis verifie visuellement ce que tu attrapes :
 *       document.querySelectorAll('ton selecteur')
 *         .forEach(n => n.style.outline = '3px solid red')
 * 7. Tu peux aussi rejouer tout ce fichier a chaud dans la console pour
 *    iterer sans reinstaller l'APK : colle son contenu, mais definis d'abord
 *    window.__DB_CSS__ = '' pour eviter une erreur.
 *
 * Ordre de preference pour un selecteur, du plus au moins durable :
 *   aria-label / role  >  href  >  data-*  >  structure (div > div)  >  classe
 * Les classes du type x1i10hfl ou _aacl sont generees : elles changent a chaque
 * deploiement d'Instagram. Ne t'appuie dessus qu'en dernier recours.
 * ========================================================================= */

(function () {
    'use strict';

    // Garde-fou : si le script est evalue deux fois sur la meme page (ce qui
    // arrive, onPageFinished peut se declencher plusieurs fois), on ne
    // reinstalle pas les observateurs.
    if (window.__dbRulesInstalled) {
        return;
    }
    window.__dbRulesInstalled = true;

    /* =======================================================================
     * CONFIGURATION — c'est la seule partie que tu devrais avoir a modifier.
     * ===================================================================== */
    var CONFIG = {

        /** Mets une fonctionnalite a false si elle casse ou te gene. */
        features: {
            hideReels: true,
            hideSponsored: true,
            forceFollowingFeed: true,
            hideReelsInExplore: true
        },

        /**
         * true : chaque passe journalise ce qu'elle attrape, et un rapport de
         * diagnostic est emis a chaque changement d'URL. Visible cote PC avec
         * adb logcat -s InstaWebView. A repasser a false une fois les
         * selecteurs cales : ca bavarde beaucoup.
         */
        debug: true,

        /* --- Redirection des Reels ------------------------------------------
         * Prefixes de chemin consideres comme du Reels. Teste avec
         * location.pathname.indexOf(prefixe) === 0
         * Le Kotlin applique la meme liste aux vraies navigations ; ici on
         * couvre les navigations internes de l'application React (pushState),
         * que le Kotlin ne voit pas. */
        reelPathPrefixes: ['/reel/', '/reels'],

        /** Ou l'on renvoie quand on atterrit sur du Reels. */
        redirectTo: '/',

        /* --- Posts sponsorises ---------------------------------------------
         * On cherche ces libelles dans le texte des articles. Instagram affiche
         * le libelle dans la langue du compte, d'ou la liste.
         * Compare en minuscules, accents compris : ajoute les variantes que tu
         * vois passer. */
        sponsoredLabels: [
            'sponsorisé',
            'sponsorise',
            'sponsored',
            'publicité',
            'publicite',
            'partenariat rémunéré',
            'paid partnership'
        ],

        /* Conteneur d'un post dans le fil. Instagram utilise <article> depuis
         * longtemps ; role="presentation" est la variante rencontree sur
         * certaines versions du web mobile. */
        postContainerSelector: 'article, div[role="presentation"] > div > article',

        /* Ou chercher le libelle sponsorise a l'interieur d'un post. On se
         * limite a l'entete pour ne pas masquer un post dont la LEGENDE
         * contient le mot "sponsorise" : un post de quelqu'un que tu suis qui
         * dit "ceci n'est pas sponsorise" doit rester visible.
         * Si le libelle n'est plus dans l'entete, mets 'self' pour chercher
         * dans tout le post. */
        sponsoredScopeSelector: 'header',

        /* --- Fil "Abonnements" ---------------------------------------------
         * Deux strategies, essayees dans cet ordre.
         *
         * 1. URL : ?variant=following sur la page d'accueil. Ce parametre a
         *    existe sur instagram.com ; il peut avoir disparu. Si tu constates
         *    qu'il ne fait plus rien, passe useVariantUrl a false pour eviter
         *    un rechargement inutile a chaque visite de l'accueil.
         * 2. Clic : on ouvre le selecteur de fil en haut de l'accueil et on
         *    clique l'entree "Abonnements". Les libelles sont cherches en
         *    "contient", insensible a la casse. */
        followingFeed: {
            useVariantUrl: true,
            variantQuery: 'variant=following',

            /* Bouton qui ouvre le menu de choix du fil (l'entete de l'accueil). */
            switcherSelectors: [
                'div[role="button"][aria-label*="fil"]',
                'div[role="button"][aria-label*="feed"]',
                'svg[aria-label="Sélecteur de fil d\'actualité"]',
                'svg[aria-label="Feed control"]'
            ],

            /* Entree "Abonnements" dans le menu ouvert. */
            followingLabels: [
                'abonnements',
                'following',
                'comptes suivis'
            ],

            /* On n'essaie le clic qu'une fois par chargement de page : sans ca,
             * un echec de detection relancerait des clics en boucle. */
            maxClickAttempts: 3
        },

        /* --- Onglet Explorer / Recherche ------------------------------------
         * Deux strategies, exclusives.
         *
         * keepOnlySearch (recommandee) : on ne garde QUE la barre de recherche
         * et on masque tout le reste de la page. On part de la barre, on remonte
         * jusqu'au body, et a chaque etage on masque les freres et soeurs.
         * L'enorme avantage : ca ne demande qu'UN selecteur (la barre), au lieu
         * de connaitre la structure de la grille. Instagram peut refondre son
         * Explorer, tant que la barre reste trouvable, ca tient.
         *
         * Sinon, repli sur le masquage des vignettes Reels une par une, qui
         * demande de deviner de combien de parents remonter : fragile. */
        explore: {
            keepOnlySearch: true,

            /* Chemins consideres comme l'onglet Explorer. */
            pathPrefixes: ['/explore'],

            /* La barre de recherche. Le premier qui matche gagne, et le
             * selecteur retenu est journalise pour que tu saches lequel sert. */
            searchSelectors: [
                'input[aria-label*="echerch"]',
                'input[placeholder*="echerch"]',
                'input[aria-label*="earch"]',
                'input[placeholder*="earch"]',
                '[role="search"] input',
                'input[type="text"]',
                'input'
            ],

            /* Jamais masques : sans ca on perdrait la navigation et on
             * resterait coince sur Explorer. */
            keepSelectors: [
                'nav',
                '[role="navigation"]',
                'header'
            ],

            /* Regle generique 1 : preserver ce qui est positionne en fixed ou
             * sticky. La barre du bas et l'entete sont ancres a l'ecran, le
             * contenu defilant ne l'est pas. Ca reconnait la barre quelle que
             * soit sa balise, sans dependre d'un selecteur de structure. */
            keepFixedElements: true,

            /* Regle generique 2 : preserver ce qui contient au moins
             * [minNavLinks] liens vers les destinations principales. Une barre
             * de navigation se reconnait a ce qu'elle pointe vers plusieurs
             * sections a la fois, la grille de contenu non. */
            navLinkSelectors: [
                'a[href="/"]',
                'a[href^="/explore"]',
                'a[href^="/direct"]',
                'a[href^="/reels"]'
            ],
            minNavLinks: 2
        },

        /* --- Reel ouvert depuis un DM ----------------------------------------
         * On garde la lecture (un pote t'envoie un Reel, tu dois pouvoir le
         * regarder) mais on coupe le defilement vers le suivant, qui est la
         * vraie porte d'entree du scroll infini.
         *
         * Aucun selecteur de structure ici non plus : on part de la balise
         * <video> qui occupe l'ecran, on remonte jusqu'au premier conteneur
         * reellement defilant, et on le verrouille. */
        reelViewer: {
            lockScroll: true,

            /* Contextes ou le verrou s'applique. Volontairement large : le
             * chemin exact d'un Reel partage en DM est journalise au verrouillage,
             * il suffira de resserrer cette liste ensuite. */
            pathPrefixes: ['/reel', '/reels', '/direct'],

            /* Fraction de la hauteur d'ecran que la video doit couvrir pour
             * qu'on considere etre en lecture plein ecran, et non devant une
             * vignette dans un fil de discussion. */
            minViewportRatio: 0.6
        },

        /* Repli : vignette Reel dans la grille (utilise seulement si
         * explore.keepOnlySearch vaut false). */
        exploreReelLinkSelector: 'a[href^="/reel/"], a[href*="/reel/"]',

        /* Remontee depuis le lien pour trouver la cellule a masquer. */
        exploreCellDepth: 3
    };

    /* =======================================================================
     * Fin de la configuration. En dessous, la mecanique.
     * ===================================================================== */

    var HIDDEN_CLASS = 'db-hidden';

    function log() {
        if (!CONFIG.debug) {
            return;
        }
        var args = Array.prototype.slice.call(arguments);
        args.unshift('[db]');
        console.log.apply(console, args);
    }

    /** Normalise pour comparer : minuscules et espaces resserres. */
    function normalize(text) {
        return (text || '').toLowerCase().replace(/\s+/g, ' ').trim();
    }

    /** Masque un noeud en le marquant, sans jamais le supprimer du DOM. */
    function hide(node, reason) {
        if (!node || node.classList.contains(HIDDEN_CLASS)) {
            return;
        }
        node.classList.add(HIDDEN_CLASS);
        log('masque', reason, node);
    }

    /* --- Injection du CSS -------------------------------------------------
     * Le Kotlin a pose le contenu de insta_rules.css dans window.__DB_CSS__.
     * On le pose dans un <style> plutot que d'utiliser des styles inline :
     * ca survit aux re-renders de React, qui reecrivent les attributs style. */
    function injectCss() {
        if (document.getElementById('db-style')) {
            return;
        }
        var css = window.__DB_CSS__;
        if (!css) {
            return;
        }
        var style = document.createElement('style');
        style.id = 'db-style';
        style.textContent = css;
        (document.head || document.documentElement).appendChild(style);
        log('css injecte');
    }

    /* --- Redirection Reels ------------------------------------------------ */
    function isReelPath(pathname) {
        for (var i = 0; i < CONFIG.reelPathPrefixes.length; i++) {
            if (pathname.indexOf(CONFIG.reelPathPrefixes[i]) === 0) {
                return true;
            }
        }
        return false;
    }

    function redirectAwayFromReels() {
        if (!CONFIG.features.hideReels) {
            return false;
        }
        if (!isReelPath(location.pathname)) {
            return false;
        }
        log('chemin Reels detecte, redirection', location.pathname);
        // replace() et pas assign() : on ne laisse pas d'entree dans
        // l'historique, sinon le bouton retour renvoie sur le Reel.
        location.replace(CONFIG.redirectTo);
        return true;
    }

    /* --- Posts sponsorises ----------------------------------------------- */
    function looksSponsored(post) {
        var scope = post;
        if (CONFIG.sponsoredScopeSelector !== 'self') {
            scope = post.querySelector(CONFIG.sponsoredScopeSelector);
            if (!scope) {
                return false;
            }
        }
        var text = normalize(scope.innerText);
        if (!text) {
            return false;
        }
        for (var i = 0; i < CONFIG.sponsoredLabels.length; i++) {
            if (text.indexOf(normalize(CONFIG.sponsoredLabels[i])) !== -1) {
                return true;
            }
        }
        return false;
    }

    function hideSponsoredPosts() {
        if (!CONFIG.features.hideSponsored) {
            return;
        }
        var posts = document.querySelectorAll(CONFIG.postContainerSelector);
        for (var i = 0; i < posts.length; i++) {
            if (!posts[i].classList.contains(HIDDEN_CLASS) && looksSponsored(posts[i])) {
                hide(posts[i], 'sponsorise');
            }
        }
    }

    /* --- Explorer : ne garder que la barre de recherche ------------------- */

    /** Marque distinctive : l'isolation doit pouvoir etre annulee, pas le reste. */
    var ISOLATE_ATTR = 'data-db-isolate';
    var exploreReported = false;

    /**
     * Passe a true des que l'utilisateur touche la recherche.
     *
     * Pourquoi c'est indispensable : Instagram affiche ses resultats de
     * recherche dans un conteneur qui n'est PAS un ancetre de la barre. Comme
     * l'isolation masque tout ce qui n'est pas sur le chemin de la barre, et que
     * l'observateur de mutations masque aussi les noeuds ajoutes apres coup, le
     * panneau de resultats etait masque des son apparition : la barre semblait
     * morte.
     *
     * On leve donc l'isolation des le premier contact avec la recherche. Le but
     * etait de te soustraire la grille de Reels, pas de t'empecher de chercher.
     * L'isolation revient a la navigation suivante.
     */
    var searchActive = false;

    /** L'element touche appartient-il a la zone de recherche ? */
    function isSearchTarget(element) {
        if (!element || !element.closest) {
            return false;
        }
        try {
            return element.closest(
                'input, textarea, [role="search"], [aria-label*="echerch"], [aria-label*="earch"]'
            ) !== null;
        } catch (e) {
            return false;
        }
    }

    function markSearchActive(origin) {
        if (searchActive) {
            return;
        }
        searchActive = true;
        clearIsolation();
        console.log('[db] explore: recherche activee (' + origin + '), isolation levee');
    }

    function isExplorePath() {
        var prefixes = CONFIG.explore.pathPrefixes;
        for (var i = 0; i < prefixes.length; i++) {
            if (location.pathname.indexOf(prefixes[i]) === 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ce noeud est-il, ou contient-il, quelque chose qu'on doit preserver ?
     *
     * Trois regles, de la plus explicite a la plus generique. Les deux
     * dernieres existent parce que la barre de navigation d'Instagram n'est ni
     * un <nav> ni un role="navigation" : se fier aux seuls selecteurs de
     * structure la faisait disparaitre, et on se retrouvait coince sur
     * Explorer sans moyen d'en sortir.
     */
    function containsKeeper(node, keepSelectors) {
        // 1. Selecteurs explicites.
        for (var i = 0; i < keepSelectors.length; i++) {
            try {
                if (node.matches && node.matches(keepSelectors[i])) {
                    return true;
                }
                if (node.querySelector && node.querySelector(keepSelectors[i])) {
                    return true;
                }
            } catch (e) {
                // Selecteur invalide : on l'ignore plutot que de tout casser.
            }
        }

        // 2. Element ancre a l'ecran (barre du bas, entete).
        if (CONFIG.explore.keepFixedElements && isAnchoredToViewport(node)) {
            return true;
        }

        // 3. Element qui pointe vers plusieurs destinations principales.
        if (countNavLinks(node) >= CONFIG.explore.minNavLinks) {
            return true;
        }

        return false;
    }

    /**
     * Le noeud, ou l'un de ses enfants directs, est-il en position fixed ou
     * sticky ? On se limite a deux niveaux : getComputedStyle est couteux, et
     * la barre est toujours haut placee dans l'arbre.
     */
    function isAnchoredToViewport(node) {
        var candidates = [node];
        for (var i = 0; i < node.children.length && i < 8; i++) {
            candidates.push(node.children[i]);
        }
        for (var j = 0; j < candidates.length; j++) {
            try {
                var position = window.getComputedStyle(candidates[j]).position;
                if (position === 'fixed' || position === 'sticky') {
                    return true;
                }
            } catch (e) {
                // Noeud detache du document : rien a preserver.
            }
        }
        return false;
    }

    /** Nombre de destinations principales distinctes atteignables depuis ce noeud. */
    function countNavLinks(node) {
        if (!node.querySelector) {
            return 0;
        }
        var selectors = CONFIG.explore.navLinkSelectors;
        var found = 0;
        for (var i = 0; i < selectors.length; i++) {
            try {
                if (node.querySelector(selectors[i])) {
                    found++;
                }
            } catch (e) {
                // selecteur invalide
            }
        }
        return found;
    }

    /**
     * Masque tout sauf [node] et ses ancetres. On remonte etage par etage en
     * masquant les freres et soeurs : a l'arrivee, seul le chemin body -> node
     * reste visible.
     */
    function isolateElement(node, keepSelectors) {
        var current = node;
        while (current && current !== document.body && current.parentElement) {
            var parent = current.parentElement;
            var children = parent.children;
            for (var i = 0; i < children.length; i++) {
                var child = children[i];
                if (child === current || containsKeeper(child, keepSelectors)) {
                    continue;
                }
                if (!child.classList.contains(HIDDEN_CLASS)) {
                    child.classList.add(HIDDEN_CLASS);
                    child.setAttribute(ISOLATE_ATTR, '1');
                }
            }
            current = parent;
        }
    }

    /**
     * Annule l'isolation. Indispensable : Instagram est une SPA, elle reutilise
     * les memes noeuds d'une page a l'autre. Sans ca, quitter Explorer laisserait
     * la moitie du site masquee.
     */
    function clearIsolation() {
        var nodes = document.querySelectorAll('[' + ISOLATE_ATTR + '="1"]');
        for (var i = 0; i < nodes.length; i++) {
            nodes[i].classList.remove(HIDDEN_CLASS);
            nodes[i].removeAttribute(ISOLATE_ATTR);
        }
    }

    function applyExplore() {
        if (!CONFIG.features.hideReelsInExplore) {
            return;
        }
        if (!isExplorePath()) {
            clearIsolation();
            return;
        }
        if (!CONFIG.explore.keepOnlySearch) {
            hideReelsInExploreGrid();
            return;
        }
        if (searchActive) {
            return;
        }

        var selectors = CONFIG.explore.searchSelectors;
        var input = null;
        var used = null;
        for (var i = 0; i < selectors.length && !input; i++) {
            try {
                input = document.querySelector(selectors[i]);
                used = selectors[i];
            } catch (e) {
                // selecteur invalide, on passe au suivant
            }
        }
        if (!input) {
            if (!exploreReported) {
                console.log('[db] explore: AUCUNE barre de recherche trouvee');
                exploreReported = true;
            }
            return;
        }
        if (!exploreReported) {
            console.log('[db] explore: barre trouvee via ' + used);
            exploreReported = true;
        }
        isolateElement(input, CONFIG.explore.keepSelectors);
    }

    /* Repli historique : masquer les vignettes Reels une par une. */
    function hideReelsInExploreGrid() {
        var links = document.querySelectorAll(CONFIG.exploreReelLinkSelector);
        for (var i = 0; i < links.length; i++) {
            // On remonte de quelques parents pour masquer la cellule entiere
            // de la grille, pas juste le lien (sinon il reste un trou blanc).
            var cell = links[i];
            for (var up = 0; up < CONFIG.exploreCellDepth && cell.parentElement; up++) {
                cell = cell.parentElement;
            }
            hide(cell, 'vignette Reel');
        }
    }

    /* --- Reel de DM : lecture oui, defilement non -------------------------- */

    var SCROLL_LOCK_ATTR = 'data-db-scrolllock';
    var scrollLockReported = false;

    function isReelViewerPath() {
        var prefixes = CONFIG.reelViewer.pathPrefixes;
        for (var i = 0; i < prefixes.length; i++) {
            if (location.pathname.indexOf(prefixes[i]) === 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Premier ancetre reellement defilant. "Reellement" compte : beaucoup de
     * conteneurs declarent overflow:auto sans jamais deborder, les verrouiller
     * ne servirait a rien et masquerait le vrai coupable.
     */
    function findScrollableAncestor(node) {
        var current = node.parentElement;
        while (current && current !== document.documentElement) {
            try {
                var overflowY = window.getComputedStyle(current).overflowY;
                var scrolls = overflowY === 'auto' || overflowY === 'scroll';
                if (scrolls && current.scrollHeight > current.clientHeight + 10) {
                    return current;
                }
            } catch (e) {
                // noeud detache
            }
            current = current.parentElement;
        }
        return null;
    }

    /** Petite description lisible d'un noeud, pour les logs de calibration. */
    function describeNode(node) {
        return node.tagName +
            (node.id ? '#' + node.id : '') +
            (node.className && typeof node.className === 'string'
                ? '.' + node.className.trim().split(/\s+/).slice(0, 2).join('.')
                : '');
    }

    function lockScroller(element) {
        if (element.getAttribute(SCROLL_LOCK_ATTR)) {
            return;
        }
        element.setAttribute(SCROLL_LOCK_ATTR, '1');
        // setProperty avec 'important' : React reecrit les attributs style a
        // chaque rendu, une affectation simple ne tiendrait pas.
        element.style.setProperty('overflow-y', 'hidden', 'important');
        // touch-action coupe le geste de defilement sans desactiver les taps,
        // donc la lecture, la pause et la fermeture continuent de repondre.
        element.style.setProperty('touch-action', 'none', 'important');
        console.log('[db] reel: defilement verrouille sur ' + describeNode(element) +
            ' (path=' + location.pathname + ')');
    }

    function unlockAllScrollers() {
        var locked = document.querySelectorAll('[' + SCROLL_LOCK_ATTR + '="1"]');
        for (var i = 0; i < locked.length; i++) {
            locked[i].style.removeProperty('overflow-y');
            locked[i].style.removeProperty('touch-action');
            locked[i].removeAttribute(SCROLL_LOCK_ATTR);
        }
    }

    function applyReelViewer() {
        if (!CONFIG.reelViewer.lockScroll || !isReelViewerPath()) {
            unlockAllScrollers();
            return;
        }

        var videos = document.querySelectorAll('video');
        var lockedSomething = false;
        for (var i = 0; i < videos.length; i++) {
            var rect = videos[i].getBoundingClientRect();
            if (rect.height < window.innerHeight * CONFIG.reelViewer.minViewportRatio) {
                continue; // vignette dans un fil, pas une lecture plein ecran
            }
            var scroller = findScrollableAncestor(videos[i]);
            if (scroller) {
                lockScroller(scroller);
                lockedSomething = true;
            }
        }

        if (!lockedSomething && !scrollLockReported && videos.length > 0) {
            scrollLockReported = true;
            console.log('[db] reel: video plein ecran detectee mais AUCUN conteneur ' +
                'defilant trouve (path=' + location.pathname +
                ', videos=' + videos.length + ')');
        }
    }

    /* --- Fil Abonnements ------------------------------------------------- */
    var followingAttempts = 0;

    function isHomeFeed() {
        return location.pathname === '/' || location.pathname === '';
    }

    /** Strategie 1 : ajouter ?variant=following a l'URL de l'accueil. */
    function tryFollowingViaUrl() {
        var cfg = CONFIG.followingFeed;
        if (!cfg.useVariantUrl) {
            return false;
        }
        if (location.search.indexOf(cfg.variantQuery) !== -1) {
            return false; // deja fait
        }
        var separator = location.search ? '&' : '?';
        log('bascule vers le fil Abonnements via URL');
        location.replace(location.pathname + location.search + separator + cfg.variantQuery);
        return true;
    }

    /**
     * Strategie 2 : simuler le clic sur le selecteur de fil, puis sur
     * "Abonnements". Le menu s'ouvre de façon asynchrone, d'ou le setTimeout.
     */
    function tryFollowingViaClick() {
        var cfg = CONFIG.followingFeed;
        if (followingAttempts >= cfg.maxClickAttempts) {
            return;
        }
        followingAttempts++;

        // L'entree "Abonnements" est-elle deja dans le DOM (menu ouvert) ?
        if (clickFollowingEntry()) {
            return;
        }

        var switcher = null;
        for (var i = 0; i < cfg.switcherSelectors.length && !switcher; i++) {
            switcher = document.querySelector(cfg.switcherSelectors[i]);
        }
        if (!switcher) {
            log('selecteur de fil introuvable');
            return;
        }
        // Un svg n'est pas cliquable : on clique le bouton qui le contient.
        var clickable = switcher.closest('div[role="button"], button') || switcher;
        clickable.click();
        log('menu de fil ouvert, recherche de l\'entree Abonnements');
        setTimeout(clickFollowingEntry, 300);
    }

    function clickFollowingEntry() {
        var labels = CONFIG.followingFeed.followingLabels;
        // On cherche parmi les elements de menu, pas dans toute la page, pour
        // ne pas cliquer un lien "Abonnements" d'un profil.
        var candidates = document.querySelectorAll(
            '[role="menuitem"], [role="option"], [role="radio"], [role="button"]'
        );
        for (var i = 0; i < candidates.length; i++) {
            var text = normalize(candidates[i].innerText);
            if (!text) {
                continue;
            }
            for (var j = 0; j < labels.length; j++) {
                if (text.indexOf(normalize(labels[j])) !== -1) {
                    candidates[i].click();
                    log('fil Abonnements selectionne');
                    return true;
                }
            }
        }
        return false;
    }

    function applyFollowingFeed() {
        if (!CONFIG.features.forceFollowingFeed || !isHomeFeed()) {
            return;
        }
        if (tryFollowingViaUrl()) {
            return; // la page va se recharger, inutile de continuer
        }
        tryFollowingViaClick();
    }

    /**
     * Compte ce que chaque selecteur attrape sur la page courante.
     *
     * C'est l'outil de calibration : si 'posts' vaut 0 sur le fil, le selecteur
     * de conteneur de post est casse et le masquage des publicites ne peut pas
     * fonctionner. Si 'switcher' vaut 0 sur l'accueil, c'est le selecteur du
     * choix de fil qui a change.
     */
    function diagnostics() {
        if (!CONFIG.debug) {
            return;
        }
        var switcherCount = 0;
        CONFIG.followingFeed.switcherSelectors.forEach(function (sel) {
            try {
                switcherCount += document.querySelectorAll(sel).length;
            } catch (e) {
                console.log('[db] selecteur invalide: ' + sel);
            }
        });
        console.log('[db] diag ' + JSON.stringify({
            path: location.pathname,
            query: location.search,
            posts: document.querySelectorAll(CONFIG.postContainerSelector).length,
            reelLinks: document.querySelectorAll(CONFIG.exploreReelLinkSelector).length,
            switcher: switcherCount,
            hidden: document.querySelectorAll('.' + HIDDEN_CLASS).length,
            styleInjected: !!document.getElementById('db-style')
        }));
    }

    /* --- Passe complete --------------------------------------------------- */
    function apply() {
        injectCss();
        if (redirectAwayFromReels()) {
            return; // on quitte la page, pas la peine de nettoyer
        }
        hideSponsoredPosts();
        applyExplore();
        applyReelViewer();
        applyFollowingFeed();
    }

    /* --- Declencheurs ----------------------------------------------------- */

    /**
     * Instagram est une SPA : apres le premier chargement, changer de page ne
     * declenche ni load ni onPageFinished cote Kotlin. Deux sondes sont donc
     * necessaires.
     */

    // Sonde 1 : le DOM bouge (scroll infini, nouveaux posts, menus).
    // On passe par requestAnimationFrame pour ne pas relancer une passe
    // complete a chaque mutation : le fil en emet des centaines par seconde.
    var scheduled = false;
    var observer = new MutationObserver(function () {
        if (scheduled) {
            return;
        }
        scheduled = true;
        requestAnimationFrame(function () {
            scheduled = false;
            apply();
        });
    });
    observer.observe(document.documentElement, {
        childList: true,
        subtree: true
    });

    // Sonde 2 : l'URL change sans rechargement. On enveloppe pushState et
    // replaceState, que React Router utilise, et on ecoute popstate pour le
    // bouton retour. C'est l'equivalent web d'un hook de navigation.
    function onUrlChange() {
        followingAttempts = 0; // nouvelle page, on a droit a de nouveaux essais
        exploreReported = false;
        searchActive = false;
        scrollLockReported = false;
        apply();
        // Differe : la page vient de changer, le contenu n'est pas encore rendu.
        setTimeout(diagnostics, 1500);
    }

    ['pushState', 'replaceState'].forEach(function (name) {
        var original = history[name];
        history[name] = function () {
            var result = original.apply(this, arguments);
            onUrlChange();
            return result;
        };
    });
    window.addEventListener('popstate', onUrlChange);

    // capture=true : on veut etre prevenu avant que React n'intercepte
    // l'evenement. focusin ET click, parce que la zone tactile de la recherche
    // n'est pas toujours l'input lui-meme (Instagram met parfois un div
    // par-dessus qui ouvre un panneau de recherche plein ecran).
    document.addEventListener('focusin', function (event) {
        if (isExplorePath() && isSearchTarget(event.target)) {
            markSearchActive('focus');
        }
    }, true);
    document.addEventListener('click', function (event) {
        if (isExplorePath() && isSearchTarget(event.target)) {
            markSearchActive('clic');
        }
    }, true);

    // Premiere passe immediate.
    apply();
    log('regles installees');
    setTimeout(diagnostics, 1500);
})();

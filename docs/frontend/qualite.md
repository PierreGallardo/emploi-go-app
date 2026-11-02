# Qualité frontend

Ce guide aide à choisir les tests et les vérifications d'une modification
frontend. L'interface doit rester compréhensible et utilisable lors d'un succès,
d'une absence de données ou d'un échec.

Les [commandes frontend](developpement.md#commandes) et le
[guide CI](../ci.md) décrivent l'exécution des contrôles et leurs rapports.
Choisir les scénarios selon le comportement modifié et les risques du changement.

## Principes

Les [principes communs](../backend/qualite.md#principes) valent aussi pour le frontend : un comportement par test, assertions précises, déterminisme, bon niveau sans doublon, mocks réservés à l'externe. Avec Vitest et [Testing Library](https://testing-library.com/docs/guiding-principles/), ils se traduisent ainsi :

- Nommer chaque test par l'action et son résultat. Pas de boucle ni de `if` : plusieurs cas se décrivent avec `it.each` et un libellé par cas.
- Tester ce que l'utilisateur voit et fait, pas l'état interne du composant ni des classes de style sans enjeu visuel. Ne pas tester une bibliothèque tierce (React Hook Form, Zod, Radix, MapLibre) : tester notre usage.
- Chercher les éléments selon la [priorité de Testing Library](https://testing-library.com/docs/queries/about/#priority) : `getByRole` avec son nom accessible, puis `getByLabelText`, `getByText` et, en dernier recours, `getByTestId`. Jamais de sélecteur CSS, sauf `document.querySelectorAll('title')` : `<title>` n'a pas de rôle ARIA.
- Simuler l'utilisateur avec `userEvent`, pas `fireEvent`.
- Choisir la requête selon l'attente : `findBy*` pour un élément qui apparaît de façon asynchrone, `queryBy*` pour vérifier une absence, `getBy*` pour un élément déjà présent.
- Vérifier avec les assertions précises de `jest-dom` et de Vitest (`toHaveValue`, `toBeDisabled`, `toEqual`, `toMatchObject`). Un `expect(a && b).toBe(true)` n'affiche que `false` en cas d'échec.
- Contrôler le temps avec des timers factices : aucune pause réelle.
- Pas de snapshot. Calculer une valeur liée aux données depuis sa source au lieu de la copier.

## Répartir les tests

| Niveau                  | Outils                                                         | Comportements à vérifier                                                                                         |
| ----------------------- | -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Fonctions et schémas    | Vitest                                                         | Transformations utiles, entrées invalides, erreurs HTTP ou de contenu, transmission du signal d'annulation.      |
| Composants              | Vitest, React Testing Library et user-event, avec jsdom        | États visibles, interactions, messages, soumission et erreurs dans un DOM simulé, avec un réseau simulé par MSW. |
| Frontières de modules   | Règle ESLint locale et tests Node.js                           | Imports autorisés entre routes, fonctionnalités et modules communs.                                              |
| Navigateur              | Playwright dans Chromium, et dans WebKit pour un sous-ensemble | Navigation, échanges réels frontend/backend et parcours essentiels.                                              |
| Vérifications manuelles | Navigateur et appareils ciblés                                 | Usage clavier, focus, compréhension, affichage mobile et limites des outils automatiques.                        |

Placer les tests unitaires et de composants à côté du code testé.
Utiliser les helpers communs de `src/test/` et un client Query isolé pour chaque
test qui en a besoin. Nettoyer les mocks et les ressources entre les tests.
Les scénarios navigateur restent dans `frontend/e2e/`.

Les traces, captures et vidéos Playwright sont désactivées par défaut.

- Un spec qui manipule des identifiants ne les active jamais.
- Aucune capture n'est faite d'une page qui affiche un secret.
- Lors d'une mise à jour de Playwright, vérifier que les rapports d'échec restent
  sans secret.
- Les captures manuelles ne contiennent aucune donnée sensible.

Les fixtures préparent des comptes distincts et libèrent leurs ressources.
Séparer leur délai de préparation du délai du parcours testé. Les limites du
navigateur headless, notamment le changement d'onglet et la visibilité des pages,
demandent aussi une vérification manuelle.

Les règles de frontières de modules et leurs limites sont décrites dans l'[architecture](architecture.md#sens-des-dépendances).

### Réseau simulé

Les données de test sont fictives. Les tests de composants simulent le réseau avec [MSW](https://mswjs.io/docs/) : il répond à chaque requête selon sa route, sans modifier le code testé.

- Le serveur MSW démarre dans `src/test/setup.ts`. Les réponses de succès sont définies par route, dans un fichier par fonctionnalité de `src/test/handlers/` : l'authentification (session, connexion, déconnexion, reprise, CSRF) et la carte (recherche, points, fiche, suggestions et sources, sur un jeu d'établissements fictifs). Le fichier de l'authentification fournit aussi des fabriques de réponses (session anonyme, refus, `429` avec `Retry-After`) ; `src/test/auth.ts` regroupe les scénarios de session communs aux tests.
- Un cas d'erreur (`401`, `403`, `429`, `5xx`, panne réseau) est défini dans le test qui en a besoin, par une réponse propre à ce test (`server.use`). Elle disparaît à la fin du test. `{ once: true }` sert à une réponse ponctuelle (une panne, une réponse retenue) suivie du retour à la réponse par défaut ; un compte connu jusqu'à sa déconnexion s'écrit avec un état dans la réponse (`signedInUntilLogout`).
- Chaque fonctionnalité a au moins un test qui traverse le vrai client HTTP commun, pour vérifier l'URL, le CSRF et le traitement des erreurs.
- Une requête sans réponse définie fait échouer le test, avec la méthode et le chemin de la requête, même si l'écran a affiché une panne. Un oubli ne devient pas un succès silencieux.
- `renderWithProviders` (`src/test/render.tsx`) affiche un écran avec un client Query isolé, vidé à la fin du test, et un routeur en mémoire. Les providers restent en place quand le test appelle `rerender`. Ils installent aussi la reconnexion automatique du `401` : un seul render à la fois, sinon l'installation échoue. Un test qui en affiche deux démonte le premier.
- `recordRequests` (`src/test/requests.ts`) liste les requêtes que le serveur reçoit : un test vérifie ce que le client a vraiment envoyé (chemin, paramètres, header `X-CSRF-TOKEN`) et ce qu'il n'a pas envoyé, après `settled()`. `holdResponse` garde une réponse ouverte jusqu'à ce que le test la libère, pour observer l'écran pendant une lecture.
- N'annule pas une requête pendant qu'elle est retenue : avec MSW 3.0.3, la requête suivante peut partir vers le vrai réseau (connexion refusée), de façon rare et sous charge, même si la réponse n'arrive jamais. Pour tester qu'un signal est transmis, passer un signal déjà annulé (`AbortSignal.abort()`). Pour tester ce que fait une opération abandonnée (timeout, lock), utiliser une opération qui se termine à l'annulation de son signal, sans réseau, ou, pour une requête du client, `stallRequests` (voir l'exception plus bas).
- Avec des timers factices, ne simuler que ce que le test déplace : `toFake: ['Date']` quand seule la date compte, `shouldAdvanceTime` quand un minuteur de l'écran doit avancer pendant que le test attend le serveur.
- Ne pas compter tous les timers (`vi.getTimerCount()`) dans un test qui touche le réseau : la première connexion du fichier arme un timer d'undici sur l'horloge factice, et le compte dépend de l'ordre des tests. Vérifier l'effet attendu, par exemple un signal non abandonné une fois le délai passé.
- Les tests de composants et de routes n'espionnent pas le client `api` : ils répondent par MSW. Un nouveau test n'espionne ni le client `api` ni `fetch` : un spy contourne le client et cache ses défauts. Exceptions : un spy qui laisse passer l'appel (sans le remplacer) pour lire une option que MSW ne voit pas, comme `cache: 'no-store'` (`features/auth/api.test.ts`, `features/map/api.test.ts`) ; `stallRequests` (`src/test/requests.ts`), qui installe pour la durée du test un middleware sur le client `api` passé par le test et laisse une requête sans réponse jusqu'à son abandon, sans passer par MSW (qui ne tient pas un abandon, voir plus haut) ; et l'import du client commun dans un test d'écran, quand il envoie une lecture protégée pour traverser le vrai middleware de reconnexion `401` (`authenticated-screen.test.tsx`) ; jamais pour l'espionner.
- Le mock partiel de `./api` (`vi.mock`) reste dans trois fichiers d'authentification. `features/auth/queries.test.ts` lit l'option `resumeLimit` passée à chaque lecture et rejette des écritures avec une erreur précise, ce que MSW ne voit pas. `session/session-sync.test.ts` et `session/reauthentication.test.ts` annulent une lecture en attente : `stallRequests` demande le client `api`, import que la règle de frontières réserve aux tests de routes et d'écrans.
- `replayAfterReconnection` (`src/api/unauthorized.test.ts`) reçoit son envoi en paramètre : ses tests passent un envoi factice, qui est le point d'injection de la fonction et non un mock du client.

Les autres outils de Vitest restent disponibles pour ce qui n'est pas du réseau : `vi.fn` pour une fonction transmise à un composant, `vi.stubGlobal` pour une API du navigateur absente de jsdom.

### Tests navigateur

Un test navigateur ne vérifie que ce que seul un navigateur voit : navigation, focus, cookies, carte, rendu accessible. Un comportement déjà prouvé par Vitest ou par le backend n'est pas répété ici.

- Attendre un état, jamais une durée ni la fin du réseau : assertions `expect(locator)` qui réessaient, sans `waitForTimeout` ni `networkidle`. Ne pas allonger un timeout ni affaiblir une assertion pour masquer un défaut.
- Chercher les éléments par rôle ou par label, comme avec Testing Library ; `getByTestId` en dernier recours.
- Attendre la carte par son attribut `data-map-state`, puis, après un déplacement ou une sélection, par `data-map-motion="idle"` : le moteur le pose à chaque mouvement et le remet à `idle` quand la carte est stable. Jamais par le hash du canvas ; un clic sur le canvas est une action, pas une attente.
- Vérifier ce que la carte dessine par `data-map-points` : à chaque arrêt, le moteur y écrit le nombre de points et de regroupements d'établissements affichés.
- Borner la parallélisation : quelques processus Playwright (`workers`, de 2 à 4 si les tests sont isolés) et `retries` au plus 1. Une relance qui réussit cache un test instable : le corriger ; en CI, `failOnFlakyTests` fait échouer la suite dans ce cas. Le fournisseur Google fictif a une seule identité : son fichier est en `mode: 'serial'`.
- Exécuter un sous-ensemble de parcours dans WebKit, à côté de Chromium, pour couvrir Safari et iPhone. Le sous-ensemble contient les parcours où le moteur change le comportement : cookies, focus, saisie et affichage mobiles.

Les tests navigateur vérifient l'échange réel entre le frontend et l'API. Ils ne valident pas la disponibilité de toutes les intégrations.
Voir les [bonnes pratiques de Playwright](https://playwright.dev/docs/best-practices).

### Exemples dans le dépôt

| Besoin                                                               | Test à consulter                                                                                                                 |
| -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Client HTTP : reprise de session, panne, réponse incohérente et CSRF | [`api.test.ts`](../../frontend/src/features/auth/api.test.ts).                                                                   |
| Saisie invalide, panne avec saisie conservée et nouvelle tentative   | [`registration-screens.test.tsx`](../../frontend/src/features/auth/signup/components/registration-screens.test.tsx).             |
| Réponse perdue, rejeu explicite et lien expiré                       | [`password-reset-screens.test.tsx`](../../frontend/src/features/auth/password-reset/components/password-reset-screens.test.tsx). |
| Changement de compte et déconnexion                                  | [`authenticated-screen.test.tsx`](../../frontend/src/features/auth/account/components/authenticated-screen.test.tsx).            |
| Données de la carte, panne de l'API et fond indisponible             | [`map-screen.test.tsx`](../../frontend/src/features/map/components/map-screen.test.tsx).                                         |
| Session persistante et déconnexion dans le navigateur                | [`authentication.spec.ts`](../../frontend/e2e/authentication.spec.ts).                                                           |
| Accueil, scan axe et page inconnue                                   | [`home.spec.ts`](../../frontend/e2e/home.spec.ts).                                                                               |

## États et interactions

Vérifier les états réellement possibles et utiles au comportement modifié :

- Chargement initial, succès, résultat vide et erreur de lecture.
- Donnée absente ou invalide lorsque l'usage doit la traiter.
- Rafraîchissement sans perte inattendue de la saisie ou de la sélection.
- Action en cours, succès confirmé et échec avec possibilité de correction.
- Changement rapide de paramètres et résultat correspondant aux paramètres actifs.
- Navigation directe et rechargement d'une route concernée.

Une annulation de requête ne doit pas afficher un faux succès ni réintroduire
les données d'un ancien contexte. Pour une écriture, vérifier les doubles
actions, l'invalidation des vues affectées et le rollback si l'interface
affiche le résultat attendu avant confirmation du serveur. Les règles de cache
et d'[optimistic update](api-layer.md#mutations-et-invalidation) figurent dans
la couche API.

## Accessibilité et mobile

Le scan axe dans Playwright complète les vérifications manuelles.
Un résultat sans violation automatique ne prouve pas l'accessibilité du parcours.
Voir [les tests d'accessibilité Playwright](https://playwright.dev/docs/accessibility-testing)
et [l'évaluation selon le W3C](https://www.w3.org/WAI/test-evaluate/).

| Aspect             | Vérifications manuelles                                                                                                                              |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Clavier            | Ordre et visibilité du focus, activation des actions, entrée et sortie des fenêtres, absence de piège.                                               |
| Champs et messages | Labels, erreurs associées aux champs et changements de statut perceptibles sans dépendre uniquement de la couleur.                                   |
| Affichage          | Tailles d'écran ciblées, zoom, contenus longs, zones tactiles et absence d'information masquée par un débordement ou accessible seulement au survol. |
| Carte et liste     | Résultats et actions essentielles accessibles dans la liste sans manipuler la carte. Éléments sans lieu identifiables, sans position fictive.        |
| Géolocalisation    | Recherche manuelle utilisable lorsque l'utilisateur refuse la géolocalisation.                                                                       |

## Sécurité et confidentialité

Les [règles de sécurité communes](../securite.md) fixent les accès et le périmètre
des données. Cette section précise leurs vérifications dans le navigateur ;
l'activation et la surveillance des collecteurs relèvent de l'[exploitation](../exploitation.md).

Pour un parcours protégé, vérifier :

- Le refus du backend en l'absence de session ou de droit.
- L'expiration de session, la déconnexion et le changement de compte.
- La protection CSRF des écritures concernées.
- L'absence de données du compte précédent dans le cache ou l'interface.

Les obligations d'intégration figurent dans la [couche API](api-layer.md#session-et-protection-des-données).

Vérifier qu'aucun secret ni adresse de serveur propre à un environnement
n'est intégré au bundle. Les variables `VITE_*` exposées au frontend sont
publiques ; voir la [documentation Vite](https://vite.dev/guide/env-and-mode).
Ne pas placer de données personnelles dans les URL, traces ou fixtures partagées.

### Diagnostic navigateur avec Faro

Faro sert au diagnostic technique des erreurs et lenteurs. Avant toute
activation, effectuer des essais avec des données sensibles fictives et
inspecter les événements réellement émis. La présence du SDK dans les
dépendances ne vaut pas validation de sa configuration.

| Point à contrôler  | Résultat attendu                                                                                                                                                                        |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Champs envoyés     | Version, environnement, erreur nettoyée et contexte technique nécessaire, dans le périmètre du [guide sécurité](../securite.md#diagnostic-et-incidents).                                |
| Données sensibles  | Aucun nom, pseudo, email, identifiant de compte, valeur de formulaire, body HTTP, cookie, token ou position géographique dans les rapports.                                             |
| URL et routes      | Gabarits sans identifiant réel, paramètre de requête ou fragment. Par exemple, `/events/:id` au lieu de l'URL d'un événement consulté.                                                  |
| Instrumentations   | Capture de console, rejeu, suivi des clics et parcours désactivés. Aucun utilisateur identifié ni identifiant de visiteur persistant ; mécanismes de session hors périmètre désactivés. |
| Filtrage           | Nettoyage avant l'envoi et contrôles à la réception. Rejet de tout événement qui ne peut pas être nettoyé de façon fiable.                                                              |
| Chaîne de collecte | Aucune adresse IP dans les rapports ni dans les traces du proxy et du collecteur liées à Faro.                                                                                          |
| Erreurs React      | Transmission explicite des erreurs interceptées par React, avec le même filtrage.                                                                                                       |
| Conservation       | Durée de conservation unique, vérifiée aussi pour les copies, exports et notifications : voir l'[exploitation](../exploitation.md#conservation-et-diagnostic-navigateur).               |

Faro ne garantit pas la détection d'un incident survenu avant son démarrage ou
sans erreur capturée.

## Performance et incidents

Mesurer les parcours concernés sur les appareils et réseaux visés :

- Volume de données, ressources lourdes et requêtes inutiles.
- Chargements successifs de données qui pourraient être demandées en parallèle.
- Réactivité pendant une action.
- Affichage et interactions de la carte avec un volume de points représentatif.

La taille d'une archive cartographique seule ne démontre pas la performance.

Définir les seuils utiles à partir du parcours et de la charge attendue.
Ne pas annoncer une capacité ou un gain sans mesure reproductible.
Un incident apporte un cas concret : le reproduire et ajouter un test de
non-régression lorsque celui-ci permet de prévenir son retour.
La supervision complète ces vérifications ; elle ne les remplace pas.

### Budget de taille

`npm run check -- frontend` mesure le build avec [size-limit](https://github.com/ai/size-limit), en taille gzip, celle que le serveur transmet. Le contrôle échoue au-delà d'un seuil (nom affiché par l'outil entre parenthèses) :

| Fichiers (`build/client/assets/`)                       | Seuil  | Pourquoi                                                    |
| ------------------------------------------------------- | ------ | ----------------------------------------------------------- |
| Point d'entrée (`Entry point`) `entry.client-*`         | 72 kB  | Téléchargé par chaque page avant son premier écran actif.   |
| Moteur de carte (`Map engine`) `map-engine-*`           | 315 kB | MapLibre : demandé à l'ouverture de la carte, jamais avant. |
| Worker de carte (`Map worker`) `maplibre-gl-worker-*`   | 160 kB | Démarré par MapLibre pour décoder les tuiles.               |
| Tout le JavaScript et le CSS (`All JavaScript and CSS`) | 805 kB | Empêche une croissance répartie sur de nombreux fichiers.   |

Chaque seuil laisse environ 10 % au-dessus de la mesure faite à sa fixation. Ils se trouvent dans [`frontend/.size-limit.json`](../../frontend/.size-limit.json). Un dépassement se corrige d'abord par le découpage ou le retrait de code. Relever un seuil demande une mesure du parcours concerné, notée dans la PR.

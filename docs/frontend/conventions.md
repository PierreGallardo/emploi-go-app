# Conventions frontend

Ces conventions expliquent comment ajouter ou modifier une fonctionnalité dans
`frontend/` : organiser et nommer le code, choisir où conserver les données et
rendre les interactions compréhensibles.
L'[architecture](architecture.md) définit les responsabilités des dossiers ;
la [couche API](api-layer.md) définit les requêtes et le cache.
Les commandes restent dans le [guide de développement](developpement.md).

Définir le comportement d'un parcours selon le [périmètre fonctionnel](../perimetre-v1.md).
Les obligations communes d'accès et de confidentialité sont dans
le [guide sécurité](../securite.md).

## Ajouter une fonctionnalité

1. Définir le comportement attendu, ses états visibles et les échanges utiles.
2. Créer `src/features/<feature>/` avec les seuls fichiers utilisés.
3. Définir les fonctions HTTP dans `api.ts`, puis les options Query dans
   `queries.ts` lorsque la fonctionnalité échange avec l'API.
4. Placer les composants de la fonctionnalité dans `components/`, ou dans
   ceux de ses sous-dossiers quand elle couvre plusieurs parcours (voir
   l'[architecture](architecture.md#organisation-du-code)).
5. Ajouter `schemas.ts` si un formulaire nécessite une validation.
6. Composer l'écran dans `src/routes/` et déclarer sa route dans `src/routes.ts`.
7. Vérifier les comportements avec les tests adaptés et actualiser la
   documentation concernée.

Exemple fictif : la fonctionnalité `events` lit une liste d'événements.

- `src/features/events/api.ts` : fonctions HTTP.
- `src/features/events/queries.ts` : options Query.
- `src/features/events/components/event-list.tsx` : affichage.
- `src/routes/events.tsx` : composition de l'écran.

Une fonctionnalité peut être affichée sur plusieurs routes.
Son dossier représente sa responsabilité, pas chaque URL ou chaque fichier.

Extraire un hook ou un helper lorsqu'il isole une logique utile ; ne pas ajouter
une couche qui ne fait que transmettre des arguments sans autre responsabilité.

Les écrans d'authentification se déclarent sous la route de mise en page
`routes/auth-layout.tsx`. Le logo et le panneau restent en place : seul le
formulaire change, avec un fondu coupé par `prefers-reduced-motion`. Chaque
étape garde sa propre URL.

Sur ces écrans, les actions s'empilent, jamais côte à côte :

- un bouton principal (plein) pour l'action attendue ensuite, au plus un par écran ;
- un bouton secondaire (contour) pour une action de secours ou une alternative ;
- un lien pour quitter l'écran, toujours en bas.

L'espacement suit une seule échelle, portée par les composants partagés
(`auth-form.tsx`) : 2 dans un champ, 4 entre les champs, 6 entre l'en-tête, le
formulaire et les actions, 8 avant les liens de sortie. Une erreur s'affiche en
texte simple (`FormError`), sans encadré.

## Noms et imports

### Langue

- Les identifiants et les noms de fichiers sont en anglais.
- Les textes affichés à l'utilisateur sont en français.
- Les URL visibles par l'utilisateur sont en français, par exemple `/connexion`
  ou `/inscription`.

### Casse

| Élément              | Convention    | Exemple fictif        |
| -------------------- | ------------- | --------------------- |
| Dossier ou fichier   | `kebab-case`  | `event-list.tsx`      |
| Composant ou type    | `PascalCase`  | `EventList`           |
| Fonction ou variable | `camelCase`   | `getEvent`, `eventId` |
| Hook React           | Préfixe `use` | `useEvent`            |

Nommer les fonctions par leur action et les composants par ce qu'ils affichent
ou permettent de faire.

Préférer les exports nommés pour les modules de fonctionnalité et les composants
partagés. Les modules de route suivent les exports attendus par React Router.
Utiliser `import type` pour un import utilisé uniquement dans les types.

L'alias `@/` désigne `frontend/src/`.
Utiliser les imports relatifs à l'intérieur d'une fonctionnalité et l'alias
pour accéder aux modules communs ou composer une fonctionnalité depuis une route.
Ne pas ajouter de fichier d'exports global regroupant toutes les fonctionnalités.

Une fonctionnalité ne dépend pas d'une autre fonctionnalité ni d'une route.
Si plusieurs usages nécessitent une même responsabilité, définir un module
partagé et ses entrées avant de déplacer le code. `lib/` accueille les fonctions
utilitaires techniques communes ; ne pas y ranger par défaut la logique d'une fonctionnalité.

## État et données

Chaque information a un propriétaire :

| Information                                                                   | Propriétaire                                                   |
| ----------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Données de l'API, chargement, erreur et staleness                             | TanStack Query                                                 |
| Paramètres de navigation et filtres à conserver au rechargement ou au partage | URL, lue et modifiée avec React Router                         |
| Valeurs, validation et erreurs d'un formulaire                                | React Hook Form, avec Zod lorsqu'une validation est nécessaire |
| Ouverture d'une fenêtre, sélection provisoire ou autre interaction locale     | État React au plus près de l'usage                             |
| Valeur calculable depuis les données ou l'état existant                       | Calcul pendant le render ou fonction dédiée                    |

Ne pas copier les résultats Query dans un contexte ou dans `useState` pour les
maintenir à jour. Un formulaire d'édition peut initialiser ses propres valeurs
depuis les données reçues ; il représente alors une saisie distincte du cache.
Définir son comportement si les données changent pendant cette saisie.

Les paramètres d'URL sont des entrées non fiables : analyser leurs types,
appliquer les valeurs par défaut et traiter les valeurs invalides.
Ne pas y placer de secret, donnée de formulaire sensible ou position précise.

Éviter les états dupliqués et les combinaisons impossibles : une valeur dérivée
du cache ou d'un autre état ne nécessite pas une seconde valeur à synchroniser.
Voir les [principes de structure de l'état React](https://react.dev/learn/choosing-the-state-structure).

## Composants et effets

Le rendu calcule ce qu'il faut afficher. Il ne déclenche aucun appel HTTP,
écriture de stockage ou démarrage d'une intégration externe.

- Charger les données API avec les options Query de la fonctionnalité.
- Déclencher une action dans son handler d'événement ; utiliser une
  mutation pour modifier les données serveur, selon la [couche API](api-layer.md#mutations-et-invalidation).
- Utiliser un effet pour synchroniser une intégration externe avec le composant,
  par exemple créer une carte après le mount. Libérer les abonnements,
  instances et ressources dans le nettoyage de cet effet.
- Calculer directement une valeur qui dépend d'un état existant, sans la
  recopier dans un second état au moyen d'un effet.

Vérifier le mount et l'unmount, y compris les cycles supplémentaires de
développement de React.
Voir [Synchroniser avec des effets](https://react.dev/learn/synchronizing-with-effects).

Les composants `components/ui/` restent indépendants de l'API et des
fonctionnalités. Ils reçoivent leurs données, états et callbacks par props.
Conserver leur sémantique HTML, leurs labels et leur comportement au clavier
lors d'une adaptation du code shadcn.

## Formulaires et validation

1. Définir les schémas de saisie dans `schemas.ts` et en déduire les types lorsque
   c'est adapté. Écrire des messages qui permettent de corriger les champs.
2. Pendant l'envoi, afficher l'état de la soumission et prévenir les doubles
   actions accidentelles.
3. Après un échec, afficher l'erreur attendue près du champ ou du formulaire
   concerné, sans réponse technique brute. Conserver la saisie pour permettre
   une correction ou une nouvelle tentative.
4. Afficher le succès après confirmation du backend.

Lorsqu'un schéma transforme une valeur, par exemple un texte en nombre,
distinguer les valeurs saisies (`z.input`) des valeurs validées (`z.output`)
dans les types du formulaire.
Voir l'[intégration Zod des resolvers](https://github.com/react-hook-form/resolvers#zod).

La validation frontend guide la saisie ; le backend valide à nouveau les entrées
et applique les droits. Les types du contrat API ne remplacent aucune de ces validations.
Ne pas dupliquer tous les types du contrat dans des interfaces écrites à la main.

## États d'interface et erreurs

| État d'une lecture  | Comportement de l'interface                                                                                                 |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Chargement initial  | Indiquer que les données sont en cours de chargement.                                                                       |
| Données disponibles | Afficher les résultats et les actions accessibles.                                                                          |
| Aucun résultat      | Expliquer l'absence de résultat, avec une action utile si possible.                                                         |
| Échec               | Afficher un message compréhensible et la possibilité de corriger ou de réessayer selon la cause.                            |
| Refetch             | Décider si les données déjà affichées restent utilisables ; éviter de remplacer inutilement tout l'écran par un indicateur. |

Les composants présentent les erreurs Query de leur usage. Le composant
`ErrorBoundary` dans `root.tsx` affiche une page de secours pour les erreurs
de route ou de render qui lui parviennent. Il ne remplace pas les états d'erreur
d'une requête.

Les messages sont courts et permettent de comprendre l'état ou l'action suivante.
Éviter d'afficher une stack trace, un body HTTP brut ou des informations de
compte dans un message technique. Associer les messages de formulaire aux champs
et rendre les changements de statut perceptibles aux technologies d'assistance.

### Focus quand un écran en remplace un autre

Quand l'élément qui tenait le focus quitte la page, le navigateur le laisse sur
`body` et un visiteur au clavier ou au lecteur d'écran repart du haut du
document. L'écran qui prend la place le rend. Cela vaut pour une navigation
(lien, redirection, rollback) comme pour un remplacement dans la page
(attente de l'email, confirmation, étape suivante, « Réessayer » réussi).

- Le focus va sur le titre de l'écran, un `h1` avec `tabIndex={-1}` et sans
  contour : `AuthCard` le fait. L'app shell protégé le donne à `<main>`.
  Un champ ne convient pas : le lecteur d'écran l'annonce sans dire où l'on est,
  et le clavier d'un téléphone s'ouvre.
- Rien ne bouge au premier chargement, au rechargement, ni tant que le focus
  est sur un élément encore dans la page. Un écran d'attente (`placeholder`)
  n'est pas un écran à lire.
- Une erreur qui remplace l'écran (`focusTitle`) prend le focus même si rien ne
  l'a perdu : elle doit être annoncée.
- Aucun événement ne dit qu'un élément a été retiré : Chromium signale
  `focusout` avant le retrait, jsdom ne signale rien. Safari ne donne pas le
  focus à un bouton cliqué, et un lecteur d'écran en mode navigation lit avec
  son propre curseur. `src/lib/lost-focus.ts` retient donc le dernier élément
  focalisé ou cliqué et l'écran affiché, et `focusWasLost()` vérifie qu'ils ont
  quitté la page alors que le focus est sur `body`.
- Un nouvel écran appelle `focusWasLost()` dans un effet, puis
  `markScreenShown()`.

### Titre du document

Le titre de l'onglet vient d'une seule source : le composant `PageTitle`
(`<title>` de React 19, qui le place dans le `<head>`). Il porte le suffixe
« · Emploi Go » et reprend le `h1` de l'écran.

- `AuthCard` rend `PageTitle` avec la prop `title` de son `h1` : le titre suit
  l'état de l'écran (« Vérifie ton email », « Connexion indisponible ») sans
  second texte à tenir à jour. `AuthenticatedScreen` reçoit `title`, car sa
  app shell n'a pas encore de `h1` pendant le chargement.
- Une route n'exporte pas de `title` dans `meta()` : `<Meta />` en ferait un
  second `<title>`, et le dernier monté l'emporterait sans retirer l'autre.
  Une règle ESLint (`tooling/route-title.mjs`) le refuse. `meta()` reste
  possible pour d'autres balises.
- Le titre est rendu avec l'écran : dans une page prérendue, il est écrit dans
  le HTML et l'hydratation l'adopte sans doublon. Le repli SPA porte le titre
  « Chargement · Emploi Go » de `HydrateFallback`.

### Fin de chargement

Un écran qui attend ses données annonce le début et la fin du chargement par un
seul statut `sr-only` : `LoadingStatus` dit « Chargement… », puis « Chargement
terminé. » pendant 5 s, dans le même élément (une région vivante déjà présente
est annoncée plus sûrement qu'une région qui apparaît). Les écrans le placent
là où l'écran de chargement et l'écran chargé ont la même position, pour que
React garde l'élément. La zone reste dans la page, vide hors annonce : l'effet de
`LoadingStatus` y écrit le message de fin, puis l'efface (une zone `role="status"` est un système
extérieur à React, voir `react-hooks/set-state-in-effect` dans les règles du frontend). Un texte visible d'attente (« Lecture du lien… », « Vérification
de Google… ») reste un simple paragraphe : le statut est celui de `LoadingStatus`.

- Il se tait quand le chargement finit sur une erreur (`silent` : l'alerte
  parle) et quand l'écran qui arrive prend le focus (le titre focalisé est
  annoncé, voir la règle ci-dessus). Il lit `focusWasLost()` dans un effet
  qui s'exécute avant celui de l'écran qui déplace le focus : `LoadingStatus`
  est un enfant de cet écran, ou un frère placé avant lui. Le déplacer après
  annoncerait la fin en plus du titre.
- Exception : l'app shell protégé donne le focus à `<main>`, qui n'a pas de
  nom, donc rien n'est annoncé par le focus (`focusSpeaks={false}`) et la fin
  du chargement reste annoncée.
- La durée de 5 s n'est pas une valeur publiée : une annonce polie attend la
  parole en cours, et un texte `sr-only` qui reste est relu comme du texte
  parasite. À confirmer avec NVDA et VoiceOver.

## Partage et vérification

Placer les tests à côté du comportement testé et utiliser `src/test/` pour les
aides réellement partagées. Les jeux de données des tests contiennent des données fictives.
Vérifier les états et interactions utiles suivant le [guide qualité](qualite.md).

Ajouter une dépendance lorsqu'elle résout un besoin identifié et vérifier son
usage dans le build SPA. Les règles de mise à jour des package.json et du lockfile
figurent dans le [guide de développement](developpement.md#installation).
Les règles de PR et de revue restent dans [CONTRIBUTING.md](../../CONTRIBUTING.md).

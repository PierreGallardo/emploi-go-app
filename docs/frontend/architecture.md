# Architecture frontend

Le frontend est une application web responsive en React et TypeScript.
Il s'exécute dans le navigateur : React Router organise la navigation et Vite
produit les fichiers de l'application à page unique (SPA).
Le backend Spring Boot expose les données et contrôle les actions autorisées.

Les routes composent les écrans, les fonctionnalités portent leurs interactions
et une couche commune assure le transport HTTP typé.

- Les [conventions](conventions.md) expliquent comment ajouter une fonctionnalité.
- La [couche API](api-layer.md) précise les échanges et le cache.

Les autres références sont regroupées dans [Voir aussi](#voir-aussi).

## Organisation du code

Les chemins de ce guide partent de `frontend/`.
Une fonctionnalité regroupe le code nécessaire à un usage cohérent.
Exemple fictif : la fonctionnalité `events` affiche une liste d'événements lue
dans l'API.

```text
src/features/events/
├── api.ts
├── queries.ts
├── schemas.ts
└── components/
    └── event-list.tsx
```

- Créer seulement les fichiers utiles au comportement de la fonctionnalité.
- `errors.ts` (erreurs propres à la fonctionnalité) et `model.ts` (types propres
  à l'interface) sont des fichiers courants, toujours optionnels.
- `schemas.ts` porte la validation d'un formulaire.
- Les fonctions utilitaires de la fonctionnalité restent dans son dossier.
- Les utilitaires techniques communs appartiennent à `src/lib/`.

Une fonctionnalité qui couvre plusieurs parcours se découpe en sous-dossiers,
un par parcours. C'est le cas de `auth` :

```text
src/features/auth/
├── api.ts, queries.ts, errors.ts, schemas.ts…  partagés par les parcours
├── session/          maintien de la session, sans écran
├── login/components/
├── signup/
│   ├── registration-code-state.ts
│   └── components/
└── shared/components/  composants communs à plusieurs parcours
```

- Découper quand un dossier mélange des parcours qu'on sait nommer, pas avant.
- Dans chaque dossier, `components/` porte l'affichage ; les autres fichiers
  portent la logique.
- Un sous-dossier importe la racine de sa fonctionnalité et `shared/`, jamais
  un sous-dossier voisin. Ce que deux parcours utilisent remonte à la racine ou
  dans `shared/`.

Les tests unitaires et de composants sont placés à côté du code testé.
`src/test/` contient la configuration et les helpers partagés.
Ses fichiers `handlers/<fonctionnalité>.ts` simulent l'API : ils n'importent que `msw` et les types ou données de leur fonctionnalité. Ses helpers (`auth.ts`, `render.tsx`) peuvent importer le code de production d'une fonctionnalité, pour installer les mêmes branchements que l'application. Le code de production n'importe jamais `src/test/`.
`e2e/` contient les parcours vérifiés dans un navigateur.

## Responsabilités

| Emplacement                                     | Responsabilité                                                                                                                                                                                                                            |
| ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/root.tsx`                                  | Structure HTML, contextes communs, affichage des routes et traitement principal des erreurs de route ou de render.                                                                                                                        |
| `src/providers.tsx`                             | Instances et contextes communs, dont le client TanStack Query et la reconnexion automatique (nouvelle session sans écran de connexion) du client HTTP.                                                                                    |
| `src/routes.ts`                                 | Association des URL aux modules de route.                                                                                                                                                                                                 |
| `src/routes/`                                   | Paramètres de navigation, métadonnées et composition des écrans.                                                                                                                                                                          |
| `src/features/<feature>/components/`            | Interface et interactions de la fonctionnalité.                                                                                                                                                                                           |
| `src/features/<feature>/api.ts`                 | Fonctions de requête de la fonctionnalité, appelant le client HTTP commun.                                                                                                                                                                |
| `src/features/<feature>/queries.ts`             | Clés, options de query, mutations et règles de cache de la fonctionnalité.                                                                                                                                                                |
| `src/features/<feature>/schemas.ts`             | Validation des valeurs des formulaires concernés.                                                                                                                                                                                         |
| `src/features/auth/session/reauthentication.ts` | Reconnexion automatique d'une query protégée refusée (`401`), remise au client HTTP commun.                                                                                                                                               |
| `src/api/client.ts`                             | Configuration commune du transport HTTP typé.                                                                                                                                                                                             |
| `src/api/unauthorized.ts`                       | Rejeu d'une query protégée après un `401` : l'emplacement de la reconnexion, que `providers.tsx` remplit.                                                                                                                                 |
| `src/api/schema.d.ts`                           | Types générés du contrat API.                                                                                                                                                                                                             |
| `src/components/`, dont `ui/`                   | Composants réutilisables, indépendants des fonctionnalités et des appels API.                                                                                                                                                             |
| `src/lib/`                                      | Fonctions utilitaires techniques partagées, indépendantes des fonctionnalités et des appels API.                                                                                                                                          |
| `src/app.css`                                   | Styles globaux et variables des thèmes clair et sombre. Sans choix, le thème suit la préférence du système ; `data-theme` sur `<html>` porte un choix explicite.                                                                          |
| `src/lib/theme.ts`, `src/lib/theme-init.js`     | Choix « Clair / Sombre / Système », fait dans « Mon compte » > « Paramètres » et gardé dans le navigateur. Le script du `<head>`, un fichier et non du code inline pour respecter `script-src 'self'`, l'applique avant le premier rendu. |

Une route reste un point de composition. Elle délègue l'affichage d'une
fonctionnalité à ses composants et les échanges HTTP à sa couche API.
Les composants génériques reçoivent leurs données et leurs actions par props.

## Sens des dépendances

Exemple fictif : le chemin d'une lecture dans la fonctionnalité `events`.

```text
routes/events.tsx
  → features/events/components/event-list.tsx
    → features/events/queries.ts
      → features/events/api.ts
        → api/client.ts (typé par api/schema.d.ts)
          → API
```

- Une route peut composer plusieurs fonctionnalités.
- Le client commun ne peut pas importer une fonctionnalité. La reconnexion
  automatique, qui relève de `auth`, lui est donc remise par
  `features/auth/session/reauthentication.ts` (`api/unauthorized.ts`).
  `providers.tsx`, qui connaît les deux, l'installe ; `renderWithProviders`
  fait de même dans les tests. Seuls `api/` et `reauthentication.ts` importent
  `api/unauthorized.ts`
  (règle `local/module-boundaries`).
- Une fonctionnalité utilise le client API par son fichier `api.ts`, ainsi que
  les composants et utilitaires communs dont elle a besoin.
- Elle n'importe ni une route ni une autre fonctionnalité.
- Un module commun est extrait lorsqu'une responsabilité doit réellement être
  partagée.

### Imports autorisés

| Module                                      | Peut importer                                                                                  | Exception des tests                               |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| `api/`, `components/`, `lib/`               | Les modules communs (`api/`, `components/`, `lib/`).                                           | Aucune.                                           |
| `components/`, `lib/`                       | Jamais `api/`.                                                                                 | Aucune.                                           |
| `features/<feature>/api.ts`                 | `api/`, `lib/` et les fichiers de la même fonctionnalité, sauf `queries.ts` et les composants. | Aucune.                                           |
| `features/<feature>/queries.ts`             | `api.ts`, `schemas.ts`, `lib/` et les types de la même fonctionnalité.                         | Aucune.                                           |
| `features/auth/session/reauthentication.ts` | `api/unauthorized.ts`, `queries.ts` de la fonctionnalité, React et TanStack Query.             | Aucune.                                           |
| `features/<feature>/…/components/`          | `queries.ts` et fichiers de la même fonctionnalité, `components/`, `lib/`.                     | Peuvent importer `api/client.ts` pour le simuler. |
| `routes/`                                   | Composants de fonctionnalités, `components/`, `lib/`.                                          | Peuvent importer `api/client.ts` pour le simuler. |
| `root.tsx`, `providers.tsx`, `routes.ts`    | Routes, fonctionnalités et modules communs.                                                    | Aucune.                                           |

Interdits dans tous les cas :

- Une fonctionnalité n'importe ni route, ni fichier de composition, ni autre fonctionnalité.
- Hors des tests, un sous-dossier de fonctionnalité n'importe pas un sous-dossier voisin, sauf `shared/`.
- Le socle commun (`api/`, `components/`, `lib/`) n'importe ni fonctionnalité, ni route, ni fichier de composition.
- Une route ou un composant de fonctionnalité n'importe pas `api.ts`.
- Seul `api.ts` d'une fonctionnalité importe le client HTTP commun.
- `api.ts` n'importe ni React ni TanStack Query.

Les règles précises sont définies dans
[`tooling/module-boundaries.mjs`](../../frontend/tooling/module-boundaries.mjs).
Elles sont vérifiées par ESLint et par les tests d'architecture.
Le transport `openapi-fetch` est réservé à `src/api/client.ts`.
La revue vérifie aussi les appels HTTP directs : un contrôle d'import ne détecte
pas un `fetch` direct.

## Navigation et données

| Outil          | Responsabilité                                                |
| -------------- | ------------------------------------------------------------- |
| React Router   | URL, paramètres de navigation et composition des écrans.      |
| TanStack Query | Chargement, erreurs, staleness et cache des données de l'API. |

Les composants utilisent les options définies dans `queries.ts`. Ils ne
recopient pas les résultats dans un second état global.

Si une navigation doit préparer des données avant d'afficher l'écran, elle
réutilise les mêmes options et le même client Query.
Un chargement déclenché par le routeur ne crée pas un cache concurrent.

La répartition de l'état entre URL, React, formulaires et Query figure dans les
[conventions](conventions.md#état-et-données).

## Exécution de la SPA

[`react-router.config.ts`](../../frontend/react-router.config.ts) fixe `ssr: false` :
aucun serveur React n'effectue le rendu à chaque requête.
Le build produit les fichiers statiques sous `build/client/`.
React Router génère le HTML initial pendant la compilation : le render initial
doit fonctionner sans `window`, `document` ni API du navigateur. Initialiser les
intégrations qui en dépendent après le mount, avec leur nettoyage.
Voir le [mode SPA de React Router](https://reactrouter.com/how-to/spa).

Les pages publiques `/connexion`, `/inscription` et `/mot-de-passe-oublie` sont
prérendues (`prerender` de la configuration) : leur écran s'affiche avant le
JavaScript, sur mobile et réseau lent. Les autres routes dépendent d'un token,
d'un parcours Google ou de la session : elles ne sont pas prérendues.

| Fichier du build         | Contenu                                                                    |
| ------------------------ | -------------------------------------------------------------------------- |
| `<route>/index.html`     | Écran prérendu de la route, repris par React à l'hydratation.              |
| `index.html` (repli SPA) | `HydrateFallback` de `root.tsx` : un état de chargement, pas d'écran vide. |

`/` n'est pas prérendue : React Router écrit donc le repli SPA dans `index.html`.
Il ne l'écrirait dans `__spa-fallback.html` que si `/` l'était.

L'hébergement distingue les demandes suivantes :

| Chemin            | Traitement                                                                                            |
| ----------------- | ----------------------------------------------------------------------------------------------------- |
| Route d'interface | Renvoyer sa page prérendue, sinon `index.html` ; React Router affiche l'écran ou la page introuvable. |
| `/api/*`          | Transmettre au backend, y compris pour une route API inconnue.                                        |
| `/cartes/*`       | Servir les ressources cartographiques publiques ; un fichier absent reçoit une erreur HTTP.           |

- Le cache des fichiers servis (pages HTML revalidées, `/assets` immutable) est
  décrit dans l'[exploitation](../exploitation.md#cache-des-fichiers-servis).
- La politique de sécurité de contenu (CSP) et les autres headers sont décrits
  dans la [sécurité](../securite.md#headers-http).

Un fichier ou endpoint absent sous `/api/*` ou `/cartes/*` ne renvoie jamais le HTML de la SPA.
Les appels API restent relatifs à l'origine courante pour utiliser le même build
entre les environnements.

## Frontières avec le backend

### Données, session et droits

| Frontend                                                               | Backend                                                                   |
| ---------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| Présenter les informations, guider la saisie et expliquer les erreurs. | Vérifier les données reçues, la session, les droits et les règles métier. |

Masquer une action ou rediriger une route ne constitue pas un contrôle d'accès
aux données. Le navigateur ne se connecte ni à PostgreSQL ni à Redis.

Les types d'échange viennent du contrat ; les types propres à l'interface restent
dans le frontend. La génération et ses vérifications sont décrites dans le
[guide du contrat API](../api.md).
Les règles de session et de confidentialité figurent dans la
[couche API](api-layer.md#session-et-protection-des-données).

### Cartographie

MapLibre GL JS et le lecteur `pmtiles` affichent le fond public.

- Le fond et les ressources graphiques restent distincts des données métier
  fournies par l'API.
- L'accès à la carte applicative exige une session.
- Les contrôles de l'API protègent les données et actions concernées.
- Aucun compte, favori ou démarche n'est intégré aux fichiers publics.
- La liste donne accès aux résultats et actions essentielles sans manipuler la
  carte.

La construction des fichiers est décrite dans la [cartographie](../cartographie.md).
Les règles de publication et de diffusion figurent dans
l'[architecture commune](../architecture.md#cartographie-et-fichiers).

### Diagnostic navigateur

Grafana Faro sert au diagnostic des erreurs et lenteurs.
Son intégration filtre les informations sensibles et respecte le périmètre de collecte.
La présence du SDK ne suffit pas à autoriser son activation.

- Le [guide sécurité](../securite.md#diagnostic-et-incidents) définit les
  informations autorisées.
- Le [guide qualité](qualite.md#sécurité-et-confidentialité) précise les
  vérifications avant activation.

## Voir aussi

- [Guide de développement](developpement.md) : commandes et installation.
- [Architecture commune](../architecture.md) : services et hébergement.
- [Périmètre fonctionnel](../perimetre-v1.md) : parcours de l'application.
- [Guide du contrat API](../api.md) : génération des types.

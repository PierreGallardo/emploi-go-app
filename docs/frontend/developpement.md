# Développer le frontend

Ce guide explique comment installer les dépendances, lancer le frontend et choisir les commandes de vérification. Les commandes ci-dessous s'exécutent depuis la racine du dépôt, sauf indication contraire.

Pour les responsabilités du code, consulter l'[architecture](architecture.md) et les [conventions](conventions.md). Le [guide de développement commun](../developpement.md) décrit l'environnement complet, ses services et son arrêt.

## Installation

Utiliser Node.js 24 à partir de 24.19.0 et npm 11 à partir de 11.17.0. Les versions acceptées sont déclarées dans le [`package.json` racine](../../package.json) ; [`.nvmrc`](../../.nvmrc) indique la version Node de référence. Une version incompatible fait échouer `npm ci` et toute commande `npm run` ; le [guide commun](../developpement.md#préparer-son-poste) explique comment installer la bonne avec nvm.

Installer Node.js et les dépendances :

```sh
nvm install
npm ci
```

Le frontend est un **workspace npm** : un sous-projet du dépôt qui partage l'installation et le lockfile avec les outils communs.

| Fichier                                                | Rôle                                                                  |
| ------------------------------------------------------ | --------------------------------------------------------------------- |
| [`frontend/package.json`](../../frontend/package.json) | Dépendances et commandes propres au frontend.                         |
| [`package.json`](../../package.json)                   | Outils et commandes communs au dépôt.                                 |
| [`package-lock.json`](../../package-lock.json)         | Versions exactes des dépendances installées pour l'ensemble du dépôt. |

Ne pas créer de lockfile dans `frontend/`. Pour ajouter une dépendance :

```sh
# Bibliothèque utilisée par le frontend.
npm install nom-du-package -w frontend

# Outil de développement du frontend.
npm install -D nom-du-package -w frontend

# Outil commun au dépôt.
npm install -D nom-du-package
```

Inclure les `package.json` et le lockfile modifiés dans la même PR. Les règles de mise à jour figurent dans [Choix de versions](#choix-de-versions).

## Commandes

### Démarrer et modifier l'interface

| Commande               | Usage                                                                               |
| ---------------------- | ----------------------------------------------------------------------------------- |
| `npm run dev`          | Commande par défaut : démarrer le frontend, l'API, PostgreSQL et Redis.             |
| `npm run dev:frontend` | Démarrer le frontend seul, avec une API déjà lancée ou sans appel API.              |
| `npm run dev:all`      | Démarrer le même environnement avec le worker pour les traitements en arrière-plan. |

Prérequis de `npm run dev` :

- Node.js et npm aux versions indiquées dans [Installation](#installation).
- JDK 25.
- Docker.

Le détail figure dans le [guide de développement commun](../developpement.md).
Pour se connecter à l'application, voir [Se connecter en local](../../README.md#se-connecter-en-local).

Ouvrir l'adresse `App` du message « Ready », <http://localhost:5173> si ce port est libre. Enregistrer une modification frontend actualise l'interface avec Vite. Arrêter la commande avec `Ctrl+C` ; les services Docker restent disponibles. Leur gestion est décrite dans le [guide commun](../developpement.md#arrêter-lenvironnement).

Si un autre processus utilise le port 5173, la commande choisit un port libre. Un port donné avec `--frontend-port` est imposé : s'il est occupé, la commande s'arrête. Par exemple, avec une API déjà lancée sur 8180 :

```sh
npm run dev:frontend -- --frontend-port 5273 --api-port 8180
```

Cette commande démarre seulement le frontend. La destination du proxy local vers l'API suit cet ordre de priorité :

1. L'option `--api-port`, qui vise `http://127.0.0.1:<port>`.
2. La variable `API_TARGET`, uniquement avec `dev:frontend`.
3. Sinon `http://127.0.0.1:8080`.

Avec `npm run dev` ou `npm run dev:all`, la commande lance l'API : `API_TARGET` est ignorée.

### Construire et consulter le build

```sh
npm run build
npm run preview -w frontend
```

Le build produit les fichiers statiques de la SPA dans `frontend/build/client/`. `preview` les sert localement, normalement sur <http://localhost:4173> ; lire l'adresse affichée si ce port est occupé. Il ne démarre pas de backend. Pour vérifier les échanges, garder une API locale accessible au proxy.

Les contraintes de render initial et les règles de routage de `/api/*` et `/cartes/*` figurent dans l'[architecture](architecture.md#exécution-de-la-spa).

### Contrôles ciblés

| Commande                    | Vérifications                                                                   |
| --------------------------- | ------------------------------------------------------------------------------- |
| `npm run typecheck`         | Types des scripts communs, génération des types de routes et types du frontend. |
| `npm run lint`              | Règles ESLint, dont les frontières entre modules.                               |
| `npm test -- frontend`      | Tests des frontières de modules, tests unitaires et tests de composants.        |
| `npm run check -- frontend` | Lint, types, tests frontend et build.                                           |
| `npm run check -- format`   | Vérification du formatage du dépôt.                                             |

`npm run format` applique le formatage du code et de la documentation du dépôt. `npm test` lance aussi les tests des scripts et du backend ; les blocs de `npm run check` sont décrits dans le [guide commun](../developpement.md#lancer-une-partie-des-contrôles).

Pour garder Vitest ouvert pendant le travail, utiliser `npm run test:watch -w frontend`. Pour les seuls tests de l'outillage frontend (frontières de modules, titres des routes, CSP), utiliser `npm run test:architecture -w frontend`.

Pour lancer un seul fichier de test Vitest, passer son chemin après `--` :

```sh
npm run test -w frontend -- src/features/auth/api.test.ts
```

Les commandes déclarées dans `frontend/package.json` se lancent avec `npm run <script> -w frontend` depuis la racine, par exemple `npm run test:watch -w frontend`. Depuis `frontend/`, `npm run <script>` suffit. Les commandes communes, comme `check`, restent à la racine. Le cas des tests navigateur est détaillé ci-dessous.

### Tests navigateur

```sh
npm run test:e2e
```

La commande **`npm run test:e2e` depuis la racine** :

1. Prépare une API de test avec PostgreSQL et Redis temporaires.
2. Pendant ce temps, construit le frontend et installe Chromium s'il manque.
3. Lance son serveur de prévisualisation sur un port libre et les tests Playwright dans Chromium.
4. Nettoie les ressources de l'API de test à la fin.

Docker et le JDK du projet sont nécessaires. Les prérequis complets figurent dans le [guide commun](../developpement.md#préparer-son-poste).

`npm run test:e2e -w frontend` lance directement Playwright : cette commande ne prépare ni le build ni l'API. Utiliser la commande racine pour la préparation complète.

Pour lancer un seul spec, passer son nom après `--` :

```sh
npm run test:e2e -- authentication.spec.ts
```

Les autres filtres figurent dans le [guide commun](../developpement.md).

Les scénarios et leurs limites sont décrits dans le [guide qualité](qualite.md#répartir-les-tests). Le rapport HTML Playwright est enregistré sous `frontend/playwright-report/`. Pour l'ouvrir après un échec, lancer depuis `frontend/` :

```sh
npx playwright show-report
```

Les résultats publiés sur GitHub sont décrits dans le [guide CI](../ci.md#comprendre-et-corriger-un-échec).

## Organisation du code

| Besoin                                                    | Référence                                                 |
| --------------------------------------------------------- | --------------------------------------------------------- |
| Trouver le bon dossier et comprendre ses responsabilités  | [Architecture frontend](architecture.md#responsabilités). |
| Ajouter une fonctionnalité, un formulaire ou un composant | [Conventions frontend](conventions.md).                   |
| Définir un appel API et ses options de cache              | [Couche API](api-layer.md).                               |
| Choisir les scénarios et les vérifications manuelles      | [Qualité frontend](qualite.md).                           |

Pour ajouter un composant shadcn, se placer dans `frontend/` :

```sh
npm exec shadcn -- add nom-du-composant
```

Le composant ajouté est du code source conservé et adapté dans le dépôt. Relire ce code et vérifier la sémantique HTML, le clavier, le focus et les styles après adaptation. Voir l'[installation shadcn avec React Router](https://ui.shadcn.com/docs/installation/react-router).

## API, cartes et supervision

Les appels API utilisent des chemins relatifs `/api/*`. Le proxy Vite les transmet à l'API locale. L'ordre de priorité de sa destination est décrit dans [Démarrer et modifier l'interface](#démarrer-et-modifier-linterface). Ces réglages concernent le serveur de développement, pas une URL compilée dans les composants.

Avec `npm run dev` ou `npm run dev:all`, le contrat et les types sont synchronisés après le redémarrage de l'API. Le frontend lancé seul ne surveille pas les modifications Java : suivre le [guide du contrat API](../api.md) pour synchroniser les types avec une API lancée séparément. Ne pas modifier `src/api/schema.d.ts` à la main.

| Intégration            | Règle et référence                                                                                                                                                                                                                             |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| API                    | Utiliser le client commun et les fonctions de la fonctionnalité selon la [couche API](api-layer.md). Garder le même build entre environnements, sans secret ni URL de serveur propre à test ou production.                                     |
| Carte                  | Utiliser MapLibre et PMTiles avec les ressources hébergées sous `/cartes/*`, selon l'[architecture commune](../architecture.md#cartographie-et-fichiers). Les fonds de démonstration des bibliothèques ne servent pas de fond à l'application. |
| Supervision navigateur | Réserver Faro au diagnostic technique. Avant toute activation, vérifier le filtrage des données et les instrumentations autorisées selon le [guide qualité](qualite.md#sécurité-et-confidentialité).                                           |

La présence d'une bibliothèque dans les dépendances ne suffit pas à établir qu'une intégration est configurée et vérifiée. Une variable `VITE_*` est publique : elle ne doit contenir aucun secret.

## Démarche en recette

Le bouton « Démarche » de la fiche d'un établissement est grisé « Bientôt ». En recette, on peut l'activer pour tester l'interface : le journal des actions est alors gardé dans le `localStorage` du navigateur, un journal par établissement. Ces données de test restent sur l'appareil, et la modale le dit.

Le drapeau se règle à la compilation :

```sh
VITE_LOCAL_FOLLOW_UP=true npm run build
```

- Il est désactivé par défaut, et seule la valeur `true` l'active.
- Sans drapeau, le code de la démarche n'entre pas dans le bundle.
- Ne jamais l'activer en production : la démarche d'un jeune appartient à son compte, pas à un navigateur.

La démarche côté serveur, liée au compte, remplacera ce journal local : le drapeau et le code qu'il charge seront alors supprimés.

## Choix de versions

Les versions directes sont fixées dans les deux `package.json` ; le lockfile fixe aussi les dépendances indirectes. Consulter ces fichiers pour connaître les versions utilisées.

Pour une mise à jour :

1. Choisir des publications stables compatibles entre elles, notamment pour React et React Router, TypeScript et ses outils, ou TanStack Query et ses Devtools.
2. Vérifier les plages de compatibilité déclarées par les outils concernés. Voir notamment les [compatibilités de typescript-eslint](https://typescript-eslint.io/users/dependency-versions/).
3. Mettre à jour les `package.json` et le lockfile dans la même PR. Résoudre les incompatibilités sans `--force` ni `--legacy-peer-deps`.
4. Régénérer les types API si les outils du contrat ont changé, puis exécuter les contrôles concernés. Après une mise à jour de Playwright, vérifier aussi la version du navigateur installé.

## Vérifier son travail

Pour un changement applicatif frontend, lancer `npm run check -- frontend format` et vérifier le comportement modifié selon le [guide qualité](qualite.md). Ajouter les contrôles communs pertinents : `npm run check` vérifie aussi le contrat et le backend ; `npm run test:e2e` vérifie le raccordement dans le navigateur.

Pour les documents ou templates seuls, relire le contenu, les liens, les exemples et le formatage, conformément aux [vérifications locales du CONTRIBUTING](../../CONTRIBUTING.md#vérifications-locales).

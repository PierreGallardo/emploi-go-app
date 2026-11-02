# Développement local

Ce guide explique comment préparer l'environnement de développement, lancer l'application et vérifier ses modifications.

Exécuter les commandes depuis la racine du dépôt.

Pour découvrir le projet et ses autres guides, consulter le [sommaire de la documentation](README.md).

## Préparer son poste

Systèmes pris en charge : macOS, Linux et Windows.

| Outil   | Version ou configuration                                                                                                                                                                                                                                   |
| ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| nvm     | Installé une seule fois sur le poste, selon [sa documentation](https://github.com/nvm-sh/nvm#installing-and-updating). Sous Windows, ou avec un `prefix` npm global, [fnm](https://github.com/Schniz/fnm) lit le même `.nvmrc` (`fnm install`, `fnm use`). |
| Node.js | 24, à partir de 24.19.0. Node 25 est refusé. Le fichier `.nvmrc` fixe la version : `nvm install` l'installe.                                                                                                                                               |
| npm     | 11, à partir de 11.17.0. Pour l'installer : `npm install -g npm@11.17.0`.                                                                                                                                                                                  |
| Java    | JDK 25, accessible depuis le terminal. Temurin est recommandé, car la CI l'utilise.                                                                                                                                                                        |
| Docker  | Docker Desktop (avec WSL 2 sous Windows), ou Docker Engine avec Compose v2 sous Linux. `docker info` doit répondre sans erreur.                                                                                                                            |

Le dépôt fournit le Maven Wrapper, qui télécharge la version de Maven utilisée par le projet.

Après avoir cloné le dépôt, installer Node.js et les dépendances JavaScript :

```sh
nvm install
npm ci
```

Dans chaque nouveau terminal, `nvm use` active la version du dépôt. Pour l'activer automatiquement en entrant dans le dossier, ajouter le hook de la documentation nvm « [Calling nvm use automatically in a directory with a .nvmrc file](https://github.com/nvm-sh/nvm#calling-nvm-use-automatically-in-a-directory-with-a-nvmrc-file) ».

Une autre version de Node.js est refusée :

- `npm ci` échoue, à cause du champ `engines` du `package.json` et d'`engine-strict` dans `.npmrc` ;
- toute commande `npm run` s'arrête avec une erreur, à cause du champ `devEngines`.

## Démarrer l'application

Avec Docker démarré :

```sh
npm run dev
```

La commande vérifie d'abord que Docker répond. Elle prépare ensuite PostgreSQL et Redis, compile le backend, puis lance l'API et le frontend. Attendre le message **« Ready »** dans le terminal :

```text
Ready in 19.2s
  App  http://localhost:5173
  API  http://localhost:8080/swagger-ui/index.html
```

| Ligne    | Adresse                                                                     |
| -------- | --------------------------------------------------------------------------- |
| `App`    | Interface web.                                                              |
| `API`    | Swagger de l'API : consulter et essayer les routes.                         |
| `Worker` | Worker, affiché seulement quand la commande le lance (`npm run dev:all`).   |
| `Mail`   | Boîte email locale, affichée seulement avec `REGISTRATION_MAIL_MODE=local`. |

Le terminal affiche des messages courts, en anglais. Les sorties de Maven et de Docker restent cachées, sauf en cas d'erreur. Les logs Spring sont réduits : pas de bannière, niveau `WARN`, sauf `fr.emploigo` et Flyway en `INFO`. Pour les rétablir, définir par exemple `LOGGING_LEVEL_ROOT=info` dans `backend/.env`.

Au premier lancement, la commande crée `backend/.env` avec un compte de démo. Pour se connecter à la carte, suivre [Se connecter en local](../README.md#se-connecter-en-local).

### Choisir les composants à lancer

Le worker exécute les traitements en arrière-plan. Le lancer pour travailler sur les parcours qui en ont besoin.

| Commande               | Composants lancés                                           |
| ---------------------- | ----------------------------------------------------------- |
| `npm run dev`          | Frontend, API, PostgreSQL et Redis.                         |
| `npm run dev:all`      | Même environnement, avec le worker.                         |
| `npm run dev:frontend` | Frontend seul, par exemple si l'API tourne déjà dans l'IDE. |

Une seule session avec backend tourne dans une copie du dépôt. Relancer `npm run dev` ou `npm run dev:all` arrête proprement la session précédente de cette copie et prend sa place.

Les lancements depuis l'IDE ou Maven sont décrits dans le [guide backend](backend/developpement.md#avec-maven-ou-lide).

### Ports utilisés

| Service               | Port par défaut | Pour le fixer                     |
| --------------------- | --------------- | --------------------------------- |
| Frontend              | 5173            | `--frontend-port`                 |
| API                   | 8080            | `--api-port`                      |
| Supervision de l'API  | 8081            | `--api-management-port`           |
| Worker                | 8090            | `--worker-port`                   |
| Supervision du worker | 8091            | `--worker-management-port`        |
| PostgreSQL            | 5432            | `DB_PORT` dans `backend/.env`     |
| Redis                 | 6379            | `REDIS_PORT` dans `backend/.env`  |
| Boîte email locale    | 8025            | Port libre si pris (mode `local`) |

- Un port par défaut libre est utilisé.
- Un port par défaut occupé, par exemple par une autre copie du dépôt, est remplacé par un port libre. Le message « Ready » affiche les adresses réelles.
- PostgreSQL et Redis gardent le port de leurs conteneurs déjà démarrés.
- Un port fixé par une option ou dans `backend/.env` est imposé. S'il est occupé, la commande s'arrête et nomme l'option ou la variable.
- Les tests E2E et la vérification du contrat prennent leurs propres ports libres.

Ajouter les options après `--`, par exemple :

```sh
npm run dev -- --frontend-port 5273 --api-port 8180 --api-management-port 8181
```

Le frontend, son proxy et la synchronisation automatique du contrat suivent les ports réels. Les liens des emails (`PUBLIC_APP_ORIGIN`) suivent aussi le port du frontend, tant que cette variable garde sa valeur par défaut. Les options du worker et les lancements directs sont détaillés dans le [guide backend](backend/developpement.md#ports-et-supervision).

### Travailler dans plusieurs copies du dépôt

Chaque copie du dépôt, par exemple un worktree Git, a sa propre session, ses ports et ses services :

- La copie principale garde ses conteneurs et ses données, dans le projet Docker Compose `emploi-go-local`.
- Chaque worktree a ses propres conteneurs, dans le projet `emploi-go-<dossier>`. Sa base démarre sans les données de la copie principale.
- `npm run services:down` dans une copie arrête seulement ses conteneurs.
- Les conteneurs et la base d'un worktree supprimé sont retirés automatiquement au lancement suivant de `npm run dev`, `services:up`, `check` ou `test:e2e`, depuis n'importe quelle copie du dépôt. Ceux de la copie principale ne le sont jamais.
- Les conteneurs temporaires laissés par un contrôle ou des E2E interrompus sont retirés de la même façon.

Les cookies ne distinguent pas les ports. Deux copies ouvertes sur `localhost` dans le même navigateur partagent donc leurs cookies de session. Ouvrir la seconde par `http://127.0.0.1:<port>` sépare les sessions.

## Modifier le code

Garder la commande de développement ouverte, modifier les fichiers et enregistrer.

| Modification                     | Ce qui se passe automatiquement                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------------------------- |
| Frontend                         | Vite actualise l'interface.                                                                       |
| Sources ou ressources du backend | Le script recompile le backend, puis DevTools redémarre l'API et le worker lancés par la session. |
| `backend/pom.xml`                | Le script reconstruit le backend et relance les processus Java avec leurs dépendances.            |
| Contrat de l'API                 | Après le redémarrage de l'API, le contrat OpenAPI et les types frontend sont synchronisés.        |

**Ces étapes sont automatiques.** Attendre le message « Backend reloaded in … » avant de vérifier le résultat. Les tests se lancent séparément.

| Message                                                     | Signification                                                                        |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `Backend reloaded in 6.1s.`                                 | Le backend modifié a redémarré et répond.                                            |
| `API contract updated: …`                                   | Le contrat et les types frontend ont changé.                                         |
| `Backend build failed. Fix the error and save to retry.`    | La compilation a échoué. Seules les erreurs du compilateur suivent.                  |
| `Backend failed to start. Fix the error and save to retry.` | Le backend compilé n'a pas démarré.                                                  |
| `API is unhealthy: check that Docker is running.`           | L'API ne répond plus à son contrôle de santé, souvent parce que Docker s'est arrêté. |

En cas d'erreur du backend, corriger le fichier concerné et enregistrer : la commande de développement réessaie automatiquement. Si la compilation échoue, l'API et le worker de la session sont arrêtés. Le frontend et les services Docker restent disponibles ; le contrat et les types restent inchangés pendant l'échec.

### Si la modification change l'API

Inclure dans la même PR les fichiers générés qui ont changé et les adaptations frontend nécessaires. Une modification interne du Java peut laisser le contrat inchangé.

Les fichiers `contracts/openapi.json` et `frontend/src/api/schema.d.ts` sont générés ; les commandes suivantes permettent de les actualiser ou de les vérifier :

| Commande                    | Usage                                                                                                                                      |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `npm run api:sync`          | Synchroniser le contrat et les types depuis une API locale à jour ; port 8080 par défaut, ou option `--api-port` et variable `API_TARGET`. |
| `npm run api:generate`      | Régénérer les types à partir du contrat enregistré.                                                                                        |
| `npm run check -- contract` | Construire une API temporaire et vérifier que le contrat et les types enregistrés correspondent au code.                                   |

Voir le [guide du contrat API](api.md) pour le détail.

## Vérifier ses modifications

**Les tests peuvent tourner pendant la session de développement.** Chaque usage compile le backend dans son propre dossier :

| Dossier                    | Utilisation                                     |
| -------------------------- | ----------------------------------------------- |
| `backend/target-dev/`      | Session de développement.                       |
| `backend/target/`          | Tests du backend (`npm test`, `npm run check`). |
| `backend/target-isolated/` | Vérification du contrat et tests E2E.           |

Une commande qui trouve son dossier occupé par une autre attend son tour au lieu d'échouer. Maven lancé directement n'attend pas : ne pas le lancer pendant une commande npm qui compile `backend/target/`.

| Commande           | Vérifications                                                                                          | Prérequis supplémentaires                          |
| ------------------ | ------------------------------------------------------------------------------------------------------ | -------------------------------------------------- |
| `npm test`         | Tests rapides, en parallèle : scripts, frontend et tests unitaires du backend (`mvn test`).            | Poste préparé (`npm ci`, JDK) ; Docker non requis. |
| `npm run check`    | Tout avant une PR, en parallèle : scripts, formatage, frontend, contrat API et backend (`mvn verify`). | Docker démarré.                                    |
| `npm run test:e2e` | Tests dans Chromium avec le frontend, l'API et leurs services.                                         | Docker démarré.                                    |

### Lancer une partie des contrôles

`npm run check` regroupe ses contrôles en blocs :

| Bloc       | Contrôles                                                                                       |
| ---------- | ----------------------------------------------------------------------------------------------- |
| `scripts`  | Types et tests des scripts du dépôt.                                                            |
| `format`   | Formatage du dépôt, sans rien modifier.                                                         |
| `frontend` | Lint, types, tests, build et budget de taille du frontend.                                      |
| `contract` | Contrat API et types frontend, comparés à une API temporaire construite depuis le code courant. |
| `backend`  | Maven `verify` : tous les tests du backend, dont ceux avec PostgreSQL et Redis temporaires.     |

Passer un ou plusieurs noms de blocs après `--` pour ne lancer qu'eux :

```sh
npm run check -- frontend
npm run check -- scripts format
```

`npm test` accepte de même les blocs `scripts`, `frontend` et `backend`, par exemple `npm test -- backend` pour les seuls tests unitaires du backend.

Un bloc réussi affiche seulement une ligne `✓ nom durée`. Un bloc en échec affiche sa sortie complète ; les autres blocs vont jusqu'au bout.

### Tests navigateur

`test:e2e` prépare lui-même l'application de test. Au premier lancement, il installe Chromium. Il construit le frontend pendant que le backend temporaire démarre, puis sert le frontend sur un port libre. Le bloc `backend`, le contrat et les E2E utilisent des services temporaires distincts des services de développement.

Utiliser la commande racine pour préparer aussi les comptes et les fournisseurs fictifs des E2E. Les tests n'envoient aucun email externe et n'utilisent pas les secrets de `backend/.env`. Pour vérifier une livraison réelle, suivre la [configuration Resend](backend/developpement.md#tester-linscription-avec-resend).

Pour relancer les tests concernés, sélectionner un fichier ou filtrer le nom des scénarios :

```sh
npm run test:e2e -- authentication.spec.ts
npm run test:e2e -- --grep 'connexion'
```

Les arguments après `--` sont transmis à Playwright. Sans filtre, `npm run test:e2e` exécute toute la suite.

Choisir les contrôles selon la modification, comme indiqué dans les [règles de contribution](../CONTRIBUTING.md#vérifications-locales). Le [guide CI](ci.md) décrit les contrôles exécutés sur GitHub.

## Arrêter l'environnement

Dans le terminal de développement, **`Ctrl+C` arrête le frontend, l'API et le worker lancés par cette commande**. Les services Docker démarrés restent disponibles pour la prochaine session.

- Un second `Ctrl+C` force l'arrêt.
- Fermer le terminal arrête la session comme `Ctrl+C`.
- Si la session disparaît malgré tout, l'API, le worker et Vite s'arrêtent seuls.

Aucun processus ni fichier n'est à nettoyer à la main.

Pour arrêter aussi les services Docker de cette copie du dépôt :

```sh
npm run services:down
```

Cette commande conserve les données PostgreSQL et celles de Redis (volume `redis-data`). Les compteurs de quota survivent donc à un redémarrage, jusqu'à leur expiration, au plus 30 minutes. `npm run services:reset` arrête les services de la copie et efface leurs données : la base PostgreSQL, que Flyway recrée au lancement suivant, et les compteurs de Redis. Arrêter d'abord `npm run dev`, qui perdrait sa base. L'utiliser après la modification en place d'une migration ([données](backend/donnees.md#faire-évoluer-une-migration)), ou quand la base locale ne correspond plus aux migrations. Ne pas l'utiliser pour contourner un quota pendant un test de parcours.

| Commande                 | Usage                                                               |
| ------------------------ | ------------------------------------------------------------------- |
| `npm run services:up`    | Démarrer PostgreSQL et Redis seuls, et afficher leurs ports.        |
| `npm run services:down`  | Arrêter les services en conservant les données PostgreSQL et Redis. |
| `npm run services:reset` | Arrêter les services et effacer les données PostgreSQL et Redis.    |

Pour voir leur état ou leurs logs, utiliser Docker Desktop, ou `docker compose -p <projet> ps` et `docker compose -p <projet> logs`. Le projet est `emploi-go-local` dans la copie principale, `emploi-go-<dossier>` dans un worktree. Ajouter aussi `-p emploi-go-<dossier>` aux commandes `docker compose -f backend/compose.yml` des autres guides lancées depuis un worktree.

## Personnaliser la configuration

Le premier `npm run dev` crée `backend/.env` depuis `backend/.env.example`. Pour modifier un réglage :

1. Modifier la valeur dans `backend/.env`.
2. Relancer la commande de développement.

Un `backend/.env` existant n'est jamais modifié par les scripts. Le fichier contient les réglages du poste et est ignoré par Git. Écrire directement les valeurs. Une référence comme `${AUTRE_VARIABLE}` n'est pas remplacée : Docker Compose remplace ses propres variables, les scripts Node, non.

Les commandes `dev`, `test` et `check` chargent ce fichier. Une variable déjà définie dans le terminal est prioritaire.

Le bloc `contract` et `test:e2e` préparent leurs propres connexions temporaires. Ils ignorent les réglages applicatifs du poste (`backend/.env` ou terminal) : le contrat est vérifié comme en CI. Pour un lancement du backend depuis Maven, Java ou l'IDE, renseigner les variables dans l'environnement de lancement, selon le [guide backend](backend/developpement.md#configuration-locale).

## Dépannage

| Problème                                                           | Action                                                                                                                                                                                 |
| ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Une commande `npm` refuse la version de Node.js                    | Lancer `nvm use` dans ce terminal, ou `nvm install` si la version de `.nvmrc` n'est pas encore installée.                                                                              |
| Java introuvable ou mauvaise version                               | Vérifier `java -version`. Si `JAVA_HOME` est défini, il doit pointer vers un JDK 25.                                                                                                   |
| Docker ne répond pas                                               | Démarrer Docker et vérifier que `docker info` affiche les informations du serveur. Sous Windows, utiliser le canal local Docker Desktop ; ne pas activer le port TCP non chiffré 2375. |
| Les tests ne trouvent pas un moteur Docker rootless sous Linux     | Définir `DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock` dans le terminal des tests. Le contexte de la CLI Docker ne suffit pas toujours aux clients Java.                          |
| Un port fixé est déjà utilisé                                      | Retirer l'option ou la variable pour laisser la commande choisir un port libre, ou en choisir un autre. Si l'API est déjà lancée dans l'IDE, utiliser `dev:frontend`.                  |
| La session précédente ne s'arrête pas                              | Fermer son terminal, puis relancer la commande.                                                                                                                                        |
| Contrat ou types signalés comme obsolètes                          | Attendre une compilation et un redémarrage réussis, puis lancer `npm run api:sync`.                                                                                                    |
| Un changement de `.env` n'est pas pris en compte                   | Relancer la commande et vérifier qu'une variable du terminal ne remplace pas celle du fichier.                                                                                         |
| La connexion PostgreSQL échoue après un changement de mot de passe | Modifier `.env` ne change pas le mot de passe enregistré dans une base déjà créée. Aligner le compte PostgreSQL et la configuration locale.                                            |

Un lock laissé par une commande arrêtée brutalement est repris automatiquement : aucun fichier n'est à supprimer.

Les ports et les lancements personnalisés sont détaillés dans le [guide backend](backend/developpement.md). Pour comprendre ou modifier les scripts eux-mêmes, consulter le [guide de l'outillage](outillage.md).

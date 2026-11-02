# Backend Emploi Go

Le backend fournit une API Spring Boot et un worker Java. Un seul module Maven produit leur JAR commun ; chaque rôle s'exécute dans un processus distinct, avec sa propre configuration.

Le [sommaire commun](../docs/README.md) ouvre le périmètre V1, l'architecture des services et les règles de sécurité, d'exploitation et de secrets. Les guides ci-dessous décrivent leurs responsabilités dans le code Java.

## Démarrer

Les versions requises sont indiquées dans le [guide de développement](../docs/developpement.md#préparer-son-poste). Le Maven Wrapper fournit Maven.

Depuis la racine du dépôt, avec Docker démarré :

```sh
npm ci
npm run dev
```

La commande prépare PostgreSQL et Redis, compile et démarre l'API et le frontend, surveille les sources Java et synchronise le contrat OpenAPI et les types frontend. `npm run dev:all` lance aussi le worker. Le [guide commun](../docs/developpement.md) décrit la boucle de développement.

L'API locale écoute sur `http://localhost:8080` si ce port est libre ; le message « Ready » donne son adresse réelle. Swagger UI est disponible sur `/swagger-ui/index.html`. `GET /api/ping` renvoie `{"status":"ok"}`. Le worker n'expose pas cette route ni la documentation de l'API.

## Guides

| Guide                                             | Responsabilité                                                      |
| ------------------------------------------------- | ------------------------------------------------------------------- |
| [Développement](../docs/backend/developpement.md) | Poste de travail, lancement, profils, configuration et versions.    |
| [Architecture](../docs/backend/architecture.md)   | Domaines, couches, dépendances et séparation API/worker.            |
| [Conventions](../docs/backend/conventions.md)     | Organisation du Java, visibilité, erreurs et logs.                  |
| [API](../docs/backend/api.md)                     | Routes, DTO, validation, autorisations et contrat HTTP.             |
| [Données](../docs/backend/donnees.md)             | PostgreSQL, JPA, migrations, transactions, sessions et Redis.       |
| [Worker](../docs/backend/worker.md)               | Tâches persistantes, retries, idempotence et intégrations externes. |
| [Qualité](../docs/backend/qualite.md)             | Tests, frontières d'architecture et contrôles avant contribution.   |

Les commandes de génération sont dans le [guide du contrat API](../docs/api.md), les contrôles GitHub dans le [guide CI](../docs/ci.md) et les scripts TypeScript dans le [guide de l'outillage](../docs/outillage.md). Suivre les [règles de contribution](../CONTRIBUTING.md) pour les branches, les PR et la revue.

## Fonctionnalités

L'API prend en charge l'authentification locale et Google, l'inscription avec confirmation de l'email, la récupération du mot de passe et la recherche des établissements de la carte. Les [règles API](../docs/backend/api.md) décrivent les échanges et les accès ; le [guide cartographique](../docs/cartographie.md) précise la recherche et les établissements visibles en local.

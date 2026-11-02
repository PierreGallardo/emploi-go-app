# Documentation Emploi Go

Ces guides décrivent l'état cible du projet. L'avancement est suivi dans les issues GitHub.

Ce sommaire est le point d'entrée unique de la documentation. Les documents communs fixent les règles du projet. Les guides frontend et backend précisent leur application dans le code.

## Première lecture

1. Lire le [périmètre fonctionnel](perimetre-v1.md) pour comprendre les utilisateurs, les parcours et les limites du produit.
2. Lire l'[architecture globale](architecture.md) pour situer le frontend, l'API, le worker et leurs services.
3. Préparer son poste avec le [guide de développement](developpement.md), puis suivre les [règles de contribution](../CONTRIBUTING.md).
4. Choisir les [guides frontend ou backend](#guides-frontend-et-backend) selon le code à modifier.

## Décisions et responsabilités communes

| Besoin                                                                        | Guide de référence                          |
| ----------------------------------------------------------------------------- | ------------------------------------------- |
| Comprendre les utilisateurs, les parcours et les fonctions retenues           | [Périmètre fonctionnel](perimetre-v1.md)    |
| Comprendre les composants, leurs échanges et la séparation des environnements | [Architecture globale](architecture.md)     |
| Protéger les comptes, les données et les accès                                | [Sécurité](securite.md)                     |
| Déployer, surveiller, sauvegarder et restaurer l'application                  | [Exploitation](exploitation.md)             |
| Conserver, distribuer, remplacer et récupérer les secrets techniques          | [Secrets et coffre](secrets.md)             |
| Répartir les fichiers entre l'application et son déploiement                  | [Organisation des dépôts](depots.md)        |
| Retrouver les informations manquantes et les choix non tranchés               | [Questions ouvertes](questions-ouvertes.md) |

## Travailler dans le monorepo

| Besoin                                                                   | Guide de référence                         |
| ------------------------------------------------------------------------ | ------------------------------------------ |
| Installer les outils, lancer l'application et vérifier ses modifications | [Développement local](developpement.md)    |
| Synchroniser le contrat OpenAPI et les types TypeScript avec le backend  | [Contrat API et types du frontend](api.md) |
| Afficher la carte de Dijon, sa recherche et ses ressources               | [Cartographie](cartographie.md)            |
| Comprendre les contrôles GitHub Actions et lire leurs résultats          | [Automatisations GitHub Actions](ci.md)    |
| Modifier les commandes partagées en TypeScript                           | [Scripts du dépôt](outillage.md)           |
| Préparer une contribution, une branche et une PR, puis suivre la revue   | [CONTRIBUTING.md](../CONTRIBUTING.md)      |

## Guides frontend et backend

| Besoin                                                          | Frontend                                                | Backend                                              |
| --------------------------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------------- |
| Comprendre l'organisation du code et les dépendances autorisées | [Architecture](frontend/architecture.md)                | [Architecture](backend/architecture.md)              |
| Écrire et organiser le code d'une fonctionnalité                | [Conventions](frontend/conventions.md)                  | [Conventions Java](backend/conventions.md)           |
| Installer, lancer et configurer la partie concernée             | [Développement](frontend/developpement.md)              | [Développement](backend/developpement.md)            |
| Utiliser ou concevoir les échanges HTTP                         | [Couche API et cache navigateur](frontend/api-layer.md) | [API HTTP, DTO et contrôles d'accès](backend/api.md) |
| Choisir les tests et les vérifications utiles                   | [Qualité](frontend/qualite.md)                          | [Qualité](backend/qualite.md)                        |

Trois guides complètent les règles du backend :

- [Authentification](backend/authentification.md) : authentification, sessions et accès côté backend.
- [Données et transactions](backend/donnees.md) : PostgreSQL, migrations, JPA, transactions, sessions et Redis.
- [Worker et traitements persistants](backend/worker.md) : planification des tâches, exécution, retry et prévention des effets en double.

Les README du [frontend](../frontend/README.md) et du [backend](../backend/README.md) servent aussi de points d'entrée depuis leurs dossiers de code.

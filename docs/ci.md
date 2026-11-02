# Automatisations GitHub Actions

Cette page présente les automatisations du dépôt et explique comment lire leurs résultats. Chaque workflow définit quand démarrer et quels jobs exécuter ; un job regroupe plusieurs étapes sur une même machine.

## Workflows

| Workflow               | Fonction                                                                                  | Déclenchement                                                      | Fichier                                         |
| ---------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------- |
| Vérifications          | Compiler le projet, vérifier le code, les tests et le contrat API, construire les images. | PR, push sur `main`, lancement manuel ou appel par la publication. | [`checks.yml`](../.github/workflows/checks.yml) |
| Publication des images | Publier les images backend et frontend sur GHCR, puis leur ajouter le tag de version.     | Push d'un tag Git `vX.Y.Z-rc.N` ou `vX.Y.Z`.                       | [`images.yml`](../.github/workflows/images.yml) |

## Vérifications

Le workflow démarre à l'ouverture ou à la réouverture d'une PR, à l'ajout de commits sur cette PR, après un push sur `main` et sur demande depuis l'onglet **Actions**. Il vérifie aussi les PR limitées à la documentation. La [publication des images](#publication-des-images) l'appelle aussi sur le commit d'un tag de version candidate.

Une nouvelle exécution sur la même PR annule la précédente si elle est encore en cours. Chaque job a une durée maximale définie dans le workflow.

### Contrôles exécutés

| Contrôle                         | Commande                                   | Vérifications                                                                                                                           |
| -------------------------------- | ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| Frontend et outils               | `npm run check -- scripts format frontend` | Types et tests des scripts, formatage, lint, typage, tests frontend, build et [budget de taille](frontend/qualite.md#budget-de-taille). |
| Backend                          | `npm run check -- backend`                 | Compilation Java, tests unitaires, HTTP, d'architecture et d'intégration de l'API et du worker.                                         |
| Contrat API                      | `npm run check -- contract`                | Construction d'une API temporaire et comparaison du contrat OpenAPI et des types avec les fichiers versionnés.                          |
| Raccordement frontend et backend | `npm run test:e2e`                         | Construction du frontend, tests Chromium et sous-ensemble WebKit (`@webkit`) : parcours réels contre le backend.                        |
| Image backend et image frontend  | `docker build`                             | Construction des deux images Docker pour `linux/amd64`, sans publication.                                                               |

### Vérifier sur son poste

Depuis la racine, utiliser la commande du contrôle concerné. Pour vérifier l'ensemble :

```sh
npm run check
npm run test:e2e
```

Docker doit être démarré : Testcontainers lance des PostgreSQL et Redis temporaires pour les tests backend. La CI a la même exigence.

`npm run check` lance en parallèle les mêmes blocs que la CI : `scripts`, `format`, `frontend`, `contract` et `backend`. Pour reproduire un seul contrôle, passer les noms de ses blocs après `--`, comme dans le tableau. Le [guide de développement](developpement.md#lancer-une-partie-des-contrôles) décrit chaque bloc.

Il lit `backend/.env` en local. Les tests navigateur se lancent à part avec `npm run test:e2e`. Les prérequis sont dans le [guide de développement](developpement.md#vérifier-ses-modifications).

Adapter les vérifications locales au changement, selon les [règles de contribution](../CONTRIBUTING.md#vérifications-locales).

### Construire les images sur son poste

Depuis la racine, avec Docker démarré :

```sh
docker build -t emploi-go-backend:local backend
docker build -t emploi-go-frontend:local -f frontend/Dockerfile .
```

Chaque image a son contexte de construction : le dossier `backend` pour le backend, la racine pour le frontend, qui a besoin du `package-lock.json` du workspace. `backend/.dockerignore` et `frontend/Dockerfile.dockerignore`, propre au Dockerfile du frontend, ne laissent entrer dans le contexte que les fichiers nécessaires : ni `.env`, ni `node_modules`, ni résultat de compilation.

L'image construite correspond à l'architecture du poste. La CI construit pour `linux/amd64`, celle du serveur. Pour reproduire la construction de la CI sur un Mac Apple Silicon, ajouter `--platform linux/amd64`. La compilation sous émulation est plus lente.

## Comprendre et corriger un échec

1. Depuis la PR, ouvrir le détail du contrôle en échec, ou retrouver l'exécution dans l'onglet **Actions**.
2. Ouvrir la première étape en échec et lire ses logs.
3. Télécharger les rapports utiles dans la rubrique **Artifacts** de l'exécution, lorsqu'ils sont disponibles.
4. Reproduire le problème avec la commande du tableau, corriger, puis relancer la vérification sur son poste.
5. Pousser la correction sur la même branche : GitHub Actions relance les contrôles de la PR.

| Rapport                | Contenu                                                         | Conservation |
| ---------------------- | --------------------------------------------------------------- | ------------ |
| `backend-test-reports` | Rapports des tests unitaires et d'intégration du backend.       | 7 jours.     |
| `playwright-report`    | Rapport des tests navigateur, sans trace ni capture par défaut. | 7 jours.     |

Ces rapports sont déposés lorsque l'étape de tests correspondante échoue et que les fichiers ont été produits. Si l'erreur survient avant les tests, consulter les logs de l'étape concernée.

Si le contrôle du contrat signale un écart avec les fichiers versionnés, suivre le [guide du contrat API](api.md) pour les synchroniser et les inclure dans le commit. Le workflow vérifie ces fichiers sans les modifier dans la branche.

## Environnement et cache

Les jobs du workflow **Vérifications** utilisent chacun une machine virtuelle Ubuntu temporaire et une copie du dépôt. Ils peuvent s'exécuter en parallèle ; leurs fichiers de travail ne sont pas partagés.

- Les versions de Node.js, npm et Java sont fixées dans le workflow.
- Docker est disponible sur la machine ; le workflow le vérifie avant les tests backend.
- PostgreSQL et Redis démarrent dans des conteneurs temporaires pour les tests qui en ont besoin.
- Les contrôles utilisent leur propre configuration, sans fichier `.env` personnel ni identifiants de production.
- Les tests navigateur utilisent Chromium et un seul worker Playwright.

Le cache réutilise des fichiers téléchargés lors d'une précédente exécution :

| Cache | Fichiers réutilisés                                           | Travail refait à chaque exécution                                                |
| ----- | ------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| npm   | Paquets téléchargés par npm.                                  | `npm ci` réinstalle les dépendances dans `node_modules` pour les jobs concernés. |
| Maven | Bibliothèques et plugins téléchargés dans `~/.m2/repository`. | Maven compile le backend et exécute les vérifications demandées.                 |

`package-lock.json` et `backend/pom.xml` servent à identifier les caches correspondants. Si aucun cache compatible n'est disponible, les outils téléchargent les fichiers nécessaires. `node_modules` et les résultats de compilation du backend ne sont pas conservés dans ces caches.

Les constructions d'images des **Vérifications** utilisent le cache GitHub Actions de Buildx, une portée par image. Elles réutilisent les couches dont les fichiers n'ont pas changé, par exemple les dépendances npm tant que `package-lock.json` est identique. La publication reconstruit sans ce cache : il est aussi écrit par des jobs qui exécutent le code des dépendances, et une couche altérée finirait dans l'image publiée.

### Monter de version Node.js

Modifier ensemble les quatre endroits suivants :

1. `.nvmrc` ;
2. le champ `engines` de `package.json` ;
3. les `node-version` du workflow `.github/workflows/checks.yml` ;
4. l'image Node.js de `frontend/Dockerfile`, avec son tag et son digest. Son npm doit respecter le champ `engines`.

## Publication des images

Le workflow publie les images dans le registre privé GitHub Container Registry (GHCR) de l'organisation. Il ne déploie rien : la sélection et le déploiement suivent la [livraison et recette](exploitation.md#livraison-et-recette).

| Image                                                | Contenu                                                                                                                  |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `ghcr.io/mission-locale-de-dijon/emploi-go-backend`  | JAR du backend, lancé par l'API et par le worker selon le profil.                                                        |
| `ghcr.io/mission-locale-de-dijon/emploi-go-frontend` | Fichiers du frontend servis par Caddy, qui transmet `/api/` à l'API, à l'adresse `API_UPSTREAM` (`api:8080` par défaut). |

### Tag de version candidate

Un push de tag `vX.Y.Z-rc.N` :

1. Vérifie que le commit tagué appartient à `main` ou à une branche `maintenance/<série>`.
2. Relance tout le workflow **Vérifications** sur ce commit.
3. Si toutes les vérifications passent, construit et publie les deux images avec deux tags : le nom du tag Git et `sha-<SHA complet du commit>`.
4. Affiche le digest de chaque image dans le résumé de l'exécution.

Un commit n'a qu'une version candidate : la publication échoue si son tag `sha-<SHA>` existe déjà. Le tag de version finale désigne ainsi toujours l'image testée en recette.

### Tag de version finale

Un push de tag `vX.Y.Z`, posé sur le commit d'une version candidate acceptée, ajoute ce tag aux images déjà publiées pour ce commit. Le workflow retrouve chaque image par son tag `sha-<SHA>`, sans rien reconstruire, et vérifie que le nouveau tag désigne le même digest. Il échoue si aucune version candidate n'a été publiée pour ce commit.

## Contrôles de sécurité

Ces contrôles s'ajoutent aux vérifications ci-dessus. Tous les outils sont gratuits. L'analyse des secrets native de GitHub est payante sur un dépôt privé, d'où l'usage de gitleaks.

| Outil                     | Ce qu'il contrôle                                 | Pourquoi                                                                  |
| ------------------------- | ------------------------------------------------- | ------------------------------------------------------------------------- |
| Dependabot                | Dépendances npm, Maven, Docker et GitHub Actions. | Proposer des mises à jour avant que les failles connues s'accumulent.     |
| Trivy                     | Dépendances et images Docker.                     | Détecter les vulnérabilités connues dans ce que l'application embarque.   |
| gitleaks                  | Secrets commités, en CI et en hook pre-commit.    | Bloquer un secret avant qu'il entre dans l'historique Git.                |
| Semgrep Community Edition | Code source.                                      | Repérer les motifs de code dangereux (injection, mauvaise configuration). |
| Titre de PR               | Format Conventional Commits.                      | Garder un historique lisible et des notes de version fiables.             |

Voir le [guide de sécurité](securite.md) pour les règles que ces outils appliquent.

## Modifier un workflow

- Modifier le fichier concerné dans [`.github/workflows/`](../.github/workflows/) par une PR et vérifier son exécution.
- Fixer les actions utilisées à un identifiant de commit, avec leur version en commentaire.
- Limiter les permissions aux besoins du workflow. **Vérifications** dispose d'un accès en lecture au contenu du dépôt. **Publication des images** n'ajoute l'écriture dans GHCR qu'aux jobs qui publient ou ajoutent un tag, avec le seul `GITHUB_TOKEN` de l'exécution.
- Mettre à jour cette page lorsque les déclencheurs, les jobs ou les commandes changent. Pour un nouveau workflow, ajouter une ligne au tableau et une section décrivant son fonctionnement.

Références : [workflows GitHub Actions](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows), [cache npm](https://github.com/actions/setup-node#caching-global-packages-data), [cache Maven](https://github.com/actions/setup-java#caching), [Playwright en CI](https://playwright.dev/docs/ci), [cache Buildx GitHub Actions](https://docs.docker.com/build/cache/backends/gha/) et [GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

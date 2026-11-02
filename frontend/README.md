# Frontend Emploi Go

Application web responsive pour les jeunes accompagnés par la Mission Locale de Dijon. Le frontend utilise React, TypeScript, React Router en mode framework SPA et Vite ; il communique avec l'API Java par HTTP.

Le frontend propose la connexion locale ou Google, l'inscription avec confirmation de l'email, la récupération du mot de passe et la carte interactive de Dijon. Le [sommaire commun](../docs/README.md) présente le périmètre V1 et les décisions d'architecture, de sécurité et d'exploitation.

## Démarrer

Prérequis : voir le [guide de développement](../docs/developpement.md#préparer-son-poste). Depuis la racine du dépôt, avec Docker démarré :

```sh
npm ci
npm run dev
```

La commande lance le frontend, l'API et les services. Ouvrir l'adresse `App` du message « Ready », <http://localhost:5173> si ce port est libre. Pour se connecter, voir [Se connecter en local](../README.md#se-connecter-en-local).

`npm run dev:frontend` lance le frontend seul. L'utiliser uniquement si une API tourne déjà à côté : sans API, l'appel de session échoue.

## Documentation

| Besoin                                             | Guide                                              |
| -------------------------------------------------- | -------------------------------------------------- |
| Comprendre la structure et les responsabilités     | [Architecture](../docs/frontend/architecture.md)   |
| Ajouter ou modifier une fonctionnalité             | [Conventions](../docs/frontend/conventions.md)     |
| Utiliser l'API, les requêtes et le cache           | [Couche API](../docs/frontend/api-layer.md)        |
| Installer les outils et lancer les commandes       | [Développement](../docs/frontend/developpement.md) |
| Vérifier les tests, l'accessibilité et la sécurité | [Qualité](../docs/frontend/qualite.md)             |

La carte constitue l'accueil protégé sur `/`. Les visiteurs sans session sont redirigés vers `/connexion`. Les routes composent les écrans des fonctionnalités `auth` et `map`, dont les requêtes et le cache restent gérés par TanStack Query.

Pour contribuer, suivre [CONTRIBUTING.md](../CONTRIBUTING.md). Le [guide API commun](../docs/api.md) décrit la génération du contrat et des types ; le [guide CI](../docs/ci.md) décrit les contrôles GitHub Actions.

# Emploi Go

Emploi Go est une application web responsive destinée aux jeunes accompagnés par la Mission Locale de Dijon. Elle aide à découvrir les entreprises et les métiers du territoire, et à suivre ses démarches vers l'emploi.

Ce dépôt regroupe l'interface React/TypeScript et le backend Java/Spring Boot. Le backend comprend une API pour l'interface et un worker pour les traitements en arrière-plan.

## Démarrer

Prérequis : nvm, Node.js 24, JDK 25 et Docker Desktop. Les versions sont dans le [guide de développement](docs/developpement.md#préparer-son-poste).

Installer nvm une seule fois sur le poste, selon [sa documentation](https://github.com/nvm-sh/nvm#installing-and-updating). Puis, depuis un terminal, avec Docker démarré :

```sh
git clone <url-du-dépôt> emploi-go-app
cd emploi-go-app
nvm install
npm ci
npm run dev
```

- `nvm install` installe et active la version de Node.js indiquée dans `.nvmrc`. Dans chaque nouveau terminal, `nvm use` la réactive.
- Le premier lancement prend plusieurs minutes : il télécharge Maven, les dépendances Java et les images Docker.
- Attendre le message **« Ready »** dans le terminal.
- Ouvrir l'adresse `App` qu'il affiche, <http://localhost:5173> si ce port est libre.

La carte est protégée : l'application affiche d'abord la page de connexion. La section suivante explique comment se connecter.

## Se connecter en local

La carte exige une connexion. Le premier `npm run dev` crée `backend/.env` depuis `backend/.env.example`, avec un compte de démo. L'envoi des emails reste désactivé par défaut (`REGISTRATION_MAIL_MODE=disabled`), donc une inscription ne peut pas être confirmée sans réglage. Deux options existent.

**Option A : compte de démo**

1. Ouvrir `backend/.env` et lire la valeur de `DEV_AUTH_PASSWORD`, tirée au hasard à la création du fichier.
2. Se connecter avec le pseudo `jeune_demo` et ce mot de passe.

Un `backend/.env` déjà présent n'est jamais modifié. S'il ne contient pas le compte de démo, y définir `DEV_AUTH_ACCOUNT=true` et un `DEV_AUTH_PASSWORD` de 20 à 128 caractères, puis relancer `npm run dev`.

**Option B : boîte email locale**

1. Dans `backend/.env`, définir `REGISTRATION_MAIL_MODE=local`. Si le fichier n'a pas de `CONFIRMATION_TOKEN_KEY`, en ajouter une selon le [guide backend](docs/backend/developpement.md#canal-demail-local).
2. Relancer `npm run dev` : il démarre aussi la boîte email locale et affiche son adresse (`Mail`) dans le message « Ready ».
3. S'inscrire, puis lire l'email de confirmation dans cette boîte.

Le [guide backend](docs/backend/developpement.md#configuration-locale) détaille ces réglages.

## Organisation

| Dossier      | Contenu                                                                 |
| ------------ | ----------------------------------------------------------------------- |
| `frontend/`  | Interface web et tests frontend.                                        |
| `backend/`   | API, worker et tests backend.                                           |
| `contracts/` | Contrat OpenAPI enregistré pour les échanges entre frontend et backend. |
| `scripts/`   | Commandes partagées de développement et de vérification.                |
| `docs/`      | Guides communs et documentation frontend et backend.                    |
| `.github/`   | Automatisations GitHub Actions et modèles d'issues et de PR.            |

## Pour aller plus loin

- [Sommaire de la documentation](docs/README.md) : tous les guides, avec un parcours de lecture.
- [Guide de développement](docs/developpement.md) : commandes, ports, tests et dépannage.
- [CONTRIBUTING.md](CONTRIBUTING.md) : branches, commits, PR et revue.

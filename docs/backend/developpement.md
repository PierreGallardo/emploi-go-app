# Lancer et configurer le backend

Ce guide explique comment lancer l'API et le worker et régler leur configuration locale.

- [Développement local](../developpement.md) : préparer son poste et utiliser les commandes npm.
- [Architecture](architecture.md) : comprendre l'organisation du backend.
- [Conventions](conventions.md) : écrire le code Java.
- [Qualité](qualite.md) : choisir et lancer les tests.

## Avec les commandes npm

Depuis la racine du dépôt :

| Commande          | Utilisation                                                    |
| ----------------- | -------------------------------------------------------------- |
| `npm run dev`     | Lancer le frontend, l'API et les services (PostgreSQL, Redis). |
| `npm run dev:all` | Même environnement, avec le worker.                            |

**Rien n'est à configurer pour un premier lancement.** Le premier `npm run dev` crée `backend/.env` avec le compte de démo. Ce fichier sert ensuite pour l'email local, Resend, Google ou les ports de PostgreSQL et Redis.

Ces commandes compilent automatiquement le backend après une sauvegarde. DevTools recharge l'API et le worker démarrés par la session. Les tests peuvent tourner en parallèle, selon le [guide commun](../developpement.md#vérifier-ses-modifications).

Une compilation échouée arrête l'API et le worker de la session. Corriger le fichier concerné et enregistrer pour les redémarrer automatiquement. Le frontend et les services Docker restent disponibles.

## Avec Maven ou l'IDE

Préparer les services depuis la racine :

```sh
npm run services:up
```

La commande affiche les ports de PostgreSQL et Redis. Si ce ne sont pas 5432 et 6379, renseigner `DB_URL` et `REDIS_PORT` dans l'environnement de lancement. Fixer `DB_PORT` et `REDIS_PORT` dans `backend/.env` garde ces ports stables.

Depuis `backend/`, choisir la commande adaptée à son terminal pour lancer l'API.

### macOS / Linux

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Windows (PowerShell)

```powershell
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

Pour lancer le worker, remplacer `local` par `local,worker`. Sans worker, rien ne purge les [données temporaires d'authentification](donnees.md#données-temporaires-dauthentification) : elles restent en base après leur expiration, sans être utilisables.

Dans l'IDE, exécuter `fr.emploigo.EmploiGoApplication` avec le profil `local` pour l'API ou `local,worker` pour le worker. Compiler après une modification avec la commande **Build**, ou activer la compilation automatique de l'IDE : DevTools réagit aux classes compilées.

`spring-boot:run` compile au lancement, mais ne surveille pas les fichiers source. Après une sauvegarde, une compilation depuis l'IDE ou Maven permet à DevTools de recharger l'application. Les commandes npm réalisent cette compilation automatiquement.

## Avec le JAR

Construire depuis `backend/` avec `./mvnw package` sous macOS / Linux ou `./mvnw.cmd package` sous Windows (PowerShell). L'API et le worker utilisent le même JAR.

Lancer l'API :

```sh
java -jar target/emploi-go-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Dans un autre terminal, lancer le worker :

```sh
java -jar target/emploi-go-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,worker
```

Le JAR ne recharge pas le code avec DevTools par défaut. Après une modification, reconstruire le JAR et relancer les processus. Ces lancements directs ne synchronisent pas le contrat ; utiliser `npm run api:sync` après avoir actualisé l'API.

## Profils et composants

La carte et ses établissements visibles en local sont décrits dans le [guide cartographique](../cartographie.md#établissements-visibles-en-local). Les migrations ne contiennent aucune donnée fictive.

L'application démarre en API par défaut. Le profil `worker` sélectionne le scheduler et désactive les composants API, Spring Session et Redis. Le worker utilise PostgreSQL et conserve sa supervision HTTP. Chaque composant déclare son rôle par `@ApiOnly`, `@WorkerOnly` ou `@ApiAndWorker` (voir le [guide d'architecture](architecture.md#rôle-api-ou-worker)).

| Profil     | But                                                                                                                     |
| ---------- | ----------------------------------------------------------------------------------------------------------------------- |
| `local`    | Utiliser les connexions et adresses locales ; activer Swagger et OpenAPI pour l'API.                                    |
| `worker`   | Exécuter le scheduler PostgreSQL, sans contrôleur API, sessions ni client Redis. Utiliser `local,worker` sur son poste. |
| `contract` | Exporter OpenAPI depuis une API temporaire, sans Swagger UI. Les scripts de vérification l'utilisent.                   |
| `test`     | Sélectionner le contexte des tests ; les tests fournissent leurs connexions.                                            |

Les fichiers de configuration se complètent :

| Fichier                    | Contenu                                                                                             |
| -------------------------- | --------------------------------------------------------------------------------------------------- |
| `application.yml`          | PostgreSQL, JPA et supervision commune.                                                             |
| `application-api.yml`      | Configuration API, sessions JDBC et Redis. Importé automatiquement en l'absence de `worker`.        |
| `application-worker.yml`   | Scheduler, purge, import des établissements, ports du worker et exclusions des composants inutiles. |
| `application-local.yml`    | Valeurs locales PostgreSQL et, pour l'API, Redis et documentation HTTP.                             |
| `application-contract.yml` | Réglages de l'export isolé OpenAPI.                                                                 |

Ces fichiers sont dans [`backend/src/main/resources/`](../../backend/src/main/resources/).

## Ports et supervision

La supervision fournit l'état de santé sur `/actuator/health` et les métriques sur `/actuator/prometheus`. L'état de santé est accessible localement ; les métriques restent protégées.

| Service               | Port par défaut | Option des scripts npm            |
| --------------------- | --------------- | --------------------------------- |
| Frontend              | 5173            | `--frontend-port`                 |
| API                   | 8080            | `--api-port`                      |
| Supervision de l'API  | 8081            | `--api-management-port`           |
| Worker                | 8090            | `--worker-port`                   |
| Supervision du worker | 8091            | `--worker-management-port`        |
| PostgreSQL            | 5432            | `DB_PORT` dans `backend/.env`.    |
| Redis, pour l'API     | 6379            | `REDIS_PORT` dans `backend/.env`. |

Exemple depuis la racine :

```sh
npm run dev:all -- --frontend-port 5273 --api-port 8180 --api-management-port 8181 --worker-port 8190 --worker-management-port 8191
```

Les options s'appliquent aux composants lancés. Sans option, un port par défaut occupé est remplacé par un port libre, affiché dans le message « Ready ». Un port donné par option est imposé : la commande refuse un port invalide, dupliqué ou déjà occupé. Le proxy frontend et l'export automatique du contrat suivent le port de l'API. Ces options ne changent pas les ports PostgreSQL et Redis : le [guide commun](../developpement.md#ports-utilisés) décrit leur choix.

Pour une API lancée dans l'IDE sur le port 8180 :

```sh
npm run dev:frontend -- --api-port 8180
```

Synchroniser le contrat dans un autre terminal :

```sh
npm run api:sync -- --api-port 8180
```

Avec Maven, Java ou l'IDE, définir `server.port` et `management.server.port` dans la configuration de lancement ; par exemple ajouter `--server.port=8180 --management.server.port=8181` à la commande Java.

## Configuration locale

### Compte local de développement

Le profil `local` peut créer un compte fictif vérifié pour tester la connexion sans email. Il suit deux variables de `backend/.env` : `DEV_AUTH_ACCOUNT=true` l'active, et `DEV_AUTH_PASSWORD` fixe son mot de passe, de 20 à 128 caractères. Le `backend/.env` créé par le premier `npm run dev` l'active avec un mot de passe aléatoire. Sans `DEV_AUTH_ACCOUNT=true`, ce provisionnement est désactivé. Il est réservé à l'API locale ; il est interdit au worker et n'est pas actif en production.

Après `npm run dev`, ouvrir `/connexion` avec le pseudo `jeune_demo` ou `jeune@example.invalid` et la valeur de `DEV_AUTH_PASSWORD`. Le compte est créé seulement s'il n'existe pas : changer la variable ne réinitialise pas son mot de passe. Une base locale qui ne correspond plus aux migrations fait échouer Flyway au démarrage : la recréer avec `npm run services:reset`, qui efface toutes ses données.

Les valeurs du profil `local` correspondent aux services de [`backend/compose.yml`](../../backend/compose.yml). Flyway crée le schéma dans PostgreSQL au démarrage.

Le premier `npm run dev` crée `backend/.env` depuis [`backend/.env.example`](../../backend/.env.example) ; modifier ce fichier pour personnaliser les connexions. Les scripts npm et Compose lisent ce fichier ; les variables du terminal ont priorité pour les scripts. Relancer la session après une modification du fichier. Le vrai `.env` est ignoré par Git.

| Variable                                     | Utilisation                                                                                                                                                                                                                                                              |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `DB_PORT`, `REDIS_PORT`                      | Ports du poste où PostgreSQL et Redis sont publiés. Sans valeur, les scripts npm prennent 5432 et 6379 s'ils sont libres, sinon un port libre, et le transmettent à l'API et au worker.                                                                                  |
| `DB_URL`                                     | URL JDBC PostgreSQL ; par défaut `jdbc:postgresql://localhost:5432/emploigo` en local. Les scripts npm la construisent sur le port PostgreSQL choisi. Une valeur définie dans `backend/.env` est gardée telle quelle, et PostgreSQL reste alors sur 5432 (ou `DB_PORT`). |
| `DB_USERNAME`, `DB_PASSWORD`                 | Connexion PostgreSQL ; utilisateur local `emploigo`. Le mot de passe par défaut est dans `.env.example`.                                                                                                                                                                 |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Connexion Redis de l'API ; adresse locale `localhost:6379` par défaut. Le worker ne les utilise pas.                                                                                                                                                                     |
| `QUOTA_KEY`                                  | Clé des compteurs de quota ; le profil `local` fournit une valeur de développement, aucune action en local. Voir les [secrets](../secrets.md#clé-des-compteurs-de-quota).                                                                                                |
| `ESTABLISHMENT_IMAGES_DIRECTORY`             | Dossier des photos des établissements servies par l'API ; `/var/lib/emploi-go/establishment-images` par défaut. Sans ce dossier, la carte montre le visuel du secteur. Voir [Photos des établissements](../exploitation.md#photos-des-établissements).                   |
| `SESSION_TIMEOUT`                            | Inactivité maximale d'une session API ; 30 minutes par défaut.                                                                                                                                                                                                           |
| `SERVER_PORT`, `MANAGEMENT_PORT`             | Ports des lancements directs Maven, Java ou IDE. Les scripts npm utilisent leurs options de ports.                                                                                                                                                                       |

Maven, Java et l'IDE ne lisent pas automatiquement `backend/.env`. Définir les variables nécessaires dans leur environnement de lancement. Le [dépannage commun](../developpement.md#dépannage) couvre les connexions Docker et les ports occupés.

### Tester l'inscription avec Resend

Configurer le email pour l'inscription, les confirmations, la récupération du mot de passe et les notifications Google. Ces envois viennent de l'API : `npm run dev` suffit. Relancer la commande après un changement de `backend/.env`.

Renseigner uniquement dans `backend/.env`, jamais dans Git ni dans une variable frontend :

```dotenv
REGISTRATION_MAIL_MODE=resend
REGISTRATION_MAIL_FROM=onboarding@resend.dev
PUBLIC_APP_ORIGIN=http://localhost:5173
RESEND_API_KEY=<clé privée Resend avec droit d'envoi>
CONFIRMATION_TOKEN_KEY=<clé privée Base64 de 32 à 64 octets aléatoires>
```

Générer la clé de confirmation une seule fois. Elle doit être en Base64 et représenter 32 à 64 octets aléatoires. Ne pas la regénérer à chaque démarrage.

macOS / Linux :

```sh
openssl rand -base64 32
```

Windows (PowerShell) :

```powershell
[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Copier le résultat dans `CONFIRMATION_TOKEN_KEY` de `backend/.env`, ignoré par Git. Ne jamais le coller dans un message, un ticket ou un fichier suivi.

L'expéditeur de test `onboarding@resend.dev` est limité à l'adresse du compte Resend. Pour d'autres destinataires et la production, vérifier un domaine dans Resend et utiliser un expéditeur de ce domaine. En production, `PUBLIC_APP_ORIGIN` doit être l'origine HTTPS de l'application. Voir les [restrictions Resend](https://resend.com/docs/knowledge-base/403-error-resend-dev-domain).

Désactiver le suivi des clics sur le domaine d'envoi : il réécrit les liens et expose leur secret à une redirection supplémentaire. Ce réglage appartient au domaine Resend. Voir le [suivi des liens](https://resend.com/blog/open-and-click-tracking).

Pour vérifier la livraison, ouvrir `/inscription` et utiliser une adresse autorisée. Après l'envoi, consulter la boîte et les indésirables, puis taper le code dans la page. Vérifier aussi un renvoi (le code précédent ne marche plus), le refus de la connexion avec le mot de passe de l'inscription, et qu'un code ne confirme pas l'inscription d'une autre page. Les [règles de confirmation](../securite.md#confirmation-dinscription) définissent les accès attendus.

Utiliser le canal local ci-dessous pour les adresses fictives. Les tests automatisés utilisent des fournisseurs isolés ; ils ne vérifient pas la livraison Resend réelle. Ne pas conserver de capture contenant un mot de passe, un code, un lien ou une adresse personnelle.

### Canal d'email local

Dans `backend/.env`, définir `REGISTRATION_MAIL_MODE=local`, puis lancer `npm run dev`. La commande démarre aussi la boîte email locale, sur le port 8025 s'il est libre, sinon sur un port libre. Elle donne son adresse à l'API et l'affiche (`Mail`) dans le message « Ready ». La boîte :

- écoute uniquement sur la boucle locale et n'envoie rien à l'extérieur ;
- garde les messages en mémoire et se vide quand la session `npm run dev` s'arrête.

Un `backend/.env` créé par le premier `npm run dev` contient déjà une `CONFIRMATION_TOKEN_KEY`. Sinon, en ajouter une : `CONFIRMATION_TOKEN_KEY=` suivi du résultat de `openssl rand -base64 32`. Avec un `LOCAL_MAIL_URL` personnalisé, la commande ne démarre pas de boîte et l'API envoie à cette adresse. Ce mode est réservé aux profils `local` et `test`.

### Timeouts des fournisseurs externes

Chaque appel à un fournisseur externe a un timeout de connexion et un timeout de réponse. Les valeurs par défaut conviennent à la production ; les tests d'intégration les réduisent pour simuler un fournisseur lent. Ce sont des propriétés Spring, sans variable d'environnement dédiée. Chaque valeur est une durée (`500ms`, `3s`) entre 100 ms et 30 s ; hors de ces bornes, le démarrage échoue.

| Propriété                                      | Défaut | Appel                                     |
| ---------------------------------------------- | ------ | ----------------------------------------- |
| `emploi-go.registration.mail-connect-timeout`  | 3 s    | Envoi d'un email (Resend), connexion      |
| `emploi-go.registration.mail-response-timeout` | 10 s   | Envoi d'un email (Resend), réponse        |
| `emploi-go.google.connect-timeout`             | 3 s    | Google (échange du code, clés), connexion |
| `emploi-go.google.response-timeout`            | 10 s   | Google (échange du code, clés), lecture   |

L'appel d'envoi d'un email attend au plus la somme des deux timeouts (13 s par défaut), body de la réponse compris. Au-delà, l'envoi est incertain. Le jeune reçoit un 503 et peut redemander après le délai entre deux envois.

### Import des établissements

Le worker importe chaque jour les établissements de la métropole ([guide worker](worker.md#import-des-établissements)). Ses propriétés ont des valeurs par défaut adaptées à la production ; seule la clé de l'API Sirene est à fournir.

| Propriété                                                  | Défaut                                 | Rôle                                                                                                                           |
| ---------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `emploi-go.establishment-synchronization.daily-at`         | `11:00`                                | Heure de Paris du lancement quotidien.                                                                                         |
| `emploi-go.establishment-synchronization.directory-url`    | `https://api.insee.fr/api-sirene/3.11` | Adresse de l'API Sirene.                                                                                                       |
| `emploi-go.establishment-synchronization.api-key`          | `SIRENE_API_KEY`, vide                 | Clé de l'API Sirene, créée par le compte sur [portail-api.insee.fr](https://portail-api.insee.fr). Sans elle, l'import échoue. |
| `emploi-go.establishment-synchronization.connect-timeout`  | 5 s                                    | Timeout de connexion à l'API (une minute au plus).                                                                             |
| `emploi-go.establishment-synchronization.response-timeout` | 30 s                                   | Timeout de réponse de l'API (une minute au plus).                                                                              |

Pour essayer l'import en local, mettre `SIRENE_API_KEY=…` dans `backend/.env` (ignoré par Git ; l'exemple est dans `backend/.env.example`).

Pour lancer l'import tout de suite en local, démarrer le worker (`npm run dev:all`), puis rendre la tâche due ; le worker la prend à sa recherche suivante, dans les dix secondes :

```bash
docker compose -f backend/compose.yml exec postgres psql -U emploigo -d emploigo -c "UPDATE scheduled_tasks SET execution_time = now() WHERE task_name = 'synchronize-establishments'"
```

L'import interroge la véritable API Sirene pendant une à deux minutes. Il remplit la table `establishments` de la base locale ; aucun établissement n'est visible tant que nous ne l'avons pas rendu visible.

### Import des fichiers ouverts

Le worker copie chaque nuit les arrêts Divia, les adresses de la métropole et la liste des organismes de formation ([guide worker](worker.md#import-des-fichiers-ouverts)). Ses propriétés ont des valeurs par défaut adaptées à la production, sans variable d'environnement dédiée.

| Propriété                                                           | Défaut                                      | Rôle                                                                 |
| ------------------------------------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------- |
| `emploi-go.dataset-synchronization.connect-timeout`                 | 5 s                                         | Timeout de connexion (une minute au plus).                           |
| `emploi-go.dataset-synchronization.download-timeout`                | 2 min                                       | Durée maximale du téléchargement d'un fichier (dix minutes au plus). |
| `emploi-go.dataset-synchronization.transit-stops.daily-at`          | `04:00`                                     | Heure de Paris de l'import des arrêts.                               |
| `emploi-go.dataset-synchronization.transit-stops.url`               | adresse stable du GTFS sur data.gouv.fr     | Fichier GTFS de DiviaMobilités.                                      |
| `emploi-go.dataset-synchronization.addresses.daily-at`              | `04:10`                                     | Heure de Paris de l'import des adresses.                             |
| `emploi-go.dataset-synchronization.addresses.url`                   | `adresses-21.csv.gz` de la BAN              | Fichier des adresses de la Côte-d'Or.                                |
| `emploi-go.dataset-synchronization.training-organizations.daily-at` | `04:20`                                     | Heure de Paris de l'import des organismes de formation.              |
| `emploi-go.dataset-synchronization.training-organizations.url`      | adresse stable de la liste sur data.gouv.fr | Liste publique des organismes de formation.                          |

Pour lancer les trois imports tout de suite en local, démarrer le worker, puis rendre les tâches dues ; chacun télécharge son fichier en quelques secondes :

```bash
docker compose -f backend/compose.yml exec postgres psql -U emploigo -d emploigo -c "UPDATE scheduled_tasks SET execution_time = now() WHERE task_name IN ('synchronize-transit-stops', 'synchronize-addresses', 'synchronize-training-organizations')"
```

### Tolérance de réutilisation du secret de reconnexion

Un ancien secret de reconnexion rejoué révoque sa famille, sauf dans un délai après la rotation qui couvre une réponse perdue ou deux requêtes simultanées : il est alors servi comme le secret courant. La règle est dans le [guide sécurité](../securite.md#sessions-et-réglages-initiaux).

| Propriété                                     | Défaut | Bornes      |
| --------------------------------------------- | ------ | ----------- |
| `emploi-go.auth.reconnection-reuse-tolerance` | 30 s   | 1 s à 1 min |

C'est une propriété Spring, sans variable d'environnement dédiée. Hors de ces bornes, le démarrage échoue : le défaut dépasse le timeout de 15 s du client, car une tolérance plus courte révoquerait sur une réponse perdue ; une tolérance longue laisserait du temps à un cookie volé. Le serveur ne vérifie pas ce lien.

Chaque révocation pour réutilisation, après la tolérance, incrémente la métrique `auth.reconnection.reuse.revoked`, sans étiquette et visible à zéro dès le démarrage de l'API (le worker ne l'expose pas). Elle sort sur `/actuator/prometheus` sous le nom `auth_reconnection_reuse_revoked_total`. Une requête qui perd la course contre une révocation déjà faite n'est pas comptée. L'[alerte](../exploitation.md#protections-dauthentification) est un avertissement.

### Plafond des calculs de hash de mot de passe

Le nombre de calculs de hash (PBKDF2) simultanés est plafonné. Les [règles](authentification.md#limitation-des-tentatives) sont dans le guide d'authentification.

| Propriété                                         | Défaut                      | Bornes  |
| ------------------------------------------------- | --------------------------- | ------- |
| `emploi-go.auth.password-hashing-max-concurrency` | nombre de cœurs vus par JVM | 1 à 256 |

C'est une propriété Spring, sans variable d'environnement dédiée. Hors de ces bornes, ou si ce n'est pas un nombre entier, le démarrage échoue.

- Au-delà du nombre de cœurs, le débit n'augmente plus : seule la durée de chaque calcul s'allonge.
- Plafond atteint, les calculs occupent tous les cœurs. Baisser la valeur laisse du processeur aux autres services de la machine, au prix d'un débit de connexion plus faible.
- Le plafond est propre à chaque processus de l'API. Le déploiement prévoit une seule instance d'API ; avec plusieurs, chacune a son plafond.
- La métrique `auth.password.hashing.rejections` compte les refus (étiquette `operation` : `matches` pour une vérification, `encode` pour une création ou pour le hash factice que la connexion calcule à sa première tentative). Elle sort sur `/actuator/prometheus` sous le nom `auth_password_hashing_rejections_total`. Un taux de refus soutenu signale une attaque ou un plafond trop bas.

### Itérations du hash des mots de passe

| Propriété                                    | Défaut  | Bornes             |
| -------------------------------------------- | ------- | ------------------ |
| `emploi-go.auth.password-hashing-iterations` | 600 000 | 1 000 à 10 000 000 |

C'est une propriété Spring, sans variable d'environnement dédiée. Hors de ces bornes, le démarrage échoue. Le hash ne garde pas ce nombre : le changer sur une base qui contient déjà des mots de passe rend ces mots de passe invalides, car chacun serait vérifié avec un autre nombre d'itérations.

### Quota et timeouts Redis

Chaque action d'authentification a son quota, réglé par propriété. Les valeurs sont toutes écrites dans `application-api.yml`, sous `emploi-go.auth.quota.scopes.<action>` : le [fonctionnement des compteurs](donnees.md#forme-des-quotas) et la réaction à une [panne de Redis](donnees.md#panne-des-compteurs) sont décrits dans le guide des données.

| Propriété (sous `emploi-go.auth.quota.scopes.<action>`) | Défaut          | Bornes                                                                                                                   |
| ------------------------------------------------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `address-limit` (sauf `resume`)                         | voir ci-dessous | 1 à 10 000 tentatives par réseau client et par fenêtre ; refusée pour `resume`                                           |
| `identity-limit` (sauf `login`)                         | voir ci-dessous | 1 à 10 000 tentatives par identité et par fenêtre ; refusée pour `login` ; pour `resume`, les reprises d'une reconnexion |
| `window`                                                | 15 min          | 1 min à 24 h (pour `login`, la vie du compteur de l'adresse seulement)                                                   |
| `send-delay` (actions d'email seulement)                | 60 s            | 1 s à 1 h : attente entre deux envois à la même identité                                                                 |
| `global-alert-threshold` (actions d'email seulement)    | 300             | 1 à 1 000 000 envois par fenêtre : au-delà, une alerte, pas un refus                                                     |
| `prefix-limit` (actions d'email seulement)              | 120             | 1 à 10 000 envois par préfixe /48 IPv6 et par fenêtre, jamais sous `address-limit` ; une IPv4 n'a pas de préfixe         |

Le délai qui suit les échecs de la connexion, sous `emploi-go.auth.quota.scopes.login.failure-delay` (obligatoire pour `login`, refusé pour les autres actions) :

| Propriété (sous `failure-delay`) | Défaut | Bornes                                                                                                                     |
| -------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------- |
| `free-failures`                  | 5      | 1 à 100 : le délai commence après cet échec ; les précédents n'en entraînent aucun                                         |
| `base-delay`                     | 5 s    | 1 s à 5 min : délai qui suit le dernier échec libre                                                                        |
| `growth-factor`                  | 2      | 1,0 à 10 : facteur de chaque délai suivant                                                                                 |
| `max-delay`                      | 5 min  | `base-delay` à 1 h : plus long délai                                                                                       |
| `counter-ttl`                    | 30 min | 1 min à 24 h, strictement plus long que `max-delay` et au plus 4 fois `window` : vie des échecs, renouvelée à chaque échec |

Avec ces défauts, les délais qui suivent le 5e échec, puis chaque échec suivant, sont 5 s, 10 s, 20 s, 40 s, 80 s, 160 s, puis 5 min. Un client qui varie l'identifiant garde au plus `address-limit × (counter-ttl / window + 1)` compteurs d'échecs vivants par réseau (270 avec les défauts : la fenêtre de l'adresse est fixe, et une clé d'échecs vit `counter-ttl` à partir de sa création) : c'est pourquoi `counter-ttl` est borné par `window`. Le total suit le nombre de tentatives admises pendant `counter-ttl` : chacune compte un échec, qu'elle aille au bout ou non, et l'adresse en limite le nombre. Variable d'environnement, par exemple : `EMPLOI_GO_AUTH_QUOTA_SCOPES_LOGIN_FAILURE_DELAY_MAX_DELAY=2m`.

Le timeout de Redis se règle par `spring.data.redis.connect-timeout` et `timeout` (500 ms par défaut). Chaque requête qui touche aux compteurs attend ce timeout quand Redis ne répond pas : un délai long retient donc les requêtes.

Les actions et leurs limites par défaut (adresse, identité) :

| Action                                                                       | Adresse | Identité                        |
| ---------------------------------------------------------------------------- | ------- | ------------------------------- |
| `login`                                                                      | 90      | aucune : délai après les échecs |
| `registration-mail`                                                          | 40      | 3 (et 120 par préfixe /48)      |
| `recovery-mail`                                                              | 20      | 3 (et 120 par préfixe /48)      |
| `google-mail`                                                                | 10      | 3 (et 120 par préfixe /48)      |
| `recovery-confirm`                                                           | 30      | 30                              |
| `google-link`                                                                | 30      | 10                              |
| `confirmation`                                                               | 60      | 30                              |
| `google-start`, `google-callback`, `google-complete`, `google-email-confirm` | 30      | 10                              |
| `resume`                                                                     | aucune  | 10 (la reconnexion)             |

Ce sont des propriétés Spring, liées par `QuotaProperties`. Elles se changent dans `application-api.yml`, ou par l'environnement sans modifier le code :

- **Variable d'environnement** : `EMPLOI_GO_AUTH_QUOTA_SCOPES_<ACTION>_<PROPRIÉTÉ>`, la clé de l'action et la propriété en majuscules, leurs tirets remplacés par des tirets bas. Exemple : `EMPLOI_GO_AUTH_QUOTA_SCOPES_REGISTRATION_MAIL_IDENTITY_LIMIT=5`. La valeur du fichier est remplacée, les autres sont conservées. Une variable qui commence par `EMPLOI_GO_AUTH_QUOTA_` sans correspondre à une propriété (`..._REGISTRATIONMAIL_...`, tiret conservé, faute de frappe) refuse le démarrage.
- **Aucune faute n'est ignorée.** Le démarrage de l'API échoue, avec le nom en cause, dans ces cas : une propriété inconnue dans un fichier ou dans `SPRING_APPLICATION_JSON` (`adress-limit`) ; une variable `EMPLOI_GO_AUTH_QUOTA_…` qui ne correspond à aucune propriété ; une action absente ; une valeur absente, illisible ou hors bornes ; un délai, un seuil ou une limite de préfixe d'email sur une action sans email ; une limite de préfixe sous la limite par adresse ; `address-limit` sur `resume` ; `identity-limit` sur `login`, ou `failure-delay` ailleurs ; un `failure-delay` absent, hors bornes, ou dont `counter-ttl` n'excède pas `max-delay` ; `open` sur une action sensible.
- Une durée écrite sans unité compte des secondes (`900` vaut `15m`) ; écrire l'unité (`15m`, `60s`) évite l'erreur.
- Un changement de quota demande un redémarrage de l'API. Les compteurs déjà posés gardent leur durée de vie.

Autres règles :

- Ne passer une action en `open` que si elle vérifie elle-même un token ou un état, et qu'elle n'envoie rien ni ne calcule de hash avant cette vérification.
- Les actions fermées par défaut vérifient un mot de passe ou envoient un email : elles ne peuvent pas être ouvertes. La règle est portée par `QuotaScope.sensitive()` : une nouvelle action qui vérifie un mot de passe ou envoie un email doit y être marquée, et `QuotaScope.mail()` marque celles qui envoient un email.
- Une nouvelle action est un nouveau `QuotaScope` : ses quatre valeurs (sept pour une action d'email, trois pour une action comptée par son identité seule) doivent être écrites dans `application-api.yml`, sans quoi l'API refuse de démarrer. L'appelant ne passe aucun nombre. La connexion est la seule action à compter des échecs : elle passe par `LoginAttemptLimiter`, pas par `AuthenticationRateLimiter`. Les clés Redis viennent de `QuotaKeys` : le limiteur n'a rien à ajouter. La reprise de la reconnexion est la seule action comptée par son identité seule (`QuotaScope.identityOnly()`) : elle passe par `QuotaEnforcer.checkIdentity`, après la vérification de son secret, et n'a pas de limite par adresse.
- Les métriques `auth_rate_limit_counters_lost_total` (Redis neuf ou revenu sans ses données), `auth_rate_limit_global_exceeded_total` (envois d'email au-delà du seuil global), `auth_rate_limit_prefix_limited_total` (demandes d'email refusées par la limite du préfixe /48), `auth_rate_limit_address_unreadable_total` (adresse de client illisible) et `auth_rate_limit_login_unrecorded_total` (réponse de connexion non enregistrée) sont décrites dans le [guide des données](donnees.md#forme-des-quotas).

## Tester la récupération

La récupération utilise la [configuration d'email](#tester-linscription-avec-resend) existante, sans nouvelle variable ni régénération de clé. Avec un compte local confirmé :

1. Se connecter dans deux navigateurs, puis demander un lien depuis « Mot de passe oublié ? ». La demande doit laisser les sessions actives.
2. Ouvrir le email le plus récent et choisir un nouveau mot de passe selon la [politique commune](../securite.md#identifiants-et-mots-de-passe). L'ouverture seule ne doit changer aucun accès.
3. Après validation, vérifier le refus des anciennes sessions et de l'ancien mot de passe, puis la connexion normale avec le nouveau.
4. Vérifier le email de notification, envoyé une seule fois, et le refus d'un lien déjà utilisé (410). Si la notification échoue, le mot de passe reste changé : aucune reprise n'existe.

Utiliser le canal local pour les adresses fictives. Préserver les comptes et les données de développement ; ne pas réinitialiser Redis pour contourner un quota.

## Tester Google

Créer dans Google Auth Platform un client OAuth de type **Application Web**. Configurer Branding (nom et contact), Audience (externe et utilisateurs test tant que le projet reste en test), puis Clients. L'application demande seulement les scopes `openid` et `email` ; elle ne récupère pas la photo ni le nom civil. Pour le serveur local et son proxy Vite, saisir exactement :

- Origine JavaScript autorisée : `http://localhost:5173`.
- URI de redirection autorisée : `http://localhost:5173/api/auth/google/callback/google`.

Conserver le secret du client exclusivement dans `backend/.env`, ignoré par Git. Ajouter les quatre paramètres ci-dessous à la configuration d'email déjà présente, sans changer l'origine ni régénérer la clé de confirmation :

```dotenv
GOOGLE_ENABLED=true
GOOGLE_CLIENT_ID=<identifiant du client Web>
GOOGLE_CLIENT_SECRET=<secret privé du client Web>
GOOGLE_REDIRECT_URI=http://localhost:5173/api/auth/google/callback/google
```

Activer Google exige un client complet et un canal d'email configuré. `GOOGLE_REDIRECT_URI` doit correspondre à `PUBLIC_APP_ORIGIN` suivi de `/api/auth/google/callback/google`. En production, utiliser l'origine HTTPS réelle et enregistrer exactement son callback dans Google. Relancer `npm run dev` après changement de configuration. Si le frontend ne prend pas le port 5173, `npm run dev` adapte `GOOGLE_REDIRECT_URI` à son port : ce callback doit alors aussi être enregistré dans Google. Utiliser `localhost` si le callback est enregistré pour ce nom ; `127.0.0.1` est une autre origine.

Depuis `/connexion` ou `/inscription`, vérifier la création avec Google, la déconnexion puis le retour au même compte. Pour une adresse externe à Gmail ou Workspace, confirmer le email local avant de reprendre Google. Avec l'adresse d'un compte local confirmé, vérifier la demande du mot de passe puis la confirmation distincte de l'association.

Les emails utilisent les mêmes [destinataires autorisés](#tester-linscription-avec-resend). Les tests automatisés remplacent Google et le fournisseur d'email ; vérifier manuellement le véritable retour Google. Références : [configuration du client](https://developers.google.com/identity/openid-connect/openid-connect#appsetup), [application en test](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification).

## Versions et dépendances

Les versions exactes sont fixées dans le dépôt, qui fait référence. Ce guide ne les recopie pas, pour ne jamais les contredire.

| Élément                    | Où trouver la version                                                             |
| -------------------------- | --------------------------------------------------------------------------------- |
| Java, Spring Boot          | [`backend/pom.xml`](../../backend/pom.xml)                                        |
| Bibliothèques Java         | [`backend/pom.xml`](../../backend/pom.xml) ; Spring Boot aligne celles qu'il gère |
| Maven                      | [Maven Wrapper](../../backend/.mvn/wrapper/maven-wrapper.properties)              |
| PostgreSQL et Redis locaux | [`backend/compose.yml`](../../backend/compose.yml)                                |
| PostGIS                    | [`backend/postgres/Dockerfile`](../../backend/postgres/Dockerfile)                |

Pour examiner les dépendances résolues, utiliser `./mvnw dependency:tree` sous macOS / Linux ou `./mvnw.cmd dependency:tree` sous Windows (PowerShell).

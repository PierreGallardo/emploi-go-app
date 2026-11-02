# Données et transactions

Ce guide définit où conserver les données, comment regrouper les écritures dans une transaction et comment réagir aux pannes de PostgreSQL ou de Redis. Il complète les guides d'[architecture](architecture.md), d'[API](api.md) et de [worker](worker.md).

## PostgreSQL et migrations

PostgreSQL est la référence pour les données durables. Une migration est un script SQL versionné qui fait évoluer la structure ou les données de la base.

### Création et validation du schéma

| Mécanisme                          | Rôle                                                                                                       |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Flyway                             | Appliquer les migrations versionnées et conserver leur historique.                                         |
| Hibernate avec `ddl-auto=validate` | Vérifier que le schéma correspond au modèle JPA, sans le créer ni le modifier.                             |
| Spring Session JDBC                | Utiliser les tables de session créées par migration. La création automatique de son schéma est désactivée. |

La configuration du dépôt lance Flyway au démarrage de l'API et du worker. Ce fonctionnement automatique est conservé en local ; la production suit la procédure séparée ci-dessous.

### Extension PostGIS

La position des établissements est une donnée géographique PostGIS, indexée pour les recherches de proximité.

- Une migration crée l'extension si elle manque.
- `CREATE EXTENSION postgis` exige un superutilisateur : PostGIS n'est pas une extension « trusted ». En production, un administrateur la crée une fois dans la base, avant la migration qui en a besoin. La migration la trouve alors en place et ne demande aucun droit de plus : le compte de migration reste sans privilège de superutilisateur.
- Les services locaux, les tests et les scripts utilisent la même image, construite par [`backend/postgres/Dockerfile`](../../backend/postgres/Dockerfile) : l'image officielle de PostgreSQL, avec PostGIS des paquets PGDG, épinglé. L'image `postgis/postgis` n'est publiée que pour amd64.

### Recherche sans accent ni casse

Un texte se cherche sous une forme commune : en minuscules, sans accents, mots séparés d'une seule espace. « Rue de l'Église » et « rue de l eglise » ont la même forme.

- La fonction `search_text` de la base donne cette forme, au texte stocké comme au texte tapé. Un index qui sert la recherche porte sur elle, comme celui des noms de voies. Un texte sans lettre ni chiffre dans cette forme ne trouve rien.
- Les textes que la carte compare à chaque ligne (nom et raison sociale d'un établissement, libellé d'une activité ou d'un métier) gardent leur forme cherchée dans une colonne calculée par la base. Sur les établissements de la métropole, une recherche passe ainsi de 260 à 110 ms.
- Elle repose sur les extensions `unaccent` et `pg_trgm`, créées par migration si elles manquent. Toutes deux sont « trusted » : il suffit du droit `CREATE` sur la base, sans superutilisateur. En production, l'administrateur les crée avec PostGIS, et le compte de migration n'a besoin d'aucun droit sur la base.
- `pg_trgm` sert une recherche par morceau de mot (« liber » trouve « Liberté ») et tolérante aux fautes de frappe (« fournill » trouve « Fournil »).
- La base tient `search_text` pour stable : une mise à jour de `unaccent` ou de la collation de la base laisse ses index et ses colonnes calculées périmés sans erreur. Après l'une ou l'autre, reconstruire ces index (`REINDEX`) et réécrire ces colonnes.

### Identités et droits en production

Pour chaque environnement, séparer les identités PostgreSQL de l'API, du worker, de la migration et de l'administration. Le JAR partagé ne donne aucun droit commun à ces processus.

| Usage          | Droits nécessaires                                                                                                                                                   |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| API            | Opérations métier autorisées, sessions navigateur et programmation des tâches, sur les objets nécessaires.                                                           |
| Worker         | Lecture des seules données nécessaires aux traitements, exécution/replanification des tâches et persistance de leurs résultats. Aucun accès aux sessions navigateur. |
| Migration      | Propriété du schéma et des objets applicatifs, migrations DDL et historique Flyway ; attribution des droits requis aux identités permanentes.                        |
| Administration | Opérations d'exploitation habilitées, avec identité distincte de celles utilisées par les services.                                                                  |

- Les identités API et worker ne peuvent pas modifier la structure de la base : aucun droit DDL, aucune propriété du schéma, aucun privilège de création de rôle ou de superutilisateur.
- Ne pas réserver `scheduled_tasks` au worker : l'API doit pouvoir créer, annuler ou replanifier les tâches des use cases.
- Accorder seulement les opérations et les objets nécessaires, y compris les séquences utilisées. Prévoir les droits sur les futurs objets créés par migration et retirer les droits implicites inutiles.
- Vérifier les opérations autorisées avec chaque identité, ainsi que le refus des modifications de structure pour l'API et le worker.

Pour la [purge](#données-temporaires-dauthentification), l'identité du worker reçoit ces droits et aucun autre sur les tables d'authentification, avec l'usage du schéma et la connexion à la base. Il ne lit ni le secret des reconnexions ni l'adresse et le hash d'une inscription. Un droit sur une colonne limite les colonnes que le worker lit et écrit, pas la valeur qu'il écrit : son identifiant PostgreSQL reste un secret à protéger comme celui de l'API ([secrets](../secrets.md#application-et-base-de-données)).

| Table                                                                                                                 | Droits du worker                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `auth_registrations`                                                                                                  | Lire `id`, `status` et `expires_at`. Modifier `status`, `email_key`, `password_hash` et `confirmation_key_hash`. Supprimer. |
| `auth_password_resets`, `auth_google_flows`, `auth_google_pending`, `auth_google_verifications`, `auth_reconnections` | Lire la clé et `expires_at`. Supprimer.                                                                                     |
| `scheduled_tasks`                                                                                                     | Lire, créer, modifier et supprimer, pour le scheduler.                                                                      |

Les codes d'une inscription sont supprimés avec elle par la base, avec les droits du propriétaire de la table. Un test d'intégration exécute la purge avec exactement ces droits.

Le droit de modifier `confirmation_key_hash` s'accorde avant de livrer la version qui l'écrit : sans lui, la purge des inscriptions expirées est refusée.

Les droits de la carte sont dans le script versionné `db/grants/map.sql`, rejoué après les migrations de chaque livraison ([exploitation](../exploitation.md#configuration-et-migrations)). Deux tests d'intégration exécutent l'import et chaque lecture de la carte avec exactement les droits de ce script.

| Données                                                | API     | Worker                                                                                                                                                |
| ------------------------------------------------------ | ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Schéma `reference` (modifié par les seules migrations) | Lecture | Lecture                                                                                                                                               |
| `establishments`                                       | Lecture | Lecture, ajout et modification des données de l'État et de l'exclusion ; suppression. Ni `visible`, ni `description`, ni `image_file`, ni `jobs_url`. |
| Arrêts, lignes et voies                                | Lecture | Lecture, ajout et suppression, usage des séquences.                                                                                                   |
| Lignes d'un arrêt, numéros d'une voie                  | Lecture | Lecture et ajout. La base les supprime avec leur arrêt ou leur voie, avec les droits du propriétaire de la table.                                     |
| `dataset_publications`                                 | Lecture | Lecture, ajout et modification.                                                                                                                       |

Les colonnes que la Mission Locale remplit restent fermées au worker, même si son identifiant fuit. Le rôle de l'API a `log_parameter_max_length = 0` et `log_parameter_max_length_on_error = 0` : PostgreSQL journalise une requête lente ou en erreur sans ses valeurs, dont la position d'un jeune.

Les [privilèges PostgreSQL](https://www.postgresql.org/docs/18/ddl-priv.html) sont distincts de la possession d'un identifiant de connexion. La configuration locale utilise un rôle Compose commun ; elle ne démontre pas la séparation des droits en production. Un profil Spring ne fournit pas cette séparation.

### Appliquer les migrations en production

1. Une tâche Flyway dédiée utilise temporairement l'identité de migration et ses secrets pour appliquer les scripts de la version sélectionnée.
2. Si une migration échoue, bloquer la suite du déploiement. Ne pas contourner l'erreur ni démarrer les nouveaux processus sur un schéma incomplet.
3. Démarrer les nouveaux processus API et worker avec `spring.flyway.enabled=false`, sans secret de migration. Hibernate continue de valider le schéma.

La [configuration Flyway de Spring Boot](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) décrit ce paramètre. Le [guide exploitation](../exploitation.md#configuration-et-migrations) fixe l'ordre de livraison ; le [guide secrets](../secrets.md#application-et-base-de-données) définit la distribution des identités.

### Faire évoluer une migration

- Nommer le fichier `V<n>__description.sql`, avec un numéro unique et croissant.
- Avant le premier déploiement, recette comprise : modifier en place le fichier du sujet concerné, qui décrit directement le schéma final. Chaque développeur remet ensuite sa base locale à zéro avec `npm run services:reset`.
- À partir du premier déploiement, les fichiers appliqués sont figés : ajouter une nouvelle version pour chaque évolution.
- Inclure les contraintes, index et reprises de données nécessaires. Vérifier la migration sur PostgreSQL réel.
- Nommer à partir de la table entière, au pluriel : `<table>_<colonnes>_key` pour une contrainte `UNIQUE` (la forme par défaut de PostgreSQL), `<table>_<sujet>_check` pour une contrainte sur plusieurs colonnes, `<table>_<sujet>_idx` pour un index, unique ou non. Le code lit certains noms pour répondre 409 : un renommage les change aussi dans le code. Les tables de Spring Session et de db-scheduler gardent les noms de leur bibliothèque.
- Écrire une date `TIMESTAMPTZ`.
- Terminer une migration qui change les données du schéma `reference` par un `ANALYZE` des tables touchées. L'autovacuum n'analyse une table qu'au-delà de 50 lignes changées : sans `ANALYZE`, une petite table comme celle des communes n'aurait pas de statistiques, et le planificateur estimerait mal ses jointures.
- Appliquer la règle expand/contract : le schéma N reste compatible avec la version N-1 de l'application. Flyway Open Source n'annule pas une migration ; l'ancienne image doit donc pouvoir tourner sur le nouveau schéma.
  - Expand : ajouter ce qui est nécessaire (table, colonne facultative, index) sans rien retirer.
  - Contract : retirer ou renommer seulement dans la version suivante, quand plus aucune image N-1 n'en dépend.
- Pour fusionner ou déplacer des tables, créer la nouvelle, y copier les données, puis remplacer chaque ancienne table par une vue modifiable du même nom : l'image N-1 continue d'écrire dans la nouvelle table, sans copie à rattraper. Retirer les vues dans la version suivante, quand plus aucune image N-1 n'en dépend. La migration prend ses locks avec un lock timeout court : si une transaction longue tient une ancienne table, elle échoue et se relance, au lieu de mettre en file les requêtes derrière elle.
- Ne jamais lire avec `SELECT *` : nommer les colonnes que le code utilise. Une connexion déjà ouverte de l'image N-1 qui relit avec `SELECT *` une table dont les colonnes changent échoue une fois (plan préparé périmé), puis se rétablit. Avec des colonnes nommées, ajouter une colonne ne casse pas la lecture.
- Ne pas assimiler une migration inverse à une restauration de données. La [procédure de rollback](../exploitation.md#rollback) distingue ces opérations.

## Établissements et données de référence

Les établissements de la carte viennent de l'API Sirene de l'INSEE ; nous ajoutons ce qu'elle ne donne pas.

| Données                                                                                                                                  | Origine                                                                   | Écriture                                                                                                                                                                                                             |
| ---------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Nom, adresse, position, activité, tranche d'effectif, catégorie et label ESS de chaque établissement                                     | API Sirene, par l'[import du worker](worker.md#import-des-établissements) | Réécrites à chaque import, par SIRET.                                                                                                                                                                                |
| Image, description, lien vers les offres d'emploi, visibilité                                                                            | Nous                                                                      | Jamais touchées par l'import. Un établissement importé reste invisible tant que nous ne le rendons pas visible.                                                                                                      |
| Nomenclatures NAF rév. 2 et NAF 2025, correspondance entre les deux, table ROME / NAF de France Travail                                  | Fichiers officiels de l'INSEE et de France Travail (Licence Ouverte)      | Migrations du schéma `reference`, générées par `scripts/reference-data.ts` ([outillage](../outillage.md)). Une activité ajoutée par une nouvelle version n'a pas de secteur : la classer dans la migration qui suit. |
| Communes de la métropole et leur centre (geo.api.gouv.fr), secteurs, secteur de chaque activité, activités précisées de certains métiers | Nous, à relire avec la Mission Locale                                     | Migrations du schéma `reference`, écrites à la main.                                                                                                                                                                 |

Le SIRET identifie un établissement de façon stable : favoris et démarches s'y rattachent. La carte ne montre que les établissements visibles et retenus ; pour elle, les autres n'existent pas.

Un établissement est retenu s'il remplit toutes ces conditions :

- il est ouvert et employeur, quelle que soit sa taille ;
- son activité a un secteur : la classification sert de liste blanche, et ce qui n'a pas été classé volontairement n'entre pas ;
- il a un nom qui n'est pas celui d'une personne : l'enseigne, sinon le nom commercial, sinon la raison sociale. Une raison sociale est le nom d'une personne pour un entrepreneur individuel ou un groupement de personnes. Sans enseigne ni nom commercial, un tel établissement est écarté, et sa raison sociale n'est jamais affichée. L'API Sirene ne donne pas les dirigeants : une société unipersonnelle (SASU, EURL) qui porte le nom de son fondateur garde sa raison sociale ;
- il a une adresse.

Sinon :

- un établissement non diffusible (opposition de la personne ou de l'entreprise) n'est jamais ajouté, et il est supprimé tout de suite s'il existait, même si quelque chose s'y rattache : l'opposition prime ;
- un établissement qui ne remplit plus une condition, ou absent d'une exécution complète (fermé, sans salarié, entreprise cessée) est masqué avec son motif et la date de sa première exclusion. Retenu de nouveau, il réapparaît avec ce que nous avons saisi. Exclu depuis plus de 90 jours, il est supprimé par la [purge du worker](worker.md#purge-des-établissements-exclus).

Ces durées valent tant que rien ne se rattache à un établissement. Les favoris et les démarches devront revoir ces deux règles : garder ce que le jeune a enregistré quand l'établissement disparaît, sans garder les données de l'établissement au-delà de leur durée.

L'activité est classée par son code NAF 2025, sinon par son code NAF rév. 2. Le secteur n'est pas copié sur l'établissement : il se lit dans la classification de son activité, seule source, et un changement de classification vaut tout de suite. La taille vient de la tranche d'effectif de l'établissement, pas de l'entreprise. Le type d'employeur (intérim, recrutement, formation, ESN) vient de l'activité et de la liste publique des organismes de formation, qui donne l'établissement déclarant. Le type est calculé à l'import. La taille est calculée par la base à partir de la tranche d'effectif : les deux ne peuvent pas diverger.

### Métiers et activités

France Travail relie chaque métier à des divisions NAF entières. Or une division peut contenir des exceptions rangées dans un autre secteur : la boulangerie artisanale est dans la division de l'industrie alimentaire. Une seule correspondance entre métiers et activités sert dans les deux sens, métier → établissements et établissement → métiers :

- dans chaque division, un métier prend les activités qui ont le secteur de la division, pas ses exceptions ;
- un métier qui travaille dans une exception a ses activités précisées à la main pour cette division (boulanger : boulangerie artisanale et commerce de pain) ;
- en NAF 2025, un métier prend les successeurs officiels de ses activités NAF rév. 2, selon la correspondance de l'INSEE, qui gardent leur secteur.

Un jeune choisit un métier par son libellé : la base garantit qu'un libellé ne nomme qu'un métier.

Chaque recherche par métier lit cette correspondance : la base la garde calculée et indexée. Toute migration qui change les métiers, les activités ou leurs secteurs la recalcule à sa fin ; `scripts/reference-data.ts` l'écrit pour les données officielles. Un test d'intégration compare, après les migrations, la correspondance enregistrée à son calcul : un recalcul oublié le fait échouer.

### Publier un établissement

L'import ajoute chaque établissement masqué. La Mission Locale choisit ceux que la carte montre et rédige ce que l'API Sirene ne donne pas. Une personne habilitée le fait avec son identité nominative d'administration, jamais avec celle de l'API ou du worker :

- rendre l'établissement visible (`visible`) ;
- écrire sa description (`description`), 2 000 caractères au plus ;
- donner le nom de sa photo (`image_file`), déposée selon [Photos des établissements](../exploitation.md#photos-des-établissements) ;
- donner le lien de ses offres (`jobs_url`), en HTTPS.

```sql
UPDATE establishments
SET visible = TRUE, description = '…', image_file = 'nom-de-la-photo.webp', jobs_url = 'https://…'
WHERE siret = '…';
```

La base refuse un nom de photo qui sortirait du dossier et un lien autre que HTTPS. L'import ne touche jamais ces colonnes. Un établissement exclu disparaît de la carte, visible ou non.

### Fichiers ouverts

La carte montre les arrêts Divia, et « Où ? » propose les adresses de la métropole. L'import des établissements lit aussi la liste des organismes de formation. Ces jeux de données sont des copies de fichiers ouverts, remplacées en entier par l'[import du worker](worker.md#import-des-fichiers-ouverts).

| Données                                          | Origine                                                                                | Ce qui est gardé                                                                                                                                                                                                                                           |
| ------------------------------------------------ | -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Arrêts de tram et de bus et leurs lignes         | GTFS de DiviaMobilités (Licence Ouverte 2.0)                                           | Les seuls arrêts desservis par une ligne. Les quais de même nom à moins de 150 m, un par sens, forment un seul arrêt, placé au milieu d'eux. Un arrêt de tram est un arrêt desservi par au moins une ligne de tram.                                        |
| Voies et numéros des 23 communes de la métropole | Base Adresse Nationale, fichier de la Côte-d'Or (Licence Ouverte 2.0)                  | Chaque numéro avec son indice de répétition (« 6 bis ») et sa position. Une voie est placée sur son numéro le plus proche du milieu de tous les autres : le point reste sur la voie même quand elle tourne. Les lieux-dits sans numéro ne sont pas gardés. |
| Organismes de formation                          | Liste publique des organismes de formation, Ministère du Travail (Licence Ouverte 2.0) | Le SIRET de l'établissement déclarant, rien d'autre.                                                                                                                                                                                                       |

- Chaque copie garde la date de publication de son fichier, pour la mention de la source : « DiviaMobilités / Dijon Métropole » et « Base Adresse Nationale », avec la date. Les logos et marques de Divia ne sont pas repris.
- L'arrêt le plus proche d'un établissement et sa distance se calculent à la lecture, par l'index géographique des arrêts : ils restent justes quand l'un des deux imports change, sans rien recalculer.
- Rien ne se rattache à un arrêt ni à une adresse : les remplacer en entier ne perd rien.

## Identifiants de compte

Les comptes et familles de reconnexion sont conservés dans PostgreSQL. La version des identifiants et l'état du compte sont vérifiés à chaque accès ; une ancienne session ne doit pas restaurer un accès révoqué.

- Utiliser un identifiant interne stable, distinct du pseudo et du email. Un changement de pseudo conserve les références et les données du compte.
- Ne donner au navigateur que l'identifiant public du compte, tiré au hasard par la base : l'identifiant interne, séquentiel, révélerait l'ordre et le nombre des inscriptions.
- Appliquer la même règle de pseudo à l'inscription, à Google, à la modification et à la connexion : la [règle du pseudo](../perimetre-v1.md#pseudo-et-avatar), vérifiée avant la mise en minuscules, indépendamment de la langue du serveur. Le pseudo est enregistré en minuscules et sert lui-même de clé ; une contrainte de la base n'admet que des minuscules, pour que l'unicité ignore la casse. Le frontend et le backend lisent les mêmes cas de test (`contracts/username-rule-cases.json`).
- Normaliser le email sans espaces de bord et en minuscules, sans fusionner les alias ni supprimer les points.
- Protéger ces clés par des contraintes uniques PostgreSQL, y compris face à des requêtes simultanées. Une vérification préalable dans l'interface ne suffit pas.
- Un compte n'a pas de pseudo entre la validation de son code et le choix de son pseudo : `username` est absent, et la contrainte unique admet plusieurs absences.
- Valider côté serveur les références d'avatar choisies dans le catalogue autorisé.

Un compte garde la date de sa création et celle de sa dernière ouverture de session, des données personnelles :

- la date de création est posée par la base ;
- la dernière ouverture de session est enregistrée à chaque session ouverte pour le compte (connexion, fin de l'inscription, reprise par reconnexion), sous le verrou du compte, jamais à chaque requête.

Une date ne se reconstitue pas après coup : elles sont gardées dès la création du compte. La durée de conservation d'un compte inactif se compte depuis sa dernière ouverture de session ; sa valeur fait partie des [questions de protection des données](../questions-ouvertes.md#protection-des-données-personnelles).

Les démarches Google en cours d'un compte sont supprimées avec lui, comme ses familles de reconnexion et ses demandes de récupération.

Quand la base refuse un identifiant déjà pris (pseudo, email, identifiant Google ou compte déjà lié à Google), l'adaptateur nomme l'identifiant concerné et le use case répond par le refus prévu. Une autre violation reste une panne (503).

Les [règles de sécurité](../securite.md#identifiants-et-mots-de-passe) fixent les garanties d'identité ; le [périmètre fonctionnel](../perimetre-v1.md#comptes-et-accès) décrit les choix de compte et d'avatar.

## JPA et accès aux données

Les entités JPA représentent les données persistées sous forme d'objets Java. Elles vivent dans `infrastructure` uniquement. Une entité ne sort jamais de `infrastructure` : ni vers `application`, ni vers un contrat HTTP, ni vers un autre domaine.

Pour un concept persisté, `infrastructure` contient trois types :

- `…JpaEntity`, la classe annotée JPA qui représente la table ;
- `SpringData…Repository`, l'interface Spring Data qui porte les requêtes ;
- `Jpa…Repository`, l'adaptateur qui implémente le port `…Repository` déclaré dans `application`.

L'adaptateur convertit dans les deux sens, par deux méthodes de l'entité : `from(objetMetier)` pour écrire et `toDomain()` pour lire.

Le use case appelle explicitement l'enregistrement. Une entité modifiée n'est jamais écrite implicitement : l'écriture reste visible dans le use case, au sein de sa transaction.

L'adaptateur détache chaque entité après sa conversion, y compris après un enregistrement. Sinon, le contexte de persistance garde une copie de la ligne : une lecture verrouillée plus tard dans la même transaction rendrait cette copie au lieu de la ligne à jour.

Chaque écriture de l'adaptateur part tout de suite en base (`saveAndFlush`, `@Modifying(flushAutomatically = true, clearAutomatically = true)`). Une requête JDBC de la même transaction voit ainsi la donnée écrite.

Le SQL natif n'est utilisé que lorsque JPA ne suffit pas, par exemple pour :

- un lock de ligne ou de PostgreSQL ;
- une purge en masse ;
- un `RETURNING`.

Il s'écrit dans l'interface Spring Data (`@Query(nativeQuery = true)`), donc dans `infrastructure`. Une opération en masse sur des tables qu'aucune entité du sujet ne représente, comme la purge, s'exécute par `EntityManager.createNativeQuery` dans l'adaptateur. La recherche de la carte aussi : ses filtres sont facultatifs et elle s'appuie sur PostGIS et `pg_trgm`. L'adaptateur n'ajoute que la clause d'un filtre donné, et chaque valeur est un paramètre lié, jamais du texte collé dans le SQL.

Pour chaque use case :

- Charger explicitement les données nécessaires et borner les collections et les recherches.
- Examiner les requêtes pour éviter de multiplier les lectures d'associations.
- Compléter les contrôles Java par les contraintes de base, notamment face aux requêtes concurrentes.
- Définir et tester la gestion des conflits et des mises à jour concurrentes.

Avec `spring.jpa.open-in-view=false`, ne pas attendre la production du JSON pour charger les associations d'une entité. Procéder dans cet ordre :

1. Dans la transaction du use case, charger les données et construire le résultat, y compris les valeurs des associations chargées à la demande.
2. Dans `api`, produire le DTO HTTP depuis ce résultat, sans nouvel accès à une entité ou au repository. `application` ne dépend pas des DTO de `api`.

La documentation Spring explique le rôle du [contexte de persistance lié à une requête](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/support/OpenEntityManagerInViewFilter.html).

## Transactions

Définir la transaction à l'entrée du use case dans `application`, pour couvrir les écritures qui doivent réussir ou être annulées ensemble. Les transactions individuelles des repositories ne garantissent pas la cohérence de plusieurs appels. Spring Data recommande ces [frontières au début d'une unité de travail](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).

Utiliser `@Transactional(readOnly = true)` pour une unité de lecture lorsque cela convient. Ce réglage est une indication d'optimisation, pas un contrôle d'autorisation ni une preuve qu'aucune écriture n'est possible. Les méthodes de requête déclarées dans un repository n'obtiennent pas toutes une transaction par défaut.

Les imports du worker sont l'exception : l'adaptateur pose la transaction de chaque écriture, car un import lit l'API Sirene ou un fichier pendant plusieurs minutes et aucun appel réseau ne doit tenir une transaction ouverte.

- L'import des établissements écrit chaque page de l'API Sirene dans sa propre transaction. Une page écrite est gardée si la suite échoue : l'exécution suivante reprend tout, et seule une exécution complète masque les établissements absents.
- L'import des arrêts, des adresses ou des organismes de formation télécharge d'abord son fichier, puis le remplace en une seule transaction.

### Faire appliquer la transaction par Spring

Spring utilise un proxy, c'est-à-dire un intermédiaire qui applique la transaction lors de l'appel d'une méthode :

- Placer `@Transactional` sur une méthode d'un composant géré par Spring, appelée par un autre composant.
- Un appel d'une méthode vers une autre méthode du même objet ne passe pas à nouveau par ce proxy. Il ne déclenche donc pas les règles transactionnelles de la méthode appelée.
- Une instance créée hors de Spring ne bénéficie pas du proxy. Ne pas compter non plus sur `@Transactional` dans un initialiseur.

Vérifier les scénarios d'annulation des écritures, ou **rollback**. La documentation décrit le [fonctionnement de l'annotation dans Spring Framework](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html).

### Erreurs et appels externes

- Par défaut, Spring annule la transaction pour les exceptions non vérifiées (`RuntimeException`) et les erreurs Java (`Error`). Préciser la politique nécessaire aux exceptions vérifiées du use case.
- Ne pas attraper puis masquer une erreur qui doit annuler la transaction.
- Ne jamais faire d'appel réseau dans une transaction ni en tenant un lock.

Pourquoi : un fournisseur lent ne doit bloquer ni l'API ni les connexions à la base.

Un email d'authentification est envoyé par l'API, sans journal ni réservation, en deux temps :

1. Décider l'email dans une transaction courte, puis la valider.
2. Après le commit, appeler le fournisseur avec un timeout borné, sans transaction ni lock en cours.

Aucun appel réseau n'a lieu dans une transaction. Les emails planifiés, comme les rappels d'événement, sont envoyés par le [worker](worker.md#envoi-des-emails).

- Un code ou un lien remplacé, utilisé ou expiré au moment de l'envoi n'est pas envoyé.
- Chaque message a sa propre clé d'idempotence : le flux et un identifiant de message tiré par le serveur. Le fournisseur ne reçoit jamais deux fois la même clé pour deux demandes distinctes.
- Le délai entre deux envois à une même adresse est posé dans Redis avant l'appel. Une demande qui arrive pendant l'envoi, ou après lui mais dans le délai, reçoit 429 sans second appel.
- Si le fournisseur n'accepte pas l'email, le jeune reçoit un 503. Il peut redemander après le délai entre deux envois (60 secondes) ; une demande répétée avant reçoit 429.
- Le timeout d'appel couvre la connexion et la réponse du fournisseur. Aucun appel simultané n'est borné par un plafond : chaque envoi attend son délai, et aucun service ne tient de connexion à la base pendant l'appel.
- Deux pertes sont acceptées : un email en double dans un cas rare, et un email perdu si l'API s'arrête pendant l'appel. Dans ce second cas, le jeune redemande un code.

Le [guide worker](worker.md#retry-et-idempotence) définit les exigences de retry et de prévention des doublons pour les tâches planifiées.

## Sessions

Spring Session JDBC conserve les sessions dans PostgreSQL et transmet leur identifiant dans le cookie `__Host-EMPLOIGO_SESSION` (`EMPLOIGO_SESSION` avec le profil `local`). Un visiteur anonyme n'a pas de session : elle est créée à la connexion, à la reconnexion et au départ du parcours Google. Le token CSRF est dans un cookie, pas dans la session ([sécurité](../securite.md#sessions-et-réglages-initiaux)). L'authentification crée une nouvelle session JDBC et renouvelle le token CSRF. Une sauvegarde concurrente de l'ancienne session ne doit pas écraser la session authentifiée.

| Réglage du dépôt                        | Valeur                                                                                                                                                                                                    |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Cookie                                  | `HttpOnly`, `Secure`, `SameSite=Lax`, préfixe `__Host-` ([cookies et sous-domaines](../securite.md#cookies-et-sous-domaines)). Le profil `local` retire seulement `Secure` et le préfixe pour HTTP local. |
| Délai technique d'inactivité par défaut | 30 minutes.                                                                                                                                                                                               |

Ces réglages ne suffisent pas à garantir la reconnexion durable du navigateur. Appliquer les durées, la rotation et les révocations du [guide sécurité](../securite.md#sessions-et-réglages-initiaux) dans les parcours de connexion.

Les contrôles de droits s'appliquent à chaque opération. Si les sessions sont indisponibles, refuser l'opération nécessitant l'authentification ; ne pas contourner cette vérification.

## Données temporaires d'authentification

Les inscriptions en attente restent distinctes des comptes actifs.

- L'email est réservé jusqu'à l'expiration de la demande.
- Une inscription ne garde aucun pseudo : le compte reçoit le sien après la confirmation du code.
- Les identifiants sont protégés par des contraintes PostgreSQL.
- La consommation du code, l'activation du compte et la création de ses accès sont atomiques.
- Les essais ratés d'un code et le total de l'inscription sont comptés dans la même transaction que la vérification. Un code faux ne lève pas d'erreur dans cette transaction, pour que le décompte soit enregistré. L'inscription est verrouillée pendant le contrôle : deux essais simultanés ne dépassent pas la limite.
- La clé d'inscription et l'email vivent dans la session du navigateur, supprimée après 30 minutes d'inactivité ; l'inscription n'en conserve que le hash. Un navigateur qui n'a pas la clé ne compte aucun échec.

Une récupération consomme le lien, change le hash du mot de passe et révoque tous les accès dans une même transaction. Les connexions concurrentes vérifient la version des identifiants réellement authentifiés avant de créer une session.

Les demandes Google sont liées au navigateur. Pour une association, elles sont aussi liées au compte et à ses accès courants.

- Un changement de compte ou un nouveau départ invalide les anciennes demandes, même si un échange avec Google est en cours.
- La liaison repose sur un identifiant Google unique.
- Un lien d'email Google n'est utilisé qu'une fois : la mise à jour ne passe que les liens encore en attente et non expirés.
- Une preuve invalidée par une récupération ou une suspension concurrente est refusée.
- Aucun token Google n'est conservé.

L'envoi des emails suit la règle [Erreurs et appels externes](#erreurs-et-appels-externes). Une demande répétée dans le délai entre deux envois ne produit pas de doublon. Le parcours public est décrit dans le [guide authentification](authentification.md#inscription-et-confirmation-de-lemail).

Seuls les hashes des codes, des liens et des clés d'inscription sont conservés en base, hors de la session du navigateur qui garde sa clé. Le hash de la clé d'une inscription confirmée est gardé avec elle jusqu'à sa suppression, 7 jours après l'expiration. Une inscription pour l'email d'un compte n'enregistre rien. La [tâche de purge du worker](worker.md#purge-des-données-temporaires) supprime ou efface les données temporaires à intervalle régulier, que le site reçoive des demandes ou non : l'API ne purge rien. Les expirations et les révocations restent contrôlées à chaque usage, même avant la purge. Les durées de conservation sont celles-ci, comptées depuis l'expiration :

| Donnée                                                     | Règle de la purge                                                                                                                                                                                                                                                      |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Demande d'inscription en attente                           | À son expiration, perd l'adresse, le hash du mot de passe et le hash de la clé (état « expirée »).                                                                                                                                                                     |
| Demande d'inscription confirmée ou expirée, avec ses codes | Supprimée 7 jours après l'expiration.                                                                                                                                                                                                                                  |
| Demande de récupération du mot de passe                    | Supprimée 7 jours après l'expiration.                                                                                                                                                                                                                                  |
| Démarche Google en cours, dossier Google en attente        | Supprimés 23 heures après l'expiration.                                                                                                                                                                                                                                |
| Vérification de l'adresse d'une identité Google            | Supprimée 7 jours après l'expiration.                                                                                                                                                                                                                                  |
| Famille de reconnexion                                     | Supprimée dès que son échéance est dépassée, révoquée ou non : elle ne sert plus qu'à refuser un cookie, ce que fait aussi une famille absente. L'échéance est de 30 jours avec « Rester connecté » ou par Google, de 8 heures (la durée maximale de la session) sans. |

La purge est en retard d'au plus son intervalle, plus la durée d'une exécution. Ce retard s'allonge de la détection d'un worker arrêté en pleine exécution (environ 30 minutes), et d'une exécution par tranche de 10 000 lignes d'une même donnée au-delà de cette taille. Surveiller la purge et sa rétention en [exploitation](../exploitation.md#protections-dauthentification).

## Redis : cache et protection contre les abus

Utiliser deux instances Redis par environnement : `limits` pour les compteurs antiabus, `cache` pour le cache. Une instance commune ferait refuser des compteurs dès que le cache remplit la mémoire. Distinguer les usages par des préfixes, des durées et des limites explicites.

### Comportement de chaque usage

| Usage              | Règle                                                                                                                 | En cas de panne ou de saturation                                                                                                                              |
| ------------------ | --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Cache              | Réutiliser seulement des résultats utiles et sûrs. Le cache ne remplace ni PostgreSQL ni les vérifications de droits. | Revenir à PostgreSQL en limitant les timeouts, le nombre de lectures simultanées et la charge. Une écriture de cache échouée n'annule pas une lecture valide. |
| Compteurs antiabus | Protéger les actions qui dépendent d'un quota. La mise à jour du compteur et son expiration doivent être atomiques.   | Selon l'action : la refuser sans l'exécuter (`503`) ou l'exécuter avec un avertissement et une métrique. Voir [Panne des compteurs](#panne-des-compteurs).    |

- Pour le cache, définir l'expiration, l'invalidation après modification et la séparation entre utilisateurs. Borner le nombre et la taille des entrées, ainsi que leur durée de vie.
- Prévoir et tester le repli vers PostgreSQL : sa capacité n'est pas illimitée.
- Réserver `429` à un quota effectivement dépassé. Une protection indisponible relève de `503`.
- Une limite IP du proxy ne remplace pas automatiquement une limite par utilisateur ou par action.

### Forme des quotas

Un quota compte les tentatives d'une action dans une fenêtre. Les limites de chaque action sont des [propriétés](developpement.md#quota-et-timeouts-redis), passées au compteur à chaque appel : l'adaptateur Redis ne lit aucune configuration.

| Compteur                 | Clé (préfixe `auth:limits:`, puis `<action>:`) | Ce qu'il compte                                                                                                                                               |
| ------------------------ | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Adresse                  | `ip:<hash du réseau>`                          | un réseau client : l'adresse IPv4 en entier, ou le préfixe /60 d'une adresse IPv6 ; une IPv4 mappée en IPv6 ou traduite par NAT64 (`64:ff9b::/96`) est l'IPv4 |
| Préfixe /48              | `prefix48:<hash du /48>`                       | actions d'email, client IPv6 seulement : les envois du préfixe /48 de l'adresse, qui regroupe 4 096 réseaux /60 ; absent pour une IPv4                        |
| Identité                 | `identity:<hash de l'identité>`                | une identité de l'action (compte, adresse électronique, preuve du navigateur)                                                                                 |
| Délai entre deux envois  | `cooldown:<hash de l'identité>`                | actions d'email seulement ; vit `send-delay`                                                                                                                  |
| Compte global des envois | `global`                                       | actions d'email seulement ; vit une fenêtre                                                                                                                   |

La reprise de la reconnexion (`resume`) n'a pas d'adresse : son seul compteur est celui de sa famille, `identity:<hash de l'identifiant de la famille>`, dans un script plus court. Il n'est appelé qu'une fois le secret de la famille vérifié, donc aucune clé n'est créée pour un cookie faux ou inconnu. Un ancien secret rejoué dans la tolérance est servi et compté comme une reprise. Le rejeu d'une demande d'email déjà répondue (inscription, renvoi de code, récupération) utilise le même script sur deux compteurs de l'action, `ip:` puis `identity:`, sans délai ni compte global.

La connexion a ses propres compteurs et son propre script : elle compte les échecs d'une identité, pas ses essais. Le tableau ci-dessus ne la concerne que pour l'adresse.

| Compteur | Clé                                                       | Ce qu'il compte                                                                                                                                                                                                                 |
| -------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Adresse  | `auth:limits:ip:<hash du réseau>` (sans nom d'action)     | toutes les tentatives admises, réussies, échouées ou interrompues ; une tentative refusée par le délai de son identité n'y compte pas ; vit `window`                                                                            |
| Échecs   | `auth:limits:login:failures:<hash de l'identifiant tapé>` | les échecs de l'identité, comptés avant la vérification ; une tentative interrompue garde le sien ; sa durée de vie est renouvelée à chaque échec (`counter-ttl`), et une réussite ou une récupération de mot de passe l'efface |
| Délai    | `auth:limits:login:delay:<hash de l'identifiant tapé>`    | présent seulement pendant le délai ; sa durée de vie est le délai ; une réussite ou une récupération de mot de passe l'efface                                                                                                   |

- Le script `begin` (un aller-retour) teste le délai (un délai en cours refuse avec ce qui reste, sans rien compter), puis compte l'adresse (une adresse au-delà de sa limite refuse sans créer aucune clé de l'identité), puis incrémente les échecs et, à partir du `free-failures`-ième, pose le délai `min(max-delay, base-delay × growth-factor^(échecs − free-failures))`. Il pose aussi le témoin, comme les autres scripts.
- Le délai est une clé avec une durée de vie : aucune horloge n'est passée au script et la durée ne dépend d'aucune instance de l'API. Redis y met fin.
- Un échec est compté une fois, avant la vérification, et n'est jamais rendu : une tentative interrompue (panne, plafond de calcul du hash, exception) garde son échec.
- Une réussite et une récupération de mot de passe effacent les échecs et le délai de l'identité, pas l'adresse. Une seule commande `DEL` supprime les deux clés, donc sans état intermédiaire. Un échec compté en même temps qu'une réussite peut être effacé avec elle : ce risque est accepté.
- `login` reste fermé : si le premier appel n'aboutit pas, la connexion répond 503.
- L'identité hachée est l'identifiant normalisé, ou `invalid:` suivi du hash du texte tapé pour un identifiant qu'aucun compte ne peut porter.

- Le réseau est déterminé par l'application (`ClientNetwork`), avant l'appel : une adresse illisible est comptée telle quelle, avec un avertissement (au plus un par minute) et la métrique `auth_rate_limit_address_unreadable_total`, car elle ne se regroupe pas. L'adresse est ensuite mise sous forme de hash à clé, comme l'identité (voir plus bas).
- Le script lit les compteurs dans cet ordre et s'arrête au premier refus : adresse, puis, pour un client IPv6 d'une action d'email, une lecture du préfixe /48 (refus s'il a déjà atteint sa limite), puis délai entre deux envois, puis identité, puis compte du préfixe et compte global. Une demande refusée par l'adresse ne crée donc aucune clé d'identité, de délai, de préfixe ou de compte global, et un préfixe plein ne laisse aucune clé d'identité : un client qui varie les identités ne remplit pas Redis (`noeviction`).
- Le délai est lu avant l'identité : une demande qu'il refuse n'envoie rien, donc ne compte pas dans l'identité.
- Le préfixe est compté après l'identité et le délai pour ne compter que les demandes qui envoient un email : une demande refusée par l'identité ou par le délai a déjà compté dans l'adresse, et ne retire rien à la part des autres clients du même /48. Une demande refusée par un préfixe plein a compté dans l'adresse seulement.
- Le préfixe /48 est déterminé par l'application (`ClientNetwork`) à partir du réseau : les 48 premiers bits d'une adresse IPv6. Une IPv4, une IPv6 qui enveloppe une IPv4 et une adresse illisible n'ont pas de préfixe.
- Un client IPv6 est compté par son /60 (`ClientNetwork`), et non par son /64. Un /48 contient ainsi 4 096 réseaux, donc au plus 4 096 clés d'adresse par action, au lieu de 65 536. Aucune propriété de configuration, aucun état de plus : `QuotaKeys.address` reste le hash du réseau, pour les 13 actions qui comptent l'adresse (toutes les `QuotaScope` sauf `RESUME`, connexion comprise).
  - Pourquoi le /60 : c'est la plus petite délégation connue d'un opérateur de réseau fixe à un abonné. Bouygues Telecom délègue un /60 (atelier IPv6 de l'ARCEP, décembre 2023), Orange un /56. Un foyer qui a un /60 ou plus ne gêne donc que lui-même.
  - Résiduel : un accès mobile reçoit un /64 pris dans un pool de l'opérateur, et plusieurs abonnés (mobiles ou en /64) partagent un même /60, donc un compteur. Un jeune sur mobile qui atteint `address-limit` sur son /64, ou un client qui parcourt ses /64, y épuise la part des autres : pour `address-limit` tentatives par fenêtre, il refuse les clients de son /60, et eux seuls.
  - Choix écartés : un plafond de clés par /48 et par action (un client qui le remplit fait refuser tous les nouveaux réseaux légitimes du /48, et il ajoute un compteur et une lecture aux scripts) ; 256 seaux par /48 (un détenteur d'un /56 en couvre environ 63 % et gêne les autres foyers du /48) ; 16 seaux par /56 (un abonné Bouygues, qui a un /60, couvre la plupart des seaux de son /56 et fait refuser jusqu'à 15 autres foyers).
  - Mémoire mesurée sur `redis:8.10.2` : une clé avec durée de vie occupe 126 octets (`MEMORY USAGE`), environ 170 octets avec le coût de l'expiration (100 000 clés : `used_memory` moins le démarrage). Cette valeur sert aux calculs ci-dessous.
  - Borne des quotas par /48, à un instant donné, clés d'identité comprises (au pire, avec la configuration par défaut). Une clé vit une fenêtre à partir de sa création : les clés de deux fenêtres consécutives coexistent.
    - clés d'adresse : au plus 4 096 par action et par fenêtre, soit environ 0,7 Mo, et 106 496 pour les 13 actions sur deux fenêtres ;
    - clés d'identité des huit actions sans email (hors connexion) : au plus 4 096 × `address-limit` par fenêtre (somme des `address-limit` : 250), soit 2 048 000 sur deux fenêtres ;
    - clés d'échecs de la connexion : au plus 4 096 × 90 par fenêtre, sur `counter-ttl` (30 min) plus une fenêtre, soit trois fenêtres : 1 105 920 ;
    - actions d'email : le préfixe /48 refuse avant la création de l'identité, donc au plus `prefix-limit` (120) clés d'identité et 120 de délai par action, soit environ 1 930 clés en tout sur deux fenêtres, avec les clés de préfixe ;
    - ensemble : environ 3,3 millions de clés, soit environ 550 Mo pour un /48 (170 octets la clé).
  - Cette borne n'est pas celle de la mémoire de Redis. Elle ne couvre pas les compteurs d'essais de code des pages de confirmation (`auth:limits:confirm:*`, deux clés de 24 heures par essai sur une page sans inscription, décrits plus bas) : leur nombre ne dépend que des essais admis par le quota `confirmation` et des demandes de code admises par le quota `registration-mail`. Elle suppose aussi qu'aucune règle du proxy ne limite un /48 : les règles de débit comptent par adresse IP, pas par /60 ni par /48, et un client réparti sur ses /64 ne les déclenche pas tant que chaque adresse reste sous le seuil. Elle reste 16 fois plus basse que sans regroupement, où les clés d'adresse sont au nombre de 65 536 par action et par /48.
  - Limite : la borne vaut par /48. Un détenteur de /32 en utilise 65 536 et multiplie d'autant les clés ; l'alerte sur la mémoire de l'instance `limits` le signale ([exploitation](../exploitation.md#protections-dauthentification)). Une adresse illisible (`ip:port` transmis par le proxy) est comptée telle quelle : elle crée une clé par valeur, et une valeur qui a la forme d'un réseau `v6:` suivi de 15 chiffres hexadécimaux se confond avec ce réseau. Elle relève de l'alerte `auth_rate_limit_address_unreadable_total`.
  - Au déploiement, les clés d'adresse IPv6 changent de nom : les compteurs d'adresse IPv6 repartent à zéro une fois, et expirent de toute façon en une fenêtre. Les compteurs IPv4 et ceux des identités ne bougent pas. Pendant un déploiement progressif, une ancienne instance compte encore par /64 pendant au plus une fenêtre : un client qui parcourt les 16 /64 d'un /60 y dispose de 16 fois sa limite, et les clés d'adresse d'un /48 peuvent y atteindre 65 536 par action.
- Les hashes des clés (adresse, préfixe, identité) sont des HMAC-SHA256 calculés avec `QUOTA_KEY`, en hexadécimal, par la seule classe `QuotaKeys` : quelqu'un qui lit Redis (sauvegarde, accès d'exploitation, fuite) ne retrouve ni l'email, ni le pseudo, ni l'adresse IP d'un jeune, même par dictionnaire. Le type de la valeur (adresse, préfixe, identité) entre dans le calcul, donc un même texte n'a pas la même hash dans deux rôles. Aucun limiteur ne calcule de hash lui-même : une nouvelle action de quota (`QuotaScope`) reprend ce mécanisme sans rien écrire de plus, et une règle d'architecture interdit à toute classe qui écrit dans Redis d'utiliser `AuthenticationSecrets`, le SHA-256 sans clé.
- Remplacer `QUOTA_KEY` remet tous les compteurs à zéro une fois, sans signaler de perte : la clé témoin ne porte aucune identité et reste en place. La [procédure](../secrets.md#clé-des-compteurs-de-quota) est dans les secrets.
- Le script de la reprise (`identity` seule) pose la clé avec la durée de la fenêtre au premier comptage, et en redonne une à une clé qui n'en aurait plus. Une famille refusée garde sa clé jusqu'à la fin de la fenêtre.
- Le compte global ne refuse jamais : au-delà de `global-alert-threshold`, la demande passe, avec un avertissement (au plus un par minute et par action) et la métrique `auth_rate_limit_global_exceeded_total{scope}`.
- La fenêtre de chaque action est la durée de vie de ses compteurs d'adresse, de préfixe, d'identité et global, et donc la plus longue attente annoncée. Changer une fenêtre ne modifie pas les compteurs déjà posés : ils expirent selon l'ancienne.

Les essais de code d'un navigateur qui ne détient pas la clé d'une inscription ont leurs propres compteurs, par email et par clé de sa session, hors des quotas ci-dessus. Ici, une page est un navigateur et la clé de son inscription. Les clés sont `auth:limits:confirm:{epoch,attempts,failures,wait}:*` par page et `auth:limits:confirm:{holder,email-epoch,email-attempts,email-failures,email-wait}:*` par email (la page qui tient l'email d'un compte et les compteurs de son code), nommées par un condensat avec clé (jamais l'email ni la clé en clair) et d'une durée de vie de 24 heures (1 heure pour les attentes). Ces compteurs gardent les réponses identiques quel que soit l'email : ils ne bornent aucune devinette, puisqu'une telle page ne peut jamais confirmer. Les essais d'une page ou d'un email sont rangés avec l'heure du code pour lequel ils comptent : un nouveau code les remet à zéro sans rien effacer. L'heure du dernier code est celle de la page qui l'a demandé, pas celle de l'email : elle remet à zéro les essais de cette page seule, et la page est déclarée expirée 15 minutes après son propre code. Seule la page qui tient l'email d'un compte compte sur l'email, comme la page d'une inscription ([sécurité](../securite.md#confirmation-dinscription)). Une page qui n'a jamais demandé de code n'expire donc jamais, quel que soit l'email, et une page dont l'inscription a été remplacée expire 15 minutes après son code. Un client anonyme crée au plus quelques clés par essai, dans la limite du quota `confirmation` par adresse, et une clé d'époque par demande de code, dans la limite du quota `registration-mail` : la mémoire de Redis relève de la supervision décrite dans l'[exploitation](../exploitation.md#protections-dauthentification). Si Redis ne répond pas, ces essais ne sont plus comptés et la réponse reste « code faux ». Avec Redis Cluster, toutes les clés d'un même script devraient en plus tomber dans le même slot (étiquettes `{…}` dans les noms) : ce n'est pas fait, car le projet utilise un Redis unique.

### Panne des compteurs

Le contrôle de quota n'ouvre aucune transaction, ne verrouille aucune ligne PostgreSQL et n'emprunte aucune connexion du pool : une panne de Redis ne retient que les requêtes qui l'appellent. Aucun état de l'API ne dépend de Redis : un redémarrage de Redis conserve les compteurs (journal AOF), et une perte réelle les remet à zéro, sans attente ni blocage.

- Le contrôle a trois résultats : autorisé, limité (avec l'attente du compteur, annoncée par `Retry-After`) ou indisponible.
- Le client borne la connexion et chaque commande à 500 ms. Une connexion coupée refuse les commandes aussitôt, et les commandes en cours sont bornées à 256.
- Il n'y a pas de coupe-circuit : chaque requête qui touche aux compteurs paie le timeout de 500 ms quand Redis ne répond pas, puis suit le mode de panne de son action.
- Le mode découle de l'action et ne se règle pas : une action qui vérifie un mot de passe ou envoie un email est fermée, les autres sont ouvertes. Le tableau les répartit :

| Mode   | Actions                                                                                                                                                                                                                                                      | Raison                                                                                              |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| Fermé  | Connexion ; demandes d'email d'inscription, de récupération et Google ; confirmation de la récupération ; liaison d'un compte Google à un compte local. Refus en `503` avec `Retry-After`, sans exécuter l'action.                                           | Un mot de passe serait vérifié ou un email partirait sans limite.                                   |
| Ouvert | Reprise de la reconnexion ; confirmation d'inscription ; confirmation d'email Google ; départ, retour et inscription du parcours Google. L'action s'exécute, avec un avertissement (au plus un par action et par minute) et une métrique à chaque exécution. | Le secret ou le token de 256 bits, ou l'état du parcours OAuth, est vérifié par l'action elle-même. |

- Le `503` d'une action fermée porte `Retry-After: 5` : une indication, pas un engagement.
- Une mémoire Redis pleine (`noeviction`) est une panne des compteurs : `503` pour une action fermée, jamais `500`.
- Les métriques, sur `/actuator/prometheus` : `auth_rate_limit_unavailable_total` (étiquettes `scope` et `outcome`, `refused` ou `executed`) et `auth_rate_limit_counters_lost_total` (pertes de compteurs détectées, voir [Mémoire et persistance](#mémoire-et-persistance)). `auth_rate_limit_global_exceeded_total` (étiquette `scope`) compte les envois d'email acceptés au-delà du seuil d'alerte global, et `auth_rate_limit_prefix_limited_total` (étiquette `scope`) les demandes d'email refusées parce que le préfixe /48 du client a atteint sa limite, avec un avertissement (au plus un par minute et par action, sans adresse ni préfixe) : voir [Forme des quotas](#forme-des-quotas). `auth_rate_limit_login_unrecorded_total` (étiquette `outcome`, `succeeded`, `abandoned` ou `reset`) compte les écritures de connexion que les compteurs n'ont pas pu enregistrer : réponses d'une tentative et remises à zéro de la récupération. Les propriétés sont dans le [guide de développement](developpement.md#quota-et-timeouts-redis).

### Mémoire et persistance

| Instance | Éviction                                                                                                                             | Persistance                                                                                       |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| `limits` | `noeviction` : quand la mémoire est pleine, Redis refuse les nouvelles écritures au lieu de supprimer des compteurs. Mémoire bornée. | AOF avec `appendfsync everysec`, sur un volume : au plus environ 1 seconde perdue au redémarrage. |
| `cache`  | `allkeys-lru` : Redis évince les entrées les moins récemment utilisées.                                                              | Aucune. Le cache se reconstruit depuis PostgreSQL.                                                |

- Un refus d'écriture est une panne des compteurs, traitée selon le mode de l'action : voir [Panne des compteurs](#panne-des-compteurs).
- Si `cache` est indisponible, le cache revient à PostgreSQL.
- La [politique d'éviction](https://redis.io/docs/latest/develop/reference/eviction/) vaut pour toute l'instance, même avec plusieurs bases logiques. C'est pourquoi chaque usage a sa propre instance.
- Voir la [persistance Redis](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) pour l'AOF.

Une perte réelle des compteurs (volume perdu, AOF illisible) est acceptée. Les actions protégées reprennent sans attente, avec des compteurs vides. L'API signale la perte par un avertissement et une métrique, sans jamais bloquer les actions.

- Le script des compteurs crée la clé témoin `auth:limits:witness` avec `SET NX`, sans durée de vie, dans le même aller-retour que le comptage. Quand il la crée, Redis est neuf ou a perdu ses données : l'API incrémente `auth.rate_limit.counters.lost` et écrit un avertissement, au plus un par minute, sans identité ni adresse.
- Le témoin n'a pas de durée de vie : c'est la seule clé de `auth:limits:*` qui n'expire pas. Il suppose la politique `noeviction` de l'instance `limits` : une éviction le supprimerait et signalerait de fausses pertes.
- Une perte est signalée au plus une fois, même avec plusieurs instances de l'API : celle dont le script a créé le témoin la signale. Le signal manque si la réponse de ce script n'arrive pas (timeout de 500 ms dépassé) : le témoin existe alors pour l'appel suivant.
- Seule une perte totale est détectée. Un arrêt brutal qui perd la dernière seconde de l'AOF, ou un AOF tronqué, garde le témoin écrit plus tôt : ces pertes partielles ne sont pas signalées.
- Un Redis qui redémarre avec son AOF garde le témoin : aucune perte n'est signalée.
- Le premier démarrage d'un environnement, avec un volume vide, est signalé comme une perte : un Redis vide ne distingue pas un début d'une perte. C'est attendu, une seule fois.

### Configuration et vérification

La configuration fixe les timeouts de connexion et de commande de Redis à 500 ms et exclut Redis de la santé globale. Cette exclusion ne fournit aucun repli ni protection antiabus : chaque fonctionnalité utilisant Redis doit définir et tester les comportements décrits ici.

Les sessions restent dans PostgreSQL et ne sont pas supprimées par une panne Redis.

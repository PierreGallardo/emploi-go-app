# Worker et traitements persistants

Ce guide définit comment exécuter les tâches enregistrées en base, les reprendre après un incident et éviter les effets en double. Il précise leur cohérence avec les écritures métier et complète les règles de [transactions](donnees.md#transactions) et d'[intégrations externes](architecture.md#intégrations-externes).

## Exécution

L'API et le worker utilisent le même JAR dans des processus distincts. Le profil `worker` active db-scheduler, qui lit les tâches persistées dans PostgreSQL et les exécute.

| Réglage du worker                 | Valeur configurée                                                    |
| --------------------------------- | -------------------------------------------------------------------- |
| Traitements simultanés            | Deux threads d'exécution.                                            |
| Recherche des tâches à exécuter   | Toutes les dix secondes.                                             |
| Attente maximale à l'arrêt        | Trente secondes.                                                     |
| Dates persistées par le scheduler | UTC.                                                                 |
| Purge des données temporaires     | Cinq minutes après la fin de l'exécution précédente.                 |
| Ports par défaut                  | 8090 pour le serveur HTTP, 8091 pour la gestion technique.           |
| Profils du lancement local        | `local,worker`, selon le [guide de développement](developpement.md). |

Le worker conserve un serveur HTTP pour ses besoins techniques. Il n'expose ni routes métier de l'API, ni Swagger UI, ni contrat OpenAPI.

Respecter la séparation des rôles :

- Le moteur d'exécution reste désactivé dans l'API. Une fonctionnalité qui programme des tâches utilise le [client de planification](#planifier-depuis-lapi) de l'API, sans activer ce moteur.
- Chaque traitement du worker possède sa définition de tâche et ses adaptateurs. L'activation du moteur ne les crée pas automatiquement.
- Un adaptateur de tâche dans `infrastructure` appelle le use case de son domaine. Il ne contourne pas les règles métier via un repository d'un autre domaine.
- Le worker exclut les auto-configurations Redis et Spring Session et ne crée pas de session HTTP. Il démarre avec PostgreSQL seul.

Le profil ne donne pas, à lui seul, des permissions ou des secrets propres au processus. Les guides [secrets](../secrets.md) et [exploitation](../exploitation.md) définissent ces restrictions et la procédure d'arrêt en environnement déployé.

## Tâches du worker

Une tâche est un traitement planifié, enregistré en base puis exécuté par le worker. Le worker exécute ces tâches :

| Tâche                              | Rôle                                                                                                 |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Rappels d'événement                | Envoyer les rappels demandés par les jeunes.                                                         |
| Purge des données temporaires      | Supprimer ou effacer les données d'authentification échues, même sans trafic.                        |
| Import des établissements          | Mettre à jour chaque jour les établissements de la carte depuis l'API Sirene de l'INSEE.             |
| Import des arrêts Divia            | Mettre à jour chaque nuit les arrêts de tram et de bus et leurs lignes.                              |
| Import des adresses                | Mettre à jour chaque nuit les adresses de la métropole proposées dans « Où ? ».                      |
| Import des organismes de formation | Mettre à jour chaque nuit la liste des organismes de formation, lue par l'import des établissements. |
| Purge des établissements           | Supprimer les établissements exclus depuis plus de 90 jours.                                         |

## Écriture métier et planification

La modification métier et la création, annulation ou replanification de sa tâche doivent réussir ou être annulées ensemble :

- Aucune tâche ne doit provenir d'une modification métier annulée.
- Aucune modification validée ne doit perdre la tâche requise.

Vérifier les deux sens sur PostgreSQL, avec des tests de validation et d'annulation de transaction, ou **commit** et **rollback**.

Pour que la planification d'une tâche soit annulée avec la transaction qui l'a créée :

- `@Transactional` sur le use case ne suffit pas.
- `SchedulerClient` obtient sa connexion depuis un `DataSource` : ce `DataSource` doit utiliser la transaction Spring en cours.
- Avoir les tables dans la même base ne suffit pas non plus.

Voir les [précautions transactionnelles de db-scheduler](https://github.com/kagkarlsson/db-scheduler#things-to-note--gotchas).

Si une autre stratégie de livraison fiable est nécessaire, en définir les états, retries et tests avant utilisation. Programmer une tâche uniquement après le commit sans trace durable laisse un risque de perte entre le commit et la programmation.

### Planifier depuis l'API

L'API fournit un `SchedulerClient`, moteur désactivé, dont la connexion suit la transaction Spring en cours. Une fonctionnalité l'injecte et l'appelle dans la transaction de son use case : elle ne crée ni son propre client ni son propre `DataSource`. Une tâche planifiée par une transaction annulée n'existe pas, et celle d'une transaction validée n'est pas perdue.

Planifier deux fois la même tâche en même temps depuis deux transactions annule la seconde : la violation de l'unicité interrompt sa transaction PostgreSQL, et la refetch que fait le client échoue. Pour une tâche qu'une demande répétée peut planifier de nouveau, planifier avec `scheduleIfNotExists`, après avoir verrouillé la donnée métier concernée pour sérialiser les demandes.

Dans le worker, le scheduler est lui-même un `SchedulerClient`. L'API ne relit les données d'une tâche que si la définition de cette tâche est un composant connu des deux processus ; son exécution reste réservée au worker.

### Données des tâches

Les données d'une tâche sont stockées en JSON, avec le même sérialiseur dans l'API qui les écrit et dans le worker qui les lit. La sérialisation de Java est exclue : une classe modifiée rendrait illisibles les lignes déjà écrites. Ce format est propre aux tâches : un changement de la représentation de l'API HTTP ne le modifie pas.

- Définir les données par un `record` de la fonctionnalité, qui ne contient que des types simples, des dates et des listes.
- N'y faire qu'ajouter des champs, comme pour une colonne. Un worker qui ne connaît pas encore un champ l'ignore ; une valeur absente est lue comme `null`, ou zéro pour un nombre primitif : préférer un type objet quand l'absence a un sens.
- Ne renommer ni retirer un champ, ni changer son type, tant qu'une tâche écrite avec l'ancien format peut exister. Laisser d'abord ces tâches s'exécuter ou les migrer.

Les tests vérifient que ce que l'API planifie est stocké sous la forme attendue et que le worker relit cette forme à l'identique.

## Retry et idempotence

Une tâche peut être tentée plusieurs fois après une interruption. Sa retry doit éviter de produire un deuxième effet pour la même opération : c'est l'objectif de l'idempotence.

Pour chaque traitement :

1. Attribuer une identité stable à l'opération, conservée entre les tentatives.
2. Relire les données faisant autorité et vérifier la pertinence de l'action à chaque tentative. Un changement de données ne doit pas rendre une ancienne tâche dangereuse.
3. Enregistrer un résultat ou une progression durable pour distinguer les étapes terminées de celles à reprendre.
4. Espacer et borner les retries. Fixer les timeouts des clients et la durée du traitement pour permettre un arrêt contrôlé.

| Situation                  | Comportement attendu                                                                                                                       |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Panne temporaire           | Réessayer (retry) selon les timeouts et les limites définis pour le traitement.                                                            |
| Erreur définitive          | Arrêter les tentatives et conserver le résultat d'échec.                                                                                   |
| Tâche devenue obsolète     | Annuler, recalculer ou abandonner selon les données et la règle métier actuelles.                                                          |
| Résultat externe incertain | Ne pas relancer aveuglément. Utiliser les garanties du fournisseur lorsqu'elles conviennent et définir le traitement de cette incertitude. |

Une réservation de tâche et une transaction PostgreSQL ne garantissent pas qu'un effet externe a lieu exactement une fois. Le fournisseur peut accepter un envoi avant qu'une interruption empêche d'enregistrer son résultat. Lorsqu'il propose un mécanisme d'idempotence adapté, utiliser une clé stable et tenir compte de sa durée de validité. Pour une même clé, envoyer un body identique à l'octet près, champs dans un ordre fixe : le fournisseur refuse une retry dont la charge diffère. Rattacher la clé à l'enregistrement de l'envoi, pas au seul identifiant choisi par le client : le fournisseur peut la retenir encore après la purge de l'enregistrement, et un nouvel envoi de contenu différent sous la même clé est refusé en 409.

Suivre les tâches en attente, leur retard, les retries, les échecs et les abandons sans exposer les données des destinataires.

## Envoi des emails

Le worker envoie les emails planifiés, comme les rappels d'événement. Les emails d'authentification sont envoyés directement par l'API, pour que le jeune connaisse le résultat tout de suite (voir le [guide authentification](authentification.md#inscription-et-confirmation-de-lemail)).

Pour un email planifié :

1. La tâche appelle le fournisseur hors transaction.
2. En cas de panne temporaire, elle réessaie avec des délais croissants et un nombre de tentatives borné.
3. Après une erreur définitive, elle arrête les tentatives et conserve le résultat d'échec.

Pourquoi : un fournisseur lent ou indisponible ne bloque ni le worker ni les connexions à la base.

L'envoi suit les règles de [retry et d'idempotence](#retry-et-idempotence) : chaque email possède une identité stable, transmise au fournisseur pour éviter les doublons. Les destinataires ne figurent pas dans les logs.

## Purge des données temporaires

Une tâche récurrente supprime ou efface les données d'authentification devenues inutiles, que le site reçoive des demandes ou non. L'API ne purge rien. Les données concernées et leurs durées de conservation sont décrites une seule fois, dans le [guide des données](donnees.md#données-temporaires-dauthentification). L'expiration d'une demande reste contrôlée à chaque usage, même avant la purge.

| Point             | Règle                                                                                                                                                                                                                                                                                                                   |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Planification     | Une seule instance de la tâche, replanifiée cinq minutes après la fin de chaque exécution (`emploi-go.purge.interval`, entre une minute et une heure). Une exécution ne chevauche donc jamais la suivante, même si elle dure plus longtemps.                                                                            |
| Plusieurs workers | Le scheduler réserve l'instance de la tâche : un seul worker l'exécute à la fois. Si un worker s'arrête en cours d'exécution, le scheduler la relance une fois le battement de cœur expiré (environ 30 minutes avec ses réglages par défaut). La purge ne traite que ce qui est échu : elle se répète sans risque.      |
| Batches           | Cent lignes par requête, chacune dans sa propre transaction : aucune connexion ni lock n'est tenu entre deux batches. Au plus cent batches par donnée et par exécution ; le reste attend l'exécution suivante, et un avertissement nomme la donnée en retard, sans adresse ni identifiant.                              |
| Échec             | Une donnée qui échoue n'empêche pas la purge des autres, sauf si la base est injoignable : l'exécution s'arrête alors à la première tentative. Elle échoue, le scheduler la compte, puis la replanifie à l'intervalle suivant. L'arrêt du worker n'est pas un échec : l'exécution s'interrompt et la suivante continue. |
| Accès aux données | La purge a ses propres adaptateurs, chargés par le worker seul. Elle ne charge ni la préparation ni l'envoi des emails d'authentification, qui restent dans l'API. Les droits PostgreSQL du worker sont listés dans le [guide des données](donnees.md#identités-et-droits-en-production).                               |

Surveiller la tâche selon l'[exploitation](../exploitation.md#protections-dauthentification).

## Import des établissements

Le worker importe chaque jour les établissements de Dijon Métropole depuis l'[API Sirene](https://portail-api.insee.fr) de l'INSEE (version 3.11), qui cherche directement des établissements. Les règles de sélection et les données conservées sont décrites dans le [guide des données](donnees.md#établissements-et-données-de-référence).

| Point              | Règle                                                                                                                                                                                                                                                                                                                                                                               |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Planification      | Une seule instance, chaque jour à l'heure de Paris fixée par `emploi-go.establishment-synchronization.daily-at` (11 h par défaut : l'INSEE conseille de lire par pages après 10 h, heure de sa mise à jour quotidienne). Un seul worker l'exécute à la fois. Le [premier import](#premier-import) n'attend pas cette heure.                                                         |
| Clé d'API          | `SIRENE_API_KEY`, envoyée dans l'en-tête `X-INSEE-Api-Key-Integration`. Sans clé, le worker démarre et l'import échoue avec une cause claire. La clé n'apparaît ni dans les logs, ni dans les messages d'erreur.                                                                                                                                                                    |
| Collecte           | Une seule recherche : les établissements ouverts et employeurs de toutes les communes de la métropole, lus par pages de 1 000 avec le curseur de l'API (une vingtaine de requêtes).                                                                                                                                                                                                 |
| Limites d'appel    | Au plus 30 requêtes par minute (une toutes les 2,1 s), avec un `User-Agent` qui nomme le service. Une réponse 429 est réessayée après l'attente de `Retry-After` (une minute au plus) ; une panne, un délai dépassé ou une réponse illisible, après des attentes croissantes. Une clé refusée (401, 403) n'est pas réessayée. Au-delà de quatre tentatives, l'exécution échoue.     |
| Écriture           | Une transaction par page de résultats, écrite par lots, par SIRET, sans lecture préalable. Aucun appel réseau dans une transaction. Répéter l'import ne change rien de plus : il réécrit les mêmes données.                                                                                                                                                                         |
| Exécution complète | Seule une exécution qui a lu autant d'établissements que l'API en annonce masque ceux qu'elle n'a pas revus. Un établissement que le client ne sait pas lire, un total qui bouge pendant la lecture, ou une exécution qui n'en lit aucun, la rend incomplète : rien n'est masqué. Un établissement fermé ou qui n'emploie plus n'est plus listé : il passe « absent de la source ». |
| Échec et arrêt     | Une exécution en échec garde ce qu'elle a écrit et reprend tout une heure plus tard, trois fois au plus, puis à l'heure quotidienne suivante. Un redémarrage du worker garde la reprise prévue, son heure et son nombre d'échecs. L'arrêt du worker interrompt l'exécution entre deux pages, sans échec : la suivante a lieu à l'heure quotidienne.                                 |

Particularités de la source :

- La position est donnée en Lambert 93 (EPSG:2154) ; l'import la convertit en degrés WGS 84. Un établissement sans coordonnées est gardé sans position.
- Le code NAF vient de la période courante de l'établissement et de son champ NAF 2025 : l'INSEE passe l'activité principale en NAF 2025 au 1er janvier 2027 et retire alors le champ à part. Les deux cas sont lus.
- L'adresse est reconstituée à partir de ses éléments (numéro, type et libellé de voie, code postal, commune) ; le type de voie reste abrégé comme l'INSEE le donne.
- L'API ne donne ni dirigeants, ni déclaration d'organisme de formation : la liste publique des organismes de formation est importée à part (voir [Import des fichiers ouverts](#import-des-fichiers-ouverts)).

La supervision de l'import est décrite dans le [guide exploitation](../exploitation.md#import-des-établissements).

### Purge des établissements exclus

La tâche `purge-excluded-establishments` supprime chaque jour à 5 h 30, heure de Paris, les établissements exclus depuis plus de 90 jours (voir la [conservation](donnees.md#établissements-et-données-de-référence)). Elle ne dépend d'aucun import. Elle supprime par lots de 500, chacun dans sa transaction, au plus 50 000 par exécution : le reste attend le lendemain. Une exécution en échec reprend comme un import : une heure plus tard, trois fois au plus.

## Import des fichiers ouverts

Le worker copie chaque nuit trois fichiers de données ouvertes publiés sous Licence Ouverte 2.0. Les données conservées sont décrites dans le [guide des données](donnees.md#fichiers-ouverts).

| Fichier                                                                                                                                                                    | Tâche                                | Heure de Paris                                                                                                             |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| [GTFS DiviaMobilités](https://www.data.gouv.fr/datasets/gtfs-diviamobilites), sur data.gouv.fr                                                                             | `synchronize-transit-stops`          | `emploi-go.dataset-synchronization.transit-stops.daily-at` (4 h par défaut)                                                |
| [Base Adresse Nationale](https://adresse.data.gouv.fr/donnees-nationales), Côte-d'Or                                                                                       | `synchronize-addresses`              | `emploi-go.dataset-synchronization.addresses.daily-at` (4 h 10 par défaut)                                                 |
| [Liste publique des organismes de formation](https://www.data.gouv.fr/datasets/liste-publique-des-organismes-de-formation-l-6351-7-1-du-code-du-travail), sur data.gouv.fr | `synchronize-training-organizations` | `emploi-go.dataset-synchronization.training-organizations.daily-at` (4 h 20 par défaut), avant l'import des établissements |

| Point          | Règle                                                                                                                                                                                                                                                                                                                                                          |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Téléchargement | Un seul appel par exécution, borné en durée et à 128 Mio, avec un `User-Agent` qui nomme le service. La date de publication du fichier est son `Last-Modified`, à défaut l'heure du téléchargement. Un fichier qui n'a pas changé depuis l'import précédent n'est ni téléchargé à nouveau (`If-Modified-Since`) ni réécrit.                                    |
| Écriture       | Les données du fichier remplacent les précédentes en une seule transaction, après le téléchargement : les lecteurs voient l'ancienne copie ou la nouvelle, jamais un mélange. Aucun appel réseau dans la transaction.                                                                                                                                          |
| Fichier refusé | Un fichier illisible, tronqué, ou qui ne donne aucun arrêt desservi, aucune adresse de la métropole ou aucun organisme de formation fait échouer l'exécution : la copie en place est gardée.                                                                                                                                                                   |
| Échec et arrêt | Comme pour l'import des établissements : nouvelle tentative une heure plus tard, trois fois au plus, puis à l'heure quotidienne suivante ; un redémarrage du worker garde la reprise prévue. L'arrêt du worker pendant le téléchargement n'est pas un échec : la suivante a lieu à l'heure quotidienne. Une copie d'un jour ou deux reste juste pour la carte. |

La supervision est décrite dans le [guide exploitation](../exploitation.md#import-des-fichiers-ouverts).

## Premier import

Un environnement neuf, une base restaurée sans ces données ou remise à zéro n'attend pas l'heure quotidienne : au démarrage, le worker lance tout de suite chaque import qui n'a jamais réussi dans cette base.

| Point           | Règle                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Jamais réussi   | Un fichier ouvert sans date de publication dans `dataset_publications` ; une table `establishments` vide. Une exécution en échec ou interrompue avant d'écrire compte comme jamais réussie ; un import des établissements interrompu après sa première page compte comme réussi, et la suite attend 11 h.                                                                                                                                                    |
| Ordre           | Les arrêts, les adresses et les organismes de formation partent au démarrage. Les établissements lisent les organismes de formation : ils partent au démarrage si les organismes sont déjà en base, sinon à la fin du premier import réussi des organismes, reprise comprise. Tant que celui-ci échoue, ils gardent leur heure de 11 h.                                                                                                                      |
| Mécanisme       | Au démarrage du scheduler, chaque tâche quotidienne est créée à son heure, puis avancée à maintenant si son import n'a jamais réussi (`reschedule` de db-scheduler). Ce `reschedule` refuse une exécution prise et vérifie la version de la ligne : deux workers qui démarrent ensemble n'exécutent jamais le même import en même temps. Un refus est journalisé et ne fait rien échouer.                                                                    |
| Ensuite         | Un import réussi garde son heure quotidienne : un redémarrage ou un déploiement ne relance rien. Un échec est repris comme d'habitude : une heure plus tard, trois fois au plus, puis à l'heure quotidienne. Un démarrage ne touche pas à une reprise prévue : elle garde son heure et son nombre d'échecs. Une fois les reprises épuisées, un démarrage relance aussitôt un import qui n'a toujours jamais réussi, jamais en même temps qu'un autre worker. |
| Sans clé Sirene | L'import des établissements échoue avec « clé d'API absente » et reprend comme tout échec ; un redémarrage pendant ses reprises ne les relance pas, mais la première réussite des organismes de formation le relance aussitôt et remet ses reprises à zéro. Ensuite, il attend 11 h ou le démarrage suivant.                                                                                                                                                 |

Chaque tâche avancée écrit « Premier import de `<tâche>` : lancé au démarrage du worker » ou « … lancé après le premier import de synchronize-training-organizations ». Un avancement impossible écrit un avertissement et la tâche garde son heure quotidienne.

## Rappels d'événement

Les règles fonctionnelles sont définies dans le [périmètre des emails et rappels](../perimetre-v1.md#emails-et-rappels) : demande explicite, échéance vingt-quatre heures avant le début horodaté, annulation et recalcul après changement pertinent.

À chaque tentative, vérifier :

- La demande de rappel est toujours active.
- L'événement est publié et n'est pas annulé.
- Sa date de début actuelle et l'échéance du rappel restent cohérentes avec la tâche.
- L'état d'envoi autorise la tentative sans produire de doublon.

| Moment de la retry                   | Règle                                                                       |
| ------------------------------------ | --------------------------------------------------------------------------- |
| Avant le début actuel de l'événement | Reprendre un rappel retardé s'il satisfait encore toutes les vérifications. |
| Dès le début de l'événement          | Abandonner sans nouvel appel au fournisseur et enregistrer ce résultat.     |

Cette limite porte sur le déclenchement de l'envoi, pas sur l'heure de réception d'un message déjà accepté. La retry n'autorise pas une nouvelle demande après l'échéance des vingt-quatre heures.

Vérifier ces règles avec des cas d'annulation, de déplacement, d'interruption, de retry et d'abandon.

## Intégrations externes

Limiter les appels du worker aux fournisseurs nécessaires à ses traitements, notamment Resend pour l'envoi des emails, l'API Sirene de l'INSEE pour l'import des établissements, data.gouv.fr et adresse.data.gouv.fr pour les arrêts, les adresses et les organismes de formation. Le [guide d'architecture](architecture.md#intégrations-externes) définit les composants retenus, leurs limites d'appel et leur configuration.

Appliquer les règles de [retry et d'idempotence](#retry-et-idempotence) à chaque effet externe. Fournir au worker uniquement les secrets nécessaires à ses appels, selon le [guide des secrets](../secrets.md#inventaire-et-séparation-des-droits).

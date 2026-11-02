# Architecture du backend

Ce guide explique comment organiser le code Java du backend et faire collaborer ses domaines. Il fixe le rôle des packages et les dépendances autorisées. L'[architecture commune](../architecture.md) présente les services et leurs échanges ; les [règles de sécurité](../securite.md) définissent les obligations d'accès et de confidentialité.

## Module et processus

Un seul module Maven, `emploi-go-backend`, produit le JAR utilisé par l'API et le worker. Le code est organisé par domaines métier dans ce module : c'est un monolithe. Les deux rôles partagent les use cases et les modèles nécessaires, mais s'exécutent dans des processus séparés.

| Processus | Responsabilité                                                                                                                 | Guide de référence  |
| --------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------- |
| API       | Recevoir les demandes HTTP et programmer les tâches nécessaires aux use cases. Le moteur d'exécution des tâches est désactivé. | [API](api.md)       |
| Worker    | Exécuter les tâches persistantes. Le profil `worker` active le moteur et exclut les composants réservés à l'API.               | [Worker](worker.md) |

PostgreSQL conserve les données durables, les sessions et les tâches. Une instance Redis par environnement sert au cache et à la protection contre les abus. Le [guide données](donnees.md) décrit ces usages et leurs garanties.

Un profil Spring sélectionne des composants et des paramètres. Il n'isole pas les droits d'un processus. Les identités de service, secrets, réseaux et permissions API/worker doivent être limités séparément lors du déploiement ; cette séparation n'est pas fournie par le JAR commun.

### Rôle API ou worker

Le JAR est commun : chaque composant Spring déclare par une annotation de `fr.emploigo.config` le ou les processus qui le chargent.

| Annotation      | Chargé par                                                                                                                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@ApiOnly`      | L'API seule : contrôleurs, sessions, Redis, quotas, envoi des emails d'authentification, recherche de la carte.                                        |
| `@WorkerOnly`   | Le worker seul : tâches planifiées, purge et leurs adaptateurs.                                                                                        |
| `@ApiAndWorker` | Les deux : notamment l'horloge, la configuration de sécurité et l'ordonnancement. Cette annotation n'ajoute aucune condition ; elle consigne le choix. |

`ApplicationRole` est le seul endroit du code Java qui nomme le profil `worker` : le rôle est `WORKER` quand ce profil est actif, `API` sinon. `@ApiOnly` et `@WorkerOnly` reposent sur `@Conditional` et non sur `@Profile`, car des `@Profile` empilés se combinent en « ou » (`ProfileCondition` retient la première expression qui correspond), alors que des conditions distinctes se combinent en « et ». Une classe peut donc ajouter son propre `@Profile("local")` à `@ApiOnly`. Un code qui a besoin du rôle l'obtient par `ApplicationRole.of(environment)`.

Pour une nouvelle classe API, écrire l'annotation à côté du stéréotype :

```java
@Service
@ApiOnly
public class PublishEventUseCase {}
```

Le rôle d'une méthode `@Bean` suit sa configuration, ou `@ApiOnly` sur la méthode. Une classe `@ConfigurationProperties` enregistrée par `@EnableConfigurationProperties` suit aussi la configuration qui l'active ; l'annotation de la classe documente son rôle.

Trois règles ArchUnit imposent ce modèle : toute classe `@Component` (ou méta-annotée : `@Service`, `@Repository`, `@RestController`, `@Configuration`), toute classe `@ConfigurationProperties` et tout repository Spring Data porte exactement une des trois annotations et aucun `@Profile` qui nomme le worker ; tout composant d'un package `api` est `@ApiOnly` ; une classe `@ApiAndWorker` n'est pas dans un package `api` et ne touche ni Redis, ni les sessions (`spring.session`, `HttpSession`), ni une annotation de contrôleur, et aucune classe `@ApiAndWorker` ou `@WorkerOnly` ne dépend d'une classe `@ApiOnly`. Les règles lisent les dépendances directes d'une classe, pas celles de ses auxiliaires ; le contexte réel du worker complète ce que le code compilé ne montre pas. Le worker garde son serveur de supervision : Spring Security et le serveur HTTP restent permis. Un rôle posé sur une méthode `@Bean` ne contredit pas celui de sa classe. Le chargement est contrôlé sur des contextes réels, sans liste de classes écrite à la main : `WorkerInfrastructureIT` vérifie qu'aucune classe `@ApiOnly`, aucun composant d'un package `api` et aucun bean issu d'une méthode `@ApiOnly` n'est dans le contexte du worker, et que celui-ci n'a ni contrôleur de l'application, ni connexion Redis, ni autre chaîne de filtres que celle de `SecurityConfiguration` ; `ApiWorkerSeparationIT` vérifie qu'aucune classe `@WorkerOnly` n'est dans celui de l'API.

Les blocs `on-profile: '!worker'` de `application.yml` (import de `application-api.yml`) et de `application-local.yml` sont le seul autre endroit qui nomme le rôle : ils sélectionnent des paramètres, pas des composants, et Spring Boot les active par `spring.config.activate.on-profile`.

## Organisation des packages

La classe `EmploiGoApplication` reste à la racine `fr.emploigo`, au-dessus des composants scannés. Chaque fonctionnalité appartient à un domaine identifiable ; ne pas créer de packages techniques globaux `controller`, `service`, `repository` ou `entity`. Ce choix suit la [structure par fonctionnalités de Spring Boot](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html).

Cet exemple fictif illustre l'organisation d'un domaine `event`. Il ne décrit pas les classes présentes dans le dépôt.

```text
fr.emploigo
├── EmploiGoApplication
├── config
└── event
    ├── api
    │   └── publication
    │       ├── PublishEventController
    │       └── PublishEventRequest
    ├── application
    │   └── publication
    │       ├── PublishEventUseCase
    │       ├── PublishEventCommand
    │       ├── PublishEventResult
    │       └── EventRepository
    ├── domain
    │   ├── Event
    │   └── EventAlreadyPublishedException
    └── infrastructure
        └── publication
            ├── EventJpaEntity
            ├── JpaEventRepository
            └── SpringDataEventRepository
```

Le contrôleur traduit la demande HTTP. Le use case vérifie les droits et coordonne la publication. Le modèle porte les règles de l'événement. `EventRepository` est un port : `application` le déclare sans savoir comment les données sont stockées. Dans `infrastructure`, l'adaptateur `JpaEventRepository` l'implémente avec JPA.

Dans un domaine, un sous-package suit `<domaine>.<couche>.<sujet>`, par exemple `auth.application.google`. Le sujet est un mot en minuscules, au singulier. Il a le même nom dans `api`, `application` et `infrastructure`. La couche se lit juste après le domaine : c'est là que les contrôles d'architecture la cherchent.

Un domaine utilise les packages suivants selon ses responsabilités. Créer un package uniquement lorsqu'il contient un composant utile ; les quatre couches ne sont pas obligatoires pour chaque domaine.

| Package du domaine | Responsabilité                                                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `api`              | Contrôleurs, DTO HTTP, validation des entrées et traduction des résultats ou erreurs vers HTTP.                                 |
| `application`      | Use case, ports (interfaces des sorties), contrôle des droits sur les ressources, coordination et frontières transactionnelles. |
| `domain`           | Règles métier, valeurs et modèles du domaine, en Java simple.                                                                   |
| `infrastructure`   | Entités JPA, interfaces Spring Data, adaptateurs des ports, adaptateurs vers Redis, SDK ou tâches du worker.                    |

Une table n'a qu'un seul port d'accès quand plusieurs parcours la partagent : `AccountRepository` pour `auth_accounts`, avec la règle d'éligibilité dans `Account` (voir [Comptes](authentification.md#comptes)).

Exception : la recherche de la carte (`establishment`, sujet `search`) lit en SQL natif les tables que l'import du worker écrit (établissements, arrêts, adresses, données de référence). L'import reste leur seul port d'écriture. Ces lectures joignent plusieurs tables avec PostGIS et les trigrammes, ce qu'un port d'accès par table ne sait pas faire. Chaque requête de la recherche est couverte par un test d'intégration de son API.

`config` contient la configuration commune indépendante des domaines. Ne pas y déplacer un use case ni y composer les services d'un domaine.

## Dépendances à respecter

Chaque domaine respecte ces dépendances :

| Origine          | Dépendances internes autorisées dans son domaine |
| ---------------- | ------------------------------------------------ |
| `api`            | `api`, `application`, `domain`.                  |
| `application`    | `application`, `domain`.                         |
| `domain`         | `domain`, et le JDK.                             |
| `infrastructure` | `infrastructure`, `application`, `domain`.       |

Le flux d'une requête suit `api → application → domain`. Pour les sorties, `infrastructure` implémente les ports déclarés dans `application`. L'API peut aussi utiliser les types de son propre `domain` pour ses traductions. Un adaptateur de tâche dans `infrastructure` peut appeler un use case de son propre `application`.

`application` ne dépend ni de JPA ni de Spring Data. Il reçoit et rend des types de `domain` ou ses propres records, jamais une entité ni une `Page`.

Ces dépendances ne changent pas le rôle des composants :

- Un contrôleur ne doit pas effectuer de requête SQL, ouvrir une transaction métier ni appeler un SDK, c'est-à-dire la bibliothèque cliente d'un fournisseur.
- Un use case ne dépend pas d'un DTO HTTP ou d'un contrôleur.

Le modèle métier et l'entité JPA sont deux classes distinctes. Le modèle reste ainsi indépendant de la base. La conversion se fait à la frontière de persistance, voir [JPA et accès aux données](donnees.md#jpa-et-accès-aux-données).

### Ports et adaptateurs

Une sortie du programme passe par un port. Une sortie est tout ce qui quitte le processus : PostgreSQL, Redis, Google, Resend ou un autre service.

- Le port est une interface déclarée dans `application`. Son nom suit la [table de nommage](conventions.md#nommage-des-classes).
- L'adaptateur est une classe d'`infrastructure` qui implémente le port.
- Un calcul ou une valeur n'a pas de port : c'est du métier, dans `domain`.

### Use case et services

Un use case est une porte d'entrée : un contrôleur, une tâche du worker ou un autre domaine l'appelle. Il fait une seule action. Un contrôleur appelle un seul use case par route. La méthode publique d'un use case porte le verbe de son nom, par exemple `ResetPasswordUseCase.reset`.

Un use case n'appelle pas un autre use case de son domaine. Sinon, les contrôles d'entrée (droits, quotas, transaction) s'exécuteraient deux fois, et les use cases dépendraient les uns des autres. Ce qu'ils partagent va :

- dans `domain`, si c'est une règle qui décide avec les données déjà en main ;
- dans un service d'`application`, si le travail passe par un port (base, Redis, service extérieur).

Un service fait un seul travail, qu'on décrit en une phrase sans « et ». Il peut avoir plusieurs méthodes publiques pour ce travail. Son nom suit la [table de nommage](conventions.md#nommage-des-classes).

## Collaborations entre domaines

Une collaboration passe par une entrée publique du package `application` du domaine appelé. Elle exprime une opération ou un résultat stable et expose seulement les informations nécessaires.

- Le type Java doit être public et situé directement dans `application` : ces classes forment la porte du domaine. Ne placer une classe à cet endroit que si un autre domaine en a besoin. Ses éventuelles classes englobantes doivent aussi être publiques.
- Les sous-packages d'`application` restent internes, même si leurs classes sont publiques pour Spring ou pour un besoin local.
- Ne pas accéder aux contrôleurs, ports, entités ou adaptateurs d'un autre domaine, ni exposer ces types internes dans les signatures des entrées partagées.
- Pour leurs tests, les domaines s'appuient sur une porte de test, voir [portes de test](qualite.md#portes-de-test).
- Le domaine appelant reste responsable de son use case ; le domaine appelé conserve ses règles et ses données.

Les dépendances entre domaines doivent rester sans cycle. Si deux domaines se nécessitent mutuellement, clarifier la responsabilité du use case ou déplacer la coordination avant d'ajouter une exception. La communication par événements reste à trancher, voir [questions ouvertes](../questions-ouvertes.md#architecture-backend). Cette exigence rejoint les [frontières et cycles vérifiés par Spring Modulith](https://docs.spring.io/spring-modulith/reference/verification.html). Le dépôt les contrôle avec ArchUnit et n'ajoute pas de dépendance runtime Spring Modulith.

## Intégrations externes

Chaque client ou traitement technique appartient à `infrastructure` dans le domaine concerné. Les use cases coordonnent les opérations. Les contrôleurs et le domaine restent indépendants des clients externes.

| Intégration                                              | Rôle                                                                                                                                                                                                                        |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| API HTTP de Resend                                       | Envoi des emails, par appel direct sans SDK. L'API envoie les emails d'authentification (vérification d'adresse, récupération d'accès) ; le worker envoie les rappels d'événement.                                          |
| Spring Security OAuth2 Client / OpenID Connect           | API : inscription et connexion avec Google, puis ouverture d'une session locale. Voir le [guide authentification](authentification.md).                                                                                     |
| API Sirene de l'INSEE                                    | Worker : import quotidien des établissements, par appel direct avec une clé d'API (`SIRENE_API_KEY`). Voir le [guide worker](worker.md#import-des-établissements).                                                          |
| Fichiers ouverts de data.gouv.fr et adresse.data.gouv.fr | Worker : import quotidien des arrêts Divia (GTFS), des adresses (Base Adresse Nationale) et des organismes de formation, par téléchargement direct sans clé. Voir le [guide worker](worker.md#import-des-fichiers-ouverts). |

Pour chaque intégration :

- Fixer les timeouts de connexion et d'appel, les limites de taille et le nombre d'appels simultanés. Un appel externe ne doit pas bloquer indéfiniment un thread HTTP ou du worker.
- Distinguer les catégories d'erreur et les retries autorisés. Les effets incertains et la prévention des doublons suivent les [règles du worker](worker.md#retry-et-idempotence).
- Rendre le résultat de l'appel plutôt que lancer une exception de `application`. L'adaptateur rend un résultat que son type décrit (`GoogleSignInClient.Verification`, le booléen de `GoogleRepository.lockCurrentFlow`), et le use case lance son échec. Un fait sur la demande, sans décision d'un use case, est une exception de `domain` (`IdentifierTakenException`), que l'adaptateur peut lancer. Le contrôle est ArchUnit : aucune classe d'`infrastructure` ne construit une exception d'`application`, directement ou par une méthode statique qui la rend. Le filtre `AuthenticationSessionFilter` en attrape une, `AuthenticationFailedException`, sans la lancer : il est l'adaptateur HTTP de Spring Security et vide le contexte de sécurité quand le use case refuse la session. Cette dépendance reste permise.
- Valider les paramètres indispensables au démarrage lorsque l'intégration est activée. Configurer ses adresses, limites et identifiants sans appel implicite au fournisseur.
- Fournir à chaque processus les seuls secrets nécessaires à ses opérations, selon le [guide secrets](../secrets.md).

Le traitement des images contrôle les formats, le poids et les dimensions avant préparation, puis borne le temps et les ressources de l'encodage avant stockage. Les fichiers cartographiques statiques ne transitent pas par ce traitement Java. L'[architecture commune](../architecture.md#cartographie-et-fichiers) décrit leur diffusion.

Les règles des [comptes](authentification.md) et des [rappels](worker.md#rappels-dévénement) restent dans leurs guides respectifs.

## Contrôle de ces règles

ArchUnit contrôle les frontières du code de production. `BackendArchitectureTest` applique les règles ; `ArchitectureRulesTest` les vérifie sur des exemples autorisés et interdits. Le [guide qualité](qualite.md#frontières-darchitecture) détaille leur portée et leurs limites.

Les cycles sont interdits entre domaines comme entre couches d'un même domaine. Une dépendance acceptée par ArchUnit doit toujours correspondre à la responsabilité du composant. Les droits métier, les transactions et les comportements de retry nécessitent leurs propres vérifications.

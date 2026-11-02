# Conventions Java

Ce guide fixe les conventions de nommage, de construction des objets et de gestion des erreurs dans le code Java du backend. Il s'applique au code de production et aux tests. Le [guide d'architecture](architecture.md) définit les packages et leurs dépendances autorisées.

## Noms et emplacement

Utiliser des packages en minuscules sous `fr.emploigo`, avec un nom de domaine exprimant sa responsabilité. Ranger la classe dans la couche correspondant à son travail. Une classe Java de premier niveau possède son propre fichier ; le chemin suit le package.

### Langue

- Écrire en anglais les identifiants de code, les packages, les tables et les routes d'API.
- Écrire en français les textes affichés à l'utilisateur et les messages d'erreur.

Pourquoi : le code reste homogène et lisible, tandis que les jeunes lisent des textes en français.

### Nommage des classes

Nommer chaque classe selon son rôle. Les exemples suivants sont fictifs :

| Rôle                                 | Règle                                                           | Exemple                                      |
| ------------------------------------ | --------------------------------------------------------------- | -------------------------------------------- |
| Contrôleur HTTP                      | Suffixe `Controller`.                                           | `PublishEventController`                     |
| Données reçues ou renvoyées en HTTP  | Suffixe `Request` ou `Response`.                                | `PublishEventRequest`                        |
| Gestion des erreurs HTTP             | Suffixe `ExceptionHandler`.                                     | `EventExceptionHandler`                      |
| Use case                             | Verbe, objet, suffixe `UseCase`.                                | `PublishEventUseCase`                        |
| Entrée d'un use case                 | Verbe, objet, suffixe `Command`.                                | `PublishEventCommand`                        |
| Sortie d'un use case                 | Verbe, objet, suffixe `Result`.                                 | `PublishEventResult`                         |
| Classe métier                        | Nom du concept, sans suffixe.                                   | `Event`                                      |
| Service partagé d'`application`      | Nom de son seul travail, souvent en `…er` ou `…or`.             | `ReminderScheduler`                          |
| Port vers la base                    | Concept, suffixe `Repository`.                                  | `EventRepository`                            |
| Port vers un autre service extérieur | Ce qu'il fait : `…Sender`, `…Checker`, `…Limiter` ou `…Client`. | `ReminderSender`                             |
| Adaptateur d'un port                 | Technologie, puis nom du port.                                  | `JpaEventRepository`, `ResendReminderSender` |
| Entité JPA                           | Concept, suffixe `JpaEntity`.                                   | `EventJpaEntity`                             |
| Interface Spring Data                | `SpringData`, puis nom du port.                                 | `SpringDataEventRepository`                  |
| Erreur                               | Le fait, suffixe `Exception`.                                   | `EventAlreadyPublishedException`             |
| Événement                            | Fait au passé, suffixe `Event`.                                 | `AccountDeletedEvent`                        |
| Configuration Spring                 | Suffixe `Configuration` ou `Properties`.                        | `ReminderConfiguration`                      |
| Filtre HTTP                          | Suffixe `Filter`.                                               | `RequestLogFilter`                           |
| Sous-package                         | Un mot, en minuscules, au singulier.                            | `registration`                               |

Les autres noms suivent ces règles :

- Une méthode commence par un verbe.
- Une question oui/non commence par `is`, `has` ou `can`.
- Une porte de test se nomme domaine + `TestSupport`, par exemple `AuthenticationTestSupport`. Voir [portes de test](qualite.md#portes-de-test).
- Un faux objet de test commence par `Fake`, par exemple `FakeEventRepository`.

Un nom suffit à savoir le rôle d'une classe, et le contrôle d'architecture peut le vérifier. Ne pas employer ces mots dans un nom de classe : `Utils`, `Helper`, `Manager`, `Impl`, `Data`, `Info`, un préfixe `I` sur une interface, ou `Service` employé seul.

| Fichier             | Emplacement et nom                                                                                                             |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Test d'un composant | Même package que le composant, sous `src/test/java`. Suffixe `Test` pour Surefire, `IT` pour les tests d'intégration Failsafe. |
| Migration           | `V<numero>__<description>.sql` sous `src/main/resources/db/migration/`.                                                        |

Les classes servant d'exemples aux règles ArchUnit restent sous `architecture.fixtures`, hors de `fr.emploigo`. Cela évite leur découverte comme composants ou entités dans les tests Spring. Le [guide qualité](qualite.md#organisation-des-tests) précise l'exécution des tests.

## Objets et dépendances

Utiliser un `record` pour un DTO ou une valeur immuable lorsque ses propriétés conviennent à cet usage. Ne pas utiliser un `record` comme entité JPA. Déclarer explicitement les données nécessaires au contrat et éviter les objets génériques ou les maps de champs pour un échange stable.

Injecter les dépendances obligatoires par constructeur et les conserver dans des champs `final`. Ne pas utiliser d'injection de champs dans le code de production.

Limiter la visibilité au besoin réel. Une classe publique d'un sous-package reste interne à son domaine selon les règles d'architecture. Les entrées publiques directement dans `application` constituent une surface de collaboration : les nommer, documenter leurs conditions et relire leurs signatures comme un contrat.

Un service n'a une interface que pour une sortie du programme : elle est alors un port, voir [ports et adaptateurs](architecture.md#ports-et-adaptateurs). Un service interne reste une classe, sans paire interface et implémentation. Une interface scellée (`sealed`) qui décrit les résultats possibles d'une opération reste permise.

Ne pas ajouter de bibliothèque uniquement pour supprimer quelques lignes Java. Lombok n'est pas déclaré dans le backend.

## Règles et erreurs

Répartir les contrôles selon leur responsabilité :

| Contrôle                                                                             | Emplacement                  |
| ------------------------------------------------------------------------------------ | ---------------------------- |
| Forme des données reçues, champs requis et valeurs acceptables pour le contrat HTTP. | Entrée HTTP dans `api`.      |
| Règles métier qui doivent toujours être respectées.                                  | `domain`.                    |
| Droits de l'utilisateur sur la ressource et l'opération.                             | Use case dans `application`. |

Une contrainte de formulaire ou une annotation de validation ne remplace pas un contrôle métier.

Exprimer une erreur attendue avec un type et une signification précis. Traduire cette erreur en HTTP dans `api`, selon le [guide API](api.md). Ne pas faire dépendre une exception métier de `ResponseEntity`, d'un statut HTTP ou d'une classe du contrôleur.

Lorsqu'une exception survient :

- Ne pas masquer une panne en renvoyant une collection vide, une valeur métier par défaut ou un succès.
- Attraper l'exception lorsque le composant peut prendre une décision explicite : traduire, reprendre avec des limites ou produire un refus cohérent.
- Conserver la cause lorsqu'elle est utile au diagnostic. Pour les écritures, respecter les [règles transactionnelles](donnees.md#transactions).

## Configuration et logs

Garder les paramètres de déploiement dans la configuration et l'environnement, avec une valeur d'exemple documentée sans secret réel. Les [règles d'intégration](architecture.md#intégrations-externes) précisent la validation des paramètres à l'activation et les limites des appels externes.

Utiliser SLF4J avec des messages permettant de comprendre l'opération, son résultat et la cause d'un échec.

- Ajouter les identifiants techniques utiles pour relier les logs d'une même opération, lorsque leur collecte est justifiée.
- Ne jamais écrire dans les logs un mot de passe, une clé, un cookie, un identifiant de session, un token ni le contenu complet d'une requête. Toute donnée personnelle dans les logs nécessite une justification validée.
- Ne pas répéter la même pile d'exception à chaque couche. Les refus attendus et les erreurs de validation n'exigent pas une pile complète ; les erreurs techniques utiles au diagnostic doivent rester visibles.
- Ne pas divulguer de pile Java, de requête SQL ou d'identifiant de fournisseur dans une réponse HTTP.

## Faire évoluer les conventions

Appliquer les choix existants avant d'introduire un nouveau mécanisme. Toute exception durable aux frontières nécessite une justification dans la PR, une règle précise et des tests adaptés ; ne pas ignorer globalement un package pour faire passer le contrôle. Actualiser le guide concerné dans le même changement, selon les [règles de contribution](../../CONTRIBUTING.md).

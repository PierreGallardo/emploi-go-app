# Concevoir l'API HTTP

Ce guide fixe les règles de conception des routes, des données échangées, des erreurs et des contrôles d'accès de l'API.

Les contrôleurs reçoivent les demandes HTTP et préparent les réponses. Les use cases appliquent les règles métier, vérifient les droits sur les ressources et définissent les transactions. Les [frontières d'architecture](architecture.md) et le [guide des données](donnees.md) précisent cette répartition.

Autres guides liés :

- Les parcours de connexion, d'inscription et de récupération sont dans le [guide authentification](authentification.md).
- Les règles de sécurité sont dans le [guide sécurité](../securite.md).
- La génération du contrat et des types frontend est dans le [guide API commun](../api.md).

## Routes et DTO

### Définir les routes

- Exposer les routes applicatives sous `/api/`.
- Choisir une méthode HTTP adaptée à l'opération. Une lecture ne modifie pas une ressource.
- Limiter les paramètres de recherche, la pagination et la taille des entrées pour chaque fonctionnalité.

La liste à jour des routes est dans [`contracts/openapi.json`](../../contracts/openapi.json). En local, elle est aussi dans Swagger : `http://localhost:8080/swagger-ui/index.html`.

### Définir les données échangées

Les contrôleurs utilisent des DTO explicites. Un DTO est un objet qui définit les champs reçus ou renvoyés par l'API.

- Séparer les DTO d'entrée et de sortie lorsque leurs données diffèrent.
- Ne jamais recevoir ni sérialiser directement une entité JPA.

| Sujet                   | Règle                                                                                                                                                                   |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Validation des entrées  | Déclarer les contraintes avec Jakarta Validation et déclencher leur vérification dans le contrôleur.                                                                    |
| Règles métier et droits | Les vérifier dans les use cases et les modèles métier. La validation des champs ne suffit pas.                                                                          |
| Champs JSON             | Décrire les types, les valeurs permises et les champs obligatoires, facultatifs ou pouvant valoir `null`. Un champ absent et un champ présent à `null` sont différents. |
| Paramètres et réponses  | Décrire les paramètres, les statuts HTTP et les erreurs prévues dans le contrat. Vérifier les réponses réelles par des tests HTTP.                                      |

Les réponses ne révèlent aucun détail technique sensible. Les [règles d'erreur](#réponses-derreur) complètent cette exigence.

### Exemple fictif de réponse API

Une route de consultation peut renvoyer un événement :

```http
GET /api/events/42
```

```json
{
  "id": 42,
  "title": "Job dating"
}
```

Dans cet exemple, le contrôleur renvoie un DTO `EventResponse`, déclaré dans son propre fichier Java. Ce DTO expose seulement l'identifiant et le titre nécessaires à cette réponse. Les identifiants de code et les routes sont en anglais, selon les [conventions](conventions.md#langue).

### Position du jeune

Une URL est gardée par les proxys et par le diagnostic du navigateur : la position d'un jeune n'y figure jamais, ni ce qu'il tape pour la dire ([sécurité](../securite.md#données-personnelles)).

- Les lectures de la carte restent des `GET`, rejouées après une reconnexion. Le lieu cherché et le rayon passent dans le header `Search-Area` (`longitude,latitude,rayon`), le texte de « Où ? » dans `Place-Query`, encodé comme un composant d'URL.
- L'API ne les écrit ni en base ni dans les logs : elle ne journalise aucun header.
- PostgreSQL reçoit la position en paramètre de requête : en production, le rôle de l'API a `log_parameter_max_length = 0` et `log_parameter_max_length_on_error = 0`, pour qu'une requête lente ou en erreur soit journalisée sans ses valeurs ([droits de production](donnees.md#identités-et-droits-en-production)).
- Hors du dépôt, les logs de Cloudflare et du proxy ne gardent ni `Search-Area` ni `Place-Query` : vérifier leur configuration à chaque changement de journalisation.

## Autorisations et session

**Refuser tout accès qui n'est pas explicitement autorisé.** La configuration [Spring Security](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html) autorise les routes et méthodes nécessaires, puis termine par `denyAll`.

| Contrôle               | Responsabilité                                                                                                                                                          |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Route et méthode HTTP  | Autoriser explicitement l'opération dans Spring Security. Autoriser une lecture n'autorise pas les autres méthodes sur la même URL.                                     |
| Identité et ressource  | Vérifier la session et les droits dans le use case. Un utilisateur connecté n'a pas accès à toutes les données.                                                         |
| Processus worker       | Exclure les contrôleurs applicatifs du profil `worker`, avec `@ApiOnly`, et vérifier cette exclusion.                                                                   |
| Protection CSRF        | Conserver la protection CSRF de Spring Security, à double soumission (cookie et header), pour les actions du navigateur. Les attributs du cookie ne la remplacent pas.  |
| Sessions indisponibles | Refuser l'opération qui dépend de l'identité si la session PostgreSQL ne peut pas être lue ou vérifiée. Ne pas accorder un accès anonyme ou privilégié de remplacement. |

Le token CSRF est un cookie : le frontend le lit et le renvoie dans le header `X-CSRF-TOKEN` avec les actions concernées, sans session ([sécurité](../securite.md#sessions-et-réglages-initiaux)). `GET /api/auth/csrf` pose le cookie s'il manque. Ne pas désactiver CSRF globalement pour faciliter un appel. Voir la [protection CSRF et les applications SPA](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Les tests distinguent l'absence d'authentification, un droit refusé et un token CSRF invalide. Le [guide qualité](qualite.md) décrit les vérifications.

Les parcours de connexion, les sessions et la reconnexion automatique sont décrits dans le [guide authentification](authentification.md). Le [guide sécurité](../securite.md#identité-session-et-récupération) fixe les règles d'accès, les durées, les cookies et les révocations.

## Documentation et contrat généré

springdoc produit le contrat OpenAPI depuis les routes et DTO de l'API recompilée et démarrée. Le profil Spring contrôle son exposition :

| Profil                        | Document OpenAPI               | Interface Swagger UI      |
| ----------------------------- | ------------------------------ | ------------------------- |
| Aucun (configuration de base) | Désactivé.                     | Désactivée.               |
| `local`, sans `worker`        | `/v3/api-docs`.                | `/swagger-ui/index.html`. |
| `contract`, sans `worker`     | Activé pour le contrôle isolé. | Désactivée.               |
| `worker`, y compris en local  | Désactivé.                     | Désactivée.               |

Spring Security n'autorise l'accès public à ces routes que pour les profils `local`, `contract` et `test`, et jamais avec `worker`. Aucun autre profil ne les expose.

Le serveur déclaré dans OpenAPI est relatif (`/`) pour conserver le même contrat entre environnements. Ne pas inscrire d'adresse de test ou de production dans les DTO ou le contrat commun.

Le [guide API commun](../api.md) définit les commandes de synchronisation, les fichiers générés à inclure avec une modification et le contrôle isolé. Les tests HTTP vérifient les réponses JSON, les statuts et les droits. Les types TypeScript ne valident pas les réponses réelles.

## Exposition et erreurs

### Points de gestion

- La santé Actuator est consultable sans détail interne.
- Les points de gestion sont liés à `127.0.0.1`. Les métriques Prometheus restent protégées.
- L'accès d'un collecteur exige une configuration explicite d'exploitation. Ne pas ouvrir l'ensemble d'Actuator au public.

Voir les règles de [supervision et d'accès](../exploitation.md#supervision-et-logs).

### Réponses d'erreur

Une erreur est renvoyée sous la forme `{ code, message }` :

- `code` est un identifiant stable, lisible par le frontend.
- `message` est un texte en français affiché à l'utilisateur.
- Aucun détail technique sensible n'apparaît (pile Java, requête SQL, identifiant de fournisseur).

Définir les erreurs avec chaque fonctionnalité et les maintenir dans OpenAPI et dans les tests HTTP.

| Code HTTP | Sens                                                                                                                                                                                                                                                                       |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 400       | Entrée invalide : champ manquant, mal formé ou hors des valeurs permises.                                                                                                                                                                                                  |
| 401       | Identité absente ou refusée : pas de session valide, identifiants incorrects.                                                                                                                                                                                              |
| 403       | Opération refusée : droit insuffisant ou token CSRF manquant ou invalide.                                                                                                                                                                                                  |
| 404       | Ressource absente, ou que l'utilisateur n'a pas le droit de connaître.                                                                                                                                                                                                     |
| 409       | Conflit métier : l'état actuel de la ressource empêche l'opération.                                                                                                                                                                                                        |
| 410       | Lien expiré, utilisé ou remplacé.                                                                                                                                                                                                                                          |
| 429       | Quota dépassé (`RATE_LIMITED`), ou email déjà envoyé dans le délai entre deux envois (`MAIL_RECENTLY_SENT`). La réponse indique le délai d'attente avec le header `Retry-After`, déclaré dans le contrat.                                                                  |
| 503       | Dépendance ou protection indisponible (base de données, Redis). L'opération est refusée ; `Retry-After` indique quand réessayer pour les compteurs et le plafond de calculs. Le plafond de calculs de mot de passe, saturé, répond `BUSY`, une panne répond `UNAVAILABLE`. |

Réserver 429 à un quota réellement dépassé. Une protection indisponible relève de 503.

Une route déclare ses réponses avec `@ApiResponse` sur sa méthode, jamais sur la classe du contrôleur : springdoc fait passer l'annotation de classe après celle de la méthode et la remplace entière à code égal, et une réponse de classe s'applique à toutes les routes, y compris à celles qui ne peuvent pas la renvoyer. `ApiContractRulesTest` fixe les statuts de chaque route du contrat et refuse une route qu'il ne liste pas ; une règle d'architecture (`ArchitectureRules.routesDeclareTheirOwnResponses`) refuse toute `@ApiResponse` sur une classe `@RestController`.

Une route déclare son 429 sur elle seule. Le contrat ajoute `Retry-After`, obligatoire, à chaque 429 de `/api/auth` (`AuthenticationOpenApiConfiguration`) : le frontend en tire l'heure à laquelle le jeune peut réessayer, et une nouvelle route n'a rien à recopier. Il ajoute aussi `Retry-After`, facultatif, à chaque 503 : les compteurs et le plafond de calculs envoient `5` (une indication, pas un engagement : voir [panne des compteurs](donnees.md#panne-des-compteurs)), une panne de base de données ne l'envoie pas, car sa durée est inconnue et une valeur fixe ferait réessayer tous les clients pendant la panne (RFC 9110 §10.2.3 et §15.6.4 : header facultatif). `ApiContractRulesTest` vérifie le contrat enregistré.

Les réponses d'authentification et leurs refus utilisent `Cache-Control: no-store`.

### Cache HTTP

Les réponses liées à une session ou à un compte restent hors du cache partagé. Une exception de cache concerne seulement une lecture publique, identique pour tous les visiteurs et sans donnée personnelle. Les autres conditions sont dans l'[architecture commune](../architecture.md#caches-et-limitation-des-actions).

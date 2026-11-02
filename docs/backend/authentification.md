# Authentification

Ce guide décrit les parcours d'authentification du jeune et leurs règles. Il explique le quoi et le pourquoi ; le comment est dans le code.

- Les durées, les cookies, la politique de mot de passe et les révocations sont dans le [guide sécurité](../securite.md#identité-session-et-récupération).
- Les routes et leurs champs sont dans le [contrat OpenAPI](../../contracts/openapi.json). Les règles générales de l'API sont dans le [guide API](api.md).

Dans ce guide, « jeune » désigne l'utilisateur final.

## Inscription et confirmation de l'email

Le jeune s'inscrit en trois étapes : un email et un mot de passe, le code reçu, puis son pseudo. Le mot de passe suit la [politique commune](../securite.md#identifiants-et-mots-de-passe).

- Une inscription en attente n'ouvre aucun accès et ne garde aucun pseudo.
- Le code validé crée le compte, sans pseudo, et ouvre sa session selon la case « Rester connecté » du formulaire.
- `POST /api/auth/username` donne son pseudo au compte de la session qui n'en a pas. Un pseudo pris, même par une demande concurrente, reçoit 409 `USERNAME_UNAVAILABLE` : la contrainte d'unicité de `auth_accounts.username` arbitre, et plusieurs comptes peuvent rester sans pseudo. Un compte qui a déjà son pseudo reçoit 409 `USERNAME_ALREADY_CHOSEN`. Un pseudo hors [règle](../perimetre-v1.md#pseudo-et-avatar) reçoit 422 `INVALID_USERNAME`, comme à l'inscription Google. La session est rouverte comme elle était gardée.
- Un compte sans pseudo, parce que l'onglet a été fermé avant la dernière étape, se connecte avec son email et choisit son pseudo avant d'entrer dans l'application. L'écran est le même que celui de l'inscription Google.
- Une adresse déjà utilisée par un compte ne crée jamais de second compte.

Le jeune reçoit un email avec un code à 6 chiffres, sans lien. Il le tape dans le navigateur où il s'est inscrit, et il y est connecté.

- Le code est valable 15 minutes et ne sert qu'une fois. Un nouveau code remplace le précédent.
- Chaque code accepte 5 essais. Au-delà, il est invalidé et le jeune en demande un nouveau.
- Une inscription accepte 15 essais ratés au total, tous codes confondus : un nouveau code ne remet pas ce total à zéro. À chaque 15e échec, plus aucun nouveau code ni aucun essai n'est possible pendant 1 heure. La réponse est un 429 avec l'heure de fin, puis le jeune redemande un code.
- Un code faux, expiré, remplacé ou épuisé est refusé avec un message qui propose l'action : réessayer ou demander un nouveau code.
- Le code est calculé à partir de la clé du serveur et de l'opération d'envoi. La base ne garde que son hash avec clé, jamais le code. Il n'apparaît ni dans les logs ni dans les métriques.
- Les essais de code comptent aussi dans les quotas d'authentification (action `confirmation`, par adresse IP et par email).

**Un code ne confirme que l'inscription demandée depuis ce navigateur.** L'API garde l'inscription en cours dans la session du navigateur, avec une clé secrète qui ne quitte jamais le serveur. L'inscription acceptée donne à la page un identifiant non secret (en-tête `Registration-Id`). Le renvoi et la confirmation ne portent que cet identifiant, ni l'email ni la clé : l'API les lit dans la session. Sans inscription en cours pour cet identifiant, la réponse est 410 `NO_PENDING_REGISTRATION`. La dernière demande pour une adresse remplace l'inscription précédente. L'écran de code ne révèle rien sur l'adresse : il répond de la même façon pour une inscription en attente, un compte ou une adresse inconnue. Pour un email qui a un compte, l'inscription n'enregistre rien et le titulaire reçoit l'email « Tu as déjà un compte », adapté à son mode de connexion. Les règles, les limites et leur justification sont dans le [guide sécurité](../securite.md#confirmation-dinscription).

Pour reprendre une inscription non confirmée, par exemple si le jeune a fermé l'onglet, il refait l'inscription avec le même email : un nouveau code part. Un navigateur ne garde que sa dernière inscription, dans la session partagée par ses onglets : une nouvelle inscription, même pour la même adresse, rend l'écran de code des autres onglets inutilisable. L'écran après l'envoi du formulaire est le même dans tous les cas : seul l'email change (un code, ou « Tu as déjà un compte » si un compte utilise l'adresse). Une inscription refusée parce qu'un email vient de partir (429 `MAIL_RECENTLY_SENT`) ne garde rien : le formulaire reste affiché, avec l'attente, et le code de cet email se saisit sur la page qui l'a demandé.

La connexion ne reprend pas une inscription : le mot de passe d'une inscription en attente reçoit le refus habituel (401), sans code ni email. Sinon, la réponse distinguerait une adresse libre d'une adresse qui a un compte.

Pourquoi : un tiers peut inscrire une adresse qui n'est pas la sienne. Le code arrive chez le vrai titulaire, et la liaison au navigateur empêche que le titulaire confirme à son insu les identifiants du tiers.

L'API envoie elle-même les emails d'authentification (confirmation, récupération, notifications), pendant la demande du jeune. La réponse indique si le fournisseur a accepté l'email. Cette acceptation ne garantit pas la réception.

Pourquoi : le jeune sait tout de suite si l'email est parti ou s'il doit réessayer. Le [guide données](donnees.md#erreurs-et-appels-externes) décrit comment cet appel reste hors transaction et sans lock.

L'inscription, la récupération et Google envoient par le même service, sans journal d'envoi. Le plafond d'appels simultanés au fournisseur est commun aux trois flux.

Si la réponse se perd, le jeune peut renvoyer la demande. Le serveur la traite comme une nouvelle demande, sans identifiant : une demande répétée dans le délai entre deux envois reçoit 429 `MAIL_RECENTLY_SENT`, avec le temps à attendre, et aucun email ne part. Passé ce délai, elle envoie un nouveau code ou un nouveau lien, et l'ancien est remplacé.

L'API décide l'email dans une transaction courte, puis l'envoie après le commit, sans transaction, lock ni connexion à la base. Un code ou un lien remplacé, utilisé ou expiré au moment de l'envoi n'est pas envoyé. Un ancien code est refusé (410) dès que le renvoi a enregistré le nouveau, sans attendre la fin de l'envoi.

- Le délai entre deux envois est posé avant l'appel : une demande qui arrive pendant l'envoi reçoit 429 `MAIL_RECENTLY_SENT`, comme celle qui arrive après lui dans le délai.
- Si le fournisseur n'accepte pas l'email, le jeune reçoit une indisponibilité (503). Il peut redemander une fois le délai écoulé.
- Si l'API s'arrête pendant l'appel, l'email peut être perdu. Le jeune redemande un code après le délai.
- Dans un cas rare, un email peut arriver en double.

Voir le [guide données](donnees.md#erreurs-et-appels-externes).

Le délai entre deux envois est compté par adresse, avant toute écriture. Il ne dépend ni du navigateur, ni du mot de passe. Il dure 60 secondes. Une inscription, un renvoi ou une demande de récupération acceptés l'annoncent dans `Retry-After` (202), pour que l'écran compte les secondes avant le prochain renvoi. Pour la récupération, l'attente est la même avec ou sans compte.

- Une demande répétée dans le délai ne crée ni second compte, ni second email, ni second code. Elle ne modifie ni l'inscription, ni le compte, ni le code en cours.
- Le refus est le même pour une adresse neuve et pour une adresse déjà utilisée, et quel que soit le résultat du premier envoi. Sinon, la réponse révélerait si l'adresse existe.
- Le refus pour un délai répond 429 `MAIL_RECENTLY_SENT` ; le refus pour une limite de volume (adresse ou identité) répond 429 `RATE_LIMITED`. Le client sait ainsi s'il doit attendre un email déjà parti ou un quota atteint.
- Un renvoi de code répété dans le délai n'émet pas de code : les compteurs d'essais de confirmation ne repartent pas de zéro.

Pourquoi sans identifiant de demande : une demande répétée n'a aucun effet fâcheux. Le délai entre deux envois, les contraintes d'unicité de la base et les statuts des codes et des liens suffisent. Un identifiant fourni par le client obligerait à garder un reçu par demande, et son hash.

### Demande après le délai

Une demande répétée après le délai entre deux envois est une nouvelle demande. Elle envoie un nouveau code ou un nouveau lien : le jeune qui n'a pas reçu le premier email reçoit le suivant, et l'ancien code ou l'ancien lien est remplacé ou expiré.

## Connexion avec email ou pseudo

Le jeune se connecte avec son email ou son pseudo, et son mot de passe.

- Seuls les comptes jeunes actifs avec un email confirmé peuvent se connecter.
- Toutes les autres situations reçoivent la même erreur 401. Le message ne révèle pas si le compte existe.
- La vérification coûte le même calcul de hash dans ces situations : le mot de passe d'un compte inconnu, non confirmé, sans mot de passe ou non autorisé est comparé à un hash factice. La durée de la réponse ne révèle donc pas non plus l'état du compte.
- La connexion ouvre une nouvelle session et, seulement si la case « Rester connecté » est cochée, une reconnexion automatique (création d'une nouvelle session sans afficher l'écran de connexion ; voir [Sessions](#sessions-et-reconnexion-automatique) et le [guide sécurité](../securite.md#sessions-et-réglages-initiaux)).
- Les actions qui modifient l'état exigent le token CSRF : le header `X-CSRF-TOKEN` porte la valeur du cookie. Le token est renouvelé à la connexion et à la reconnexion. La déconnexion efface son cookie : le frontend en demande un nouveau (`GET /api/auth/csrf`) avant l'écriture suivante.

## Connexion et inscription avec Google

Google est une autre façon d'accéder à un compte Emploi Go. Il ne donne jamais de droits administrateur.

### Identité

- Le compte est retrouvé par l'identifiant Google `sub`. L'email ne sert pas de clé de liaison.
- Si l'adresse Google change, la liaison reste valable.
- Les tokens Google restent côté serveur. Ils ne deviennent pas des moyens d'accès à l'API.

### Règles selon l'adresse

| Adresse          | Vérification exigée                                                                      |
| ---------------- | ---------------------------------------------------------------------------------------- |
| Gmail            | Google confirme l'adresse. Aucune confirmation locale supplémentaire.                    |
| Google Workspace | Google confirme l'adresse et fournit un domaine d'organisation (`hd`) validé.            |
| Autre adresse    | Confirmation locale de l'adresse par email, même si Google annonce qu'elle est vérifiée. |

Le domaine de l'email seul ne suffit pas à prouver une appartenance à Workspace. Ces règles suivent la [vérification Google du token](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).

### Association à un compte existant

**Une adresse identique ne fusionne jamais deux comptes automatiquement.** Sinon, quelqu'un qui contrôle une adresse Google pourrait prendre un compte local.

L'association exige :

1. Une connexion locale récente (moins de cinq minutes) au compte existant.
2. Une confirmation distincte de l'association.

Une démarche Google expire après 10 minutes. Une demande d'email Google répétée dans le délai entre deux envois répond 429 `MAIL_RECENTLY_SENT`, comme l'inscription (voir [Demande après le délai](#demande-après-le-délai)).

L'ajout de Google conserve les sessions existantes et notifie le titulaire. Les [règles d'accès](../securite.md#google) complètent ces points.

### Panne ou refus de Google

Une panne ou une lenteur de Google donne `UNAVAILABLE`, que ce soit pendant l'échange du code ou pendant la récupération de ses clés de vérification (erreur réseau, timeout dépassé, erreur HTTP, réponse qui n'est pas un ensemble de clés). Le jeune peut réessayer. Un token réellement invalide (signature, émetteur, audience, nonce, dates, clé inconnue après rafraîchissement) donne `PROVIDER_REJECTED`. Les [timeouts](developpement.md#timeouts-des-fournisseurs-externes) sont configurables.

### Flux

- Le flux est OpenID Connect, avec PKCE.
- L'application demande seulement les scopes `openid` et `email`.
- Une demande expirée, remplacée ou déjà utilisée impose un nouveau départ.
- Un ancien onglet ne peut pas terminer un nouveau parcours.

Pour configurer Google en local, voir [Tester Google](developpement.md#tester-google).

## Mot de passe oublié

Le jeune demande un lien depuis la page « Mot de passe oublié ».

- La réponse est identique pour toutes les adresses, connues ou non.
- La demande ne modifie aucun accès existant.
- Le lien est à usage unique et expire (voir les [durées](../securite.md#sessions-et-réglages-initiaux)).
- Le lien est validé avant le calcul du hash. Un lien invalide, expiré ou remplacé ne coûte aucun calcul.
- Le changement consomme le lien et remplace le mot de passe. Il incrémente la version des identifiants, que le filtre de session, la reprise et les parcours Google revérifient : toutes les sessions et reconnexions du compte sont refusées dès la requête suivante. Leurs lignes ne sont pas supprimées : elles expirent (30 minutes d'inactivité pour une session, 30 jours pour une reconnexion) puis la purge les retire.
- Le changement n'ouvre aucune session. Le jeune se connecte ensuite normalement.
- Le changement remet à zéro les échecs et le délai de connexion du compte, pour son email et pour son pseudo : le jeune se connecte aussitôt avec son nouveau mot de passe. Voir [Limitation des tentatives](#limitation-des-tentatives).
- Le jeune reçoit un email de notification, envoyé une seule fois après le changement. Si cet envoi échoue, le mot de passe reste changé et la notification n'est pas reprise.

Pourquoi révoquer tous les accès : la récupération peut suivre une compromission du compte.

Une demande répétée avec un lien déjà utilisé répond 410 `USED_LINK` : le mot de passe ne change pas, et aucun email ne part. Le jeune se connecte avec son nouveau mot de passe, ou demande un nouveau lien.

Un compte Google seul n'a pas de mot de passe Emploi Go. L'email rappelle alors comment se connecter avec Google. Voir les [règles de récupération](../securite.md#liens-de-vérification-et-récupération).

## Comptes

- `AccountRepository` est le seul accès aux comptes : connexion, reconnexion, récupération du mot de passe, Google et inscription passent par lui. Seuls les fichiers du package `auth/infrastructure/account` nomment la table `auth_accounts` ; un test lit les sources et échoue si un autre fichier la nomme (voir [qualité](qualite.md#frontières-darchitecture)).
- Créer un compte exige d'accepter les conditions d'utilisation et la politique de confidentialité. L'inscription locale et l'inscription Google refusent une demande sans cette acceptation (400 `INVALID_INPUT`). La création du compte enregistre la date de cette acceptation. Un compte de développement n'en a pas ; les comptes créés avant cette règle sont datés de sa mise en place.
- `Account.mayAuthenticate()` est la seule règle « peut s'authentifier » (compte activé, adresse vérifiée, rôle `JEUNE`). Aucun SQL ne la répète : un parcours qui a besoin de l'état du compte lit le compte, puis applique cette règle.
- Les lectures sont des requêtes, jamais des entités en cache : un compte relu après un lock est la ligne validée. Ces méthodes exigent une transaction ouverte (sinon `IllegalTransactionStateException`). Deux modes de lock de ligne existent : `lockById` (`FOR NO KEY UPDATE`), pour la création et la rotation d'une session, et `lockByIdForUpdate` (`FOR UPDATE`), pour la récupération et Google, qui bloquent aussi l'insertion de lignes qui référencent le compte.
- Ordre des locks : un parcours Google prend d'abord le lock du navigateur (`lockCurrentFlow`), puis la ligne du compte, puis les lignes qui en dépendent (reconnexions, sessions, liens). Un nouveau parcours le respecte pour ne pas créer d'interblocage.
- La réinitialisation du mot de passe verrouille la ligne du compte, pour la vérification de version. La consommation du lien est un geste unique : elle ne passe le lien à utilisé que s'il est encore en attente. Un lien remplacé par une nouvelle demande entre-temps est refusé, et le mot de passe reste inchangé. La demande d'un lien ne prend aucun lock.
- La confirmation d'un lien d'email Google est un geste unique : la mise à jour ne passe le lien à utilisé que s'il est encore en attente et non expiré. Deux confirmations simultanées : une seule réussit, l'autre reçoit le refus du lien déjà utilisé.
- L'inscription locale verrouille la ligne de l'inscription (`FOR UPDATE`) : elle sérialise ses confirmations, ses renvois et les nouvelles demandes pour la même adresse. Une seule inscription reste en attente par adresse.
- L'inscription Google ne prend aucun lock de compte. Les contraintes d'unicité arbitrent : une adresse, un pseudo ou une identité Google déjà pris en parallèle reçoit le refus prévu (409).
- Avant de créer le compte, l'inscription Google clôt l'inscription locale en attente de la même adresse. Elle prend la ligne de l'inscription avant l'adresse unique, dans le même ordre qu'une confirmation locale : les deux ne peuvent pas s'interbloquer. Une confirmation locale en cours se termine d'abord ; si elle crée le compte, Google trouve l'adresse prise (409). Sinon, la page de l'inscription close reçoit les réponses d'un email avec compte (voir le [guide sécurité](../securite.md#confirmation-dinscription)).
- Une seule vérification d'email Google reste en attente par adresse et identité. Une demande simultanée qui la remplacerait reçoit 503 et peut réessayer.
- Le délai entre deux envois, posé avant la transaction, rend rares deux demandes simultanées pour une même adresse. Si cela arrive quand même, la seconde reçoit une 503 et peut réessayer.

## Sessions et reconnexion automatique

Deux accès distincts existent pour le jeune :

- La session en cours, stockée côté serveur.
- La reconnexion automatique, qui recrée une session sans afficher l'écran de connexion.

Règles :

- Avant de recréer une session, le serveur vérifie la reconnexion, l'état du compte et ses droits.
- Chaque reconnexion réussie renouvelle le secret et l'échéance de la reconnexion. Les durées, l'absence de durée maximale et la réutilisation d'un ancien secret sont expliquées dans le [guide sécurité](../securite.md#sessions-et-réglages-initiaux).
- Un ancien secret rejoué après la tolérance révoque la reconnexion et la session de sa famille. Il reçoit le même refus que tout autre cookie invalide, et le serveur trace un avertissement sans secret ni identifiant et compte la révocation (`auth.reconnection.reuse.revoked`). Rejoué dans la tolérance, le secret précédent est servi comme le secret courant : le serveur renouvelle le secret, la session est recréée et l'ancien secret courant devient le précédent. Seul le secret précédent est reconnu : un secret plus ancien reçoit un simple refus, sans révocation, alors qu'Auth0 y déclenche la détection de réutilisation. Une reprise abandonnée par le client mais encore en attente d'un lock côté serveur peut tourner le secret après une relance qui a déjà réussi : le navigateur garde alors un secret devenu précédent, et sa reprise suivante hors tolérance révoque la famille. Ce cas suppose plus de 15 s d'attente côté serveur.
- Quand la réponse d'une reconnexion se perd, le navigateur garde l'ancien secret. Il est servi s'il revient dans la tolérance ; passé ce délai, le serveur le refuse et le jeune se reconnecte avec son mot de passe, le rejeu tardif a déjà révoqué la famille.
- Le format `<identifiant>.<secret>` (80 caractères : un UUID en minuscules, un point, 43 caractères de base 64 pour URL) est lu en un seul endroit, `AuthenticationToken`, pour le cookie de reconnexion comme pour les liens d'inscription, de récupération du mot de passe et de confirmation d'email Google. Il est vérifié avant tout accès à la base : un cookie ou un lien mal formé est refusé sans emprunter de connexion du pool, après les contrôles qui le précédaient (quota, saisie). Un cookie bien formé mais inconnu, ou dont le secret est faux, ne coûte qu'une lecture, sans transaction ni lock.
- Le secret de la reconnexion est comparé avant toute attente de lock. Un cookie au secret faux, révoqué ou expiré est refusé sans retarder la connexion ni la réinitialisation du mot de passe du jeune concerné. Il est comparé de nouveau une fois les locks pris, car une reconnexion, une révocation ou une réinitialisation du mot de passe a pu être validée entre-temps. Le statut et le body du refus sont les mêmes quelle que soit la cause.
- La déconnexion (`POST /api/auth/logout`) est le `logout()` de Spring Security : elle répond 204, révoque la session et la reconnexion du navigateur concerné et efface les cookies de session, de reconnexion et l'indicateur. Si la base est indisponible pendant la révocation, elle répond 503 sans rien effacer, pour que le jeune puisse réessayer : le cookie de reconnexion est la seule prise pour révoquer sa famille. Si la base tombe après la révocation, pendant la suppression de la session, la réponse est aussi un 503, mais les accès sont déjà révoqués et les cookies effacés. Un cookie de reconnexion mal formé, inconnu, ou qui ne correspond ni au secret courant ni au précédent ne révoque rien, n'attend aucun lock et n'ouvre aucune transaction.
- La reconnexion automatique n'a pas de quota par adresse IP, mais une limite par reconnexion : voir [Limitation des tentatives](#limitation-des-tentatives).
- Une lecture de données ne déclenche pas de reconnexion automatique.
- Les administrateurs n'ont pas de reconnexion automatique.
- Le cookie de session est `__Host-EMPLOIGO_SESSION` et celui de reconnexion `__Host-EMPLOIGO_RECONNECTION`, en `Path=/` : le second accompagne donc toutes les requêtes du site. Le token CSRF est dans `__Host-XSRF-TOKEN`, et `__Host-EMPLOIGO_RECONNECTABLE` indique qu'une reconnexion est possible : le frontend ne la demande que s'il est présent. Avec le profil `local`, les noms n'ont pas de préfixe. Les attributs suivent les [règles des cookies](../securite.md#cookies-et-sous-domaines).

**Une session recréée automatiquement n'est pas une authentification récente.** Pour une action sensible (changer l'email, le mot de passe ou une méthode de connexion), le serveur demande une nouvelle authentification explicite.

Les durées, les révocations et la [règle `__Host-`](../securite.md#cookies-et-sous-domaines) sont définies dans le [guide sécurité](../securite.md#sessions-et-réglages-initiaux). Le stockage des sessions est décrit dans le [guide des données](donnees.md#sessions).

## Limitation des tentatives

Les actions d'authentification sont limitées pour freiner les abus : connexion, reprise de la reconnexion, inscription, demandes d'email, confirmation et récupération.

- Les quotas sont configurables, action par action : limite par adresse, limite par identité, fenêtre, et, pour les actions qui envoient un email, limite par préfixe /48 IPv6, délai entre deux envois et seuil d'alerte global. La connexion n'a pas de limite par identité : elle a un délai progressif après des échecs (voir plus bas). La reconnexion automatique n'a pas de limite par adresse : elle est limitée par reconnexion (voir plus bas). Les propriétés, leurs bornes et leurs valeurs par défaut sont dans le [guide de développement](developpement.md#quota-et-timeouts-redis).
- La limite principale porte sur l'adresse IP. Une adresse IPv4 compte en entier ; une adresse IPv6 compte par préfixe /60, la délégation d'un abonné chez un opérateur de réseau fixe : quiconque détient un préfixe change d'adresse à volonté à l'intérieur. Les accès mobiles reçoivent un /64 chacun, pris dans un pool : plusieurs abonnés mobiles peuvent partager un /60, donc un compteur. Le nombre de clés d'adresse de Redis d'un /48 est ainsi borné à 4 096 par action, au prix d'un compteur que partagent les /64 d'un même /60, abonnés mobiles compris (voir [Forme des quotas](donnees.md#forme-des-quotas)). Une adresse IPv4 écrite en IPv6 (`::ffff:203.0.113.7`) compte comme l'IPv4.
- Pour la connexion, seuls les échecs font avancer le compteur de l'identité, et un échec répété est suivi d'un délai progressif plutôt que d'un blocage :
  - les 4 premiers échecs d'une identité ne sont suivis d'aucun délai. Le 5e, puis chaque échec suivant, est suivi d'un refus de l'identité (429, `Retry-After`) de 5 s, 10 s, 20 s, 40 s, 80 s, 160 s, puis 5 minutes au plus. Les valeurs sont réglables ;
  - pendant le délai, toute tentative sur cette identité reçoit un 429, même avec le bon mot de passe. Elle ne vérifie aucun mot de passe, ne compte ni dans l'identité ni dans l'adresse, et n'allonge pas le délai : un tiers ne peut donc pas le porter au-delà du plafond ;
  - le titulaire se connecte dès la fin du délai. Une connexion réussie remet à zéro les échecs de l'identité, mais pas le compteur de l'adresse : un succès ne masque pas les échecs d'un attaquant qui partage l'adresse ;
  - un tiers peut renouveler le délai à chaque expiration : le titulaire reprend la main en récupérant son mot de passe (voir la remise à zéro par la récupération, plus bas) ;
  - les échecs sont oubliés 30 minutes après le dernier, ou tout de suite par une réussite. Un échec compté au même moment qu'une réussite peut être effacé avec elle : le risque est accepté. Cette remise à zéro est observable : un tiers qui tente un échec sur un identifiant peut déduire d'un 401 là où il attendait un 429 qu'une connexion réussie a eu lieu entre-temps. Le risque est accepté, car il demande une surveillance continue et ne révèle rien de plus que la connexion d'un compte déjà connu.
- L'identité de la connexion est l'identifiant tel qu'il a été tapé, normalisé comme le serait la recherche du compte (casse et espaces pour un email comme pour un pseudo), et non le compte qu'il désigne. L'email et le pseudo d'un même compte ont donc deux compteurs : une réponse ne permet jamais de relier l'un à l'autre. Le contrôle passe avant toute lecture de la base. Un identifiant qu'aucun compte ne peut porter (pseudo hors règle, email mal formé) a un compteur par texte tapé, jamais un compteur commun à tous les clients.
- Un échec est compté avant la vérification du mot de passe, dans la même opération atomique que le contrôle du délai : des requêtes parallèles ne peuvent pas toutes passer avant qu'un échec soit enregistré, et ne dépassent donc pas le nombre d'échecs libres. Une tentative interrompue (plafond de calculs atteint, base indisponible, erreur) garde son échec : rien n'est rendu.
- Le mot de passe d'une inscription en attente est un échec comme un autre : la connexion ne connaît que les comptes.
- Une récupération de mot de passe réussie remet à zéro les échecs et le délai de la connexion du compte, sous ses deux identités : son email et son pseudo, tels que le compte les stocke (ce sont les identités que la connexion compte quand elle trouve le compte). Le compteur de l'adresse n'est pas touché. Pourquoi : un tiers peut renouveler le délai à chaque expiration, et le titulaire n'a alors que la récupération pour reprendre la main, qui prouve qu'il possède l'email. Elle lève le délai en cours et fait repartir la progression de zéro (4 échecs sans délai, le 5e suivi de 5 s) ; elle ne garantit pas l'accès si le tiers continue à échouer : le titulaire se connecte tout de suite, tant que le tiers n'a pas refait 5 échecs.
  - La remise à zéro a lieu après la validation du changement, hors de toute transaction, et avant l'envoi de la notification. Une tentative déjà comptée à ce moment voit son échec effacé avec les autres : c'est la même perte acceptée qu'une réussite simultanée.
  - Une demande répétée avec un lien déjà consommé répond 410 `USED_LINK` et ne remet rien à zéro : une ancienne demande ne sert pas à effacer le délai d'un compte.
  - Comme pour un succès de connexion, un tiers qui voit son délai disparaître peut en déduire qu'une remise à zéro a eu lieu : le risque est accepté, il ne révèle que ce que la récupération annonce déjà par un email au titulaire.
  - Les deux identités sont remises à zéro l'une après l'autre : l'échec de la première n'empêche pas la seconde.
- Si les compteurs ne peuvent pas enregistrer le résultat (remise à zéro après un succès ou après une récupération), la réponse ne change pas : une récupération réussit et notifie quand même. Le fait est signalé par un avertissement (au plus un par minute) et la métrique `auth_rate_limit_login_unrecorded_total` (étiquette `outcome` : `succeeded` ou `reset`, une fois par identité pour `reset`). Les échecs et le délai d'une identité dont la remise à zéro a été perdue restent en vigueur jusqu'à leur terme. La confirmation de la récupération est une action fermée : une panne des compteurs qui précède son contrôle la refuse déjà (503) ; la remise à zéro n'est perdue que si la panne survient entre ce contrôle et la remise à zéro elle-même.
- Pour les actions qui envoient un email, un seuil d'alerte global ne refuse rien : au-delà, la demande est acceptée, avec un avertissement et la métrique `auth_rate_limit_global_exceeded_total` (voir [Forme des quotas](donnees.md#forme-des-quotas)). Un seuil qui refuserait tout le monde permettrait à un client de bloquer toutes les inscriptions. Les limites par adresse, par préfixe /48 et par identité et le délai entre deux envois restent appliqués.
- Les tentatives refusées comptent aussi dans l'adresse. Elles ne comptent dans l'identité que si l'adresse est dans sa limite et, pour une action d'email, si le délai entre deux envois est écoulé : une demande refusée par ce délai n'envoie rien et ne retire rien aux envois de l'identité. Une demande refusée par l'adresse ne crée aucun compteur d'identité, donc un client qui varie les identités ne remplit pas la mémoire de Redis. L'attente annoncée est alors celle de l'adresse. Pour la connexion, le compteur de l'adresse compte toutes les tentatives admises, réussies, échouées ou interrompues ; une tentative refusée par le délai de son identité ne compte nulle part, puisqu'elle ne vérifie rien : un jeune qui insiste pendant son délai ne consomme pas la limite de l'adresse de la salle.
- Le départ du parcours Google est limité avant la création de la session : un appel refusé ne crée ni session ni parcours. Un navigateur sans session n'a pas encore d'identité : seule son adresse IP le freine.
- Les demandes d'email sont limitées avant toute recherche de compte, y compris pour une adresse inconnue.
- Pour les actions qui envoient un email, un client IPv6 est aussi compté par préfixe /48. Un /48 contient 4 096 réseaux /60, chacun avec sa propre limite par adresse : sans cette limite, un client qui en détient un pourrait faire monter les rebonds d'email jusqu'à faire suspendre l'envoi pour tous les jeunes. Un préfixe plein refuse dès que l'adresse est comptée, sans créer de compteur d'identité, et sans rien retirer aux essais de l'identité du jeune. Le préfixe n'est compté qu'après l'identité et le délai entre deux envois, donc il ne compte que les demandes qui envoient un email : une demande refusée plus tôt n'entame pas la part des autres clients du même /48. Elle n'est jamais inférieure à celle d'une adresse. Un /48 n'a pas un seul détenteur : un opérateur peut y regrouper plusieurs foyers (un /56 ou un /60 chacun), et sa limite se règle d'après la métrique `auth_rate_limit_prefix_limited_total`. Une adresse IPv4 n'a pas de préfixe : sa limite par adresse suffit. Les compteurs d'un /48 sont bornés par ses 4 096 compteurs d'adresse (un par /60) et les identités qu'ils admettent, pas par la limite du préfixe. Un client qui détient plusieurs /48 contourne la limite du préfixe et multiplie ces compteurs : la surveillance de la mémoire de Redis complète cette protection, et la [règle de limitation de débit au proxy](../exploitation.md#protections-dauthentification), qui compte par adresse IP sans regrouper les /60 ni les /48, ne suffit pas à elle seule.
- Un quota dépassé renvoie une erreur 429. Le header `Retry-After` indique l'attente restante du compteur qui refuse, et le message n'annonce pas de durée.
- Si les compteurs sont indisponibles, l'action suit son mode de panne : elle est refusée (503 avec `Retry-After`) quand elle vérifie un mot de passe ou envoie un email, et s'exécute avec un avertissement quand son token ou son état est déjà vérifié. Le [guide des données](donnees.md#panne-des-compteurs) liste les actions de chaque mode. Les huit actions qui vérifient un mot de passe ou envoient un email ne peuvent pas être ouvertes : l'API refuse de démarrer.
- Une session déjà ouverte, la déconnexion et la reconnexion automatique restent utilisables.

La reconnexion automatique n'a pas de quota par adresse IP. Elle est limitée par reconnexion (par famille), après la vérification de son secret :

- Un quota par adresse IP bloquerait des jeunes légitimes. Une fois le quota d'une adresse publique atteint, comme celle du réseau sans fil de la Mission Locale, tous les jeunes suivants seraient refusés pendant 15 minutes.
- Le secret de la reconnexion fait 256 bits : il ne se devine pas. Un cookie mal formé, inconnu ou au mauvais secret est refusé sans rien compter : un client ne peut pas créer de compteur en variant le cookie, ni remplir Redis.
- Une reprise dont le secret est le bon écrit une ligne de session et renouvelle le secret. Elle est comptée par reconnexion, dès que son secret est vérifié, qu'elle réussisse ou non : la limite par défaut est de 10 reprises par fenêtre de 15 minutes. Au-delà, la réponse est un 429 avec `Retry-After`, sans rotation ni écriture, et la reconnexion reste valide. Une reprise légitime a lieu environ une fois par session inactive : la limite ne gêne pas les jeunes d'une même salle, qui ont chacun leur reconnexion.
- Un refus définitif de la reprise (cookie absent, mal formé, inconnu, expiré, révoqué ou rejoué après la tolérance) répond 401 avec le code `RECONNECTION_REFUSED` et son propre message, distinct du refus de la connexion (`INVALID_CREDENTIALS`).
- La lecture de la session (`GET /api/auth/session`) répond 401 avec le seul code `UNAUTHENTICATED`, qu'aucune session n'existe ou que le compte ou la reconnexion soient révoqués entre le filtre et la route.
- Le compteur est appelé hors de toute transaction, quand aucune connexion du pool n'est tenue ni aucun lock posé.
- Deux navigateurs qui portent le même cookie courant se font concurrence : le second est servi comme un secret précédent rejoué dans la tolérance, donc les deux reçoivent une session sans révocation. La reconnexion garde le secret du dernier renouvellement ; le premier servi porte alors un secret précédent, et si sa reprise suivante dépasse la tolérance, la famille est révoquée, comme pour un cookie cloné.
- Quand les compteurs sont indisponibles, la reprise s'exécute, avec un avertissement et une métrique : son secret est déjà vérifié (mode de panne ouvert).
- Les rafales anonymes de reprises ou de déconnexions (des cookies mal formés ou inconnus à la chaîne) ne sont pas comptées par l'API : elles sont limitées au proxy. Voir l'[exploitation](../exploitation.md#protections-dauthentification).

Le plafond de calculs simultanés complète les quotas, qui comptent par adresse IP et par identité :

- Le hash PBKDF2 est volontairement coûteux. Une attaque répartie sur de nombreuses adresses passe sous chaque quota et peut saturer le processeur, partagé avec la recette.
- Le nombre de calculs de hash en cours (vérifier un mot de passe, créer un hash) est plafonné. La valeur et son [réglage](developpement.md#plafond-des-calculs-de-hash-de-mot-de-passe) sont dans le guide de développement.
- Le plafond de calculs couvre la connexion, y compris pour un compte inconnu ou sans mot de passe, l'inscription, la récupération et le parcours Google « accès à un compte existant ».
- Pour la connexion, l'ordre est : quota (délai de l'identité, adresse), lecture du compte, plafond de calculs, calcul. Un refus de quota ne touche ni la base, ni le plafond, ni le calcul. Une tentative refusée par le plafond n'est pas un échec : le quota la rend, et la limite de l'adresse l'a comptée.
- Pour l'inscription, la règle du mot de passe est vérifiée avant le quota : un mot de passe refusé ne lance pas le délai entre deux envois et ne retire rien aux envois de l'email. L'adresse, l'identité et le délai sont ensuite comptés en un seul appel.
- Pour l'inscription, la capacité est vérifiée avant le quota : une demande refusée par le plafond n'utilise pas le délai d'attente de l'adresse, et le nouvel essai est accepté. Le plafond peut encore être atteint entre cette vérification et le calcul : la demande est alors refusée après le quota, ce qui est rare.
- Au-delà du plafond de calculs, la requête reçoit tout de suite un 503 de code `BUSY`, avec un header `Retry-After`, sans attendre ni calculer. Ce code se distingue de `UNAVAILABLE` (une panne) : voir la [couche API frontend](../frontend/api-layer.md#session-et-protection-des-données). La réponse est la même pour un compte connu et pour un compte inconnu.

Les exigences de Redis et d'adresse IP derrière le proxy sont dans l'[exploitation](../exploitation.md#protections-dauthentification). Le comportement de Redis est dans le [guide des données](donnees.md#redis--cache-et-protection-contre-les-abus).

## Second facteur administrateur

Un administrateur se connecte avec un second facteur TOTP : un code temporaire produit par une application d'authentification. Des codes de secours à usage unique existent en cas de perte.

- Une connexion Google ne dispense pas du second facteur.
- Un code déjà utilisé est refusé, et les tentatives sont limitées.
- Le secret TOTP est chiffré au repos. Les codes de secours sont conservés sous forme de hashes. La clé est gérée selon le [guide des secrets](../secrets.md#inventaire-et-séparation-des-droits).

Les règles de perte et de remplacement du facteur sont dans le [guide sécurité](../securite.md#authentification-multifacteur-administrateur).

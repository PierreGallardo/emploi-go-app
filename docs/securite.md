# Sécurité

Ce guide définit les règles à respecter pour protéger les comptes, les données et les accès d'Emploi Go.

## Droits applicatifs

**Refuser tout accès qui n'est pas explicitement autorisé.** Vérifier les droits côté serveur à chaque opération, y compris sur la donnée demandée. Masquer un bouton dans l'interface ne protège pas l'accès.

| Accès          | Limite à respecter                                                                                                                            |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Compte jeune   | Accéder uniquement à son propre profil, ses favoris, ses démarches et ses rappels.                                                            |
| Administration | Limiter les accès aux fonctions de publication et de support autorisées. Aucun accès aux favoris, démarches ou rappels nominatifs des jeunes. |
| Support        | Ne jamais consulter ou choisir le mot de passe d'un jeune, ni se connecter à sa place. Encadrer et tracer les suppressions de comptes.        |

Les fonctions proposées à chaque rôle figurent dans le [périmètre fonctionnel](perimetre-v1.md#administration).

Pour les accès administrateur :

- Utiliser des comptes nominatifs et une authentification multifacteur.
- Attribuer et retirer les droits par une procédure réservée aux personnes habilitées.
- Ne jamais accorder ces droits par une inscription publique ou une connexion Google.
- Revoir régulièrement les droits et les retirer dès qu'ils ne sont plus nécessaires, selon les [principes de gestion des habilitations de la CNIL](https://www.cnil.fr/fr/securite-gerer-les-habilitations).

Si l'identité, les droits ou une protection contre les abus ne peuvent pas être vérifiés, refuser l'opération concernée. Les [guides API](backend/api.md#autorisations-et-session) et [données](backend/donnees.md) précisent les contrôles techniques et les comportements en cas de panne.

## Identité, session et récupération

### Identifiants et mots de passe

Conserver un identifiant interne stable pour chaque compte. Changer le pseudo ou l'email conserve les favoris, les démarches et les autres données du compte. Les règles techniques d'unicité figurent dans le [guide des données](backend/donnees.md#identifiants-de-compte).

#### Règles pour l'utilisateur

- 8 à 128 caractères, comptés en unités UTF-16, comme `@Size` en Java et `String.length` en JavaScript : un emoji compte pour 2.
- Au moins une majuscule, une minuscule, un chiffre et un caractère spécial.
- Chaque caractère est classé par sa catégorie Unicode, sur le texte tel qu'il est saisi (jamais normalisé) : majuscule `Lu` (`É`), minuscule `Ll` (`é`, `ß`), chiffre `Nd`.
- Un caractère spécial est tout caractère qui n'est ni une lettre, ni un chiffre, ni une marque combinante. L'espace, la ponctuation, les symboles et les emoji sont spéciaux. Les lettres accentuées, y compris un accent saisi comme marque séparée, ne le sont pas.
- Une lettre sans casse (chinois, arabe) n'est ni majuscule ni minuscule : un mot de passe écrit seulement dans ces écritures est refusé tant qu'il ne contient pas une majuscule et une minuscule (lettres latines, grecques ou cyrilliques, par exemple).
- Un mot de passe qui respecte la règle est accepté, même courant ou déjà fuité ailleurs.
- Les gestionnaires de mots de passe fonctionnent.
- Le mot de passe saisi n'est jamais tronqué ni modifié.

La règle s'applique à l'inscription et à la réinitialisation, pas à la connexion : les comptes créés avec l'ancienne règle (12 caractères sans composition) se connectent toujours.

Le frontend (`password-rule.ts`) et le backend (`RegistrationPassword.meetsRule`) appliquent la même règle. Un fichier de cas commun, `contracts/password-rule-cases.json`, est lu par les deux suites de tests : un mot de passe est jugé pareil des deux côtés. Pour changer la règle, modifier les deux implémentations et ce fichier. Le JDK et le navigateur peuvent embarquer des versions d'Unicode différentes : pour un caractère très récent, l'écran peut accepter un mot de passe que l'API refuse ensuite (`WEAK_PASSWORD`). Le backend décide.

Sur l'écran :

- une icône à côté du libellé ouvre la règle au survol, au clavier et au toucher ; le lecteur d'écran la lit avec le champ ;
- après un envoi refusé, l'erreur liste seulement ce qui manque (« Il manque : un chiffre, un symbole. ») et se met à jour quand un critère est rempli ;
- le passage à un mot de passe conforme est annoncé ;
- un œil affiche ou masque le mot de passe et sa confirmation.

Pourquoi : elle suit celle des grands services (8 caractères avec composition). Elle est complétée par la limitation des tentatives, qui freine la recherche du mot de passe d'un compte. Le NIST privilégie des mots de passe plus longs sans règle de composition. Le projet choisit la composition, comme les services grand public. La [CNIL](https://www.cnil.fr/fr/mots-de-passe-recommandations-pour-maitriser-sa-securite) adapte ses exigences aux risques.

#### Protection

- Conserver un hash à sens unique, jamais le mot de passe en clair.
- Le hash utilise PBKDF2-HMAC-SHA256 avec 600 000 itérations.
- Aucun hash rapide (HMAC ou SHA) du mot de passe n'est conservée.
- Les tentatives de connexion sont limitées.
- Le nombre de calculs de hash simultanés est plafonné : voir la [limitation des tentatives](backend/authentification.md#limitation-des-tentatives).

Pourquoi la règle seule, sans liste de mots de passe courants ni contrôle des mots de passe compromis :

- Beaucoup de services ne contrôlent pas plus que la règle.
- Un contrôle auprès d'un service tiers ajoute une dépendance externe, ses délais (jusqu'à 3 s par contrôle pendant une panne), sa configuration et sa surveillance.
- Une liste locale de mots de passe courants se régénère à partir de ce même service : elle part avec lui.
- Conséquence acceptée : un mot de passe conforme mais déjà fuité ailleurs (`Marseille13!`) est accepté. La limitation des tentatives ne protège pas contre sa réutilisation, puisque l'attaquant n'essaie qu'une fois par compte.

Ces règles suivent les [recommandations OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), sauf le contrôle des mots de passe compromis, écarté pour les raisons ci-dessus.

### Confirmation d'inscription

L'inscription locale se confirme par un code à 6 chiffres envoyé par email et tapé dans le navigateur qui l'a demandée. Le code n'est pas un lien : il ne fonctionne dans aucun autre navigateur, et il ouvre une session dans celui où il est tapé.

| Règle           | Valeur                                                                                                                                                                                                   |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Longueur        | 6 chiffres, tirés avec la clé du serveur. [NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html) exige au moins 6 chiffres.                                                                 |
| Validité        | 15 minutes, usage unique. Un nouveau code remplace le précédent.                                                                                                                                         |
| Essais par code | 5, puis le code est invalidé.                                                                                                                                                                            |
| Échecs au total | 15 par inscription. Un nouveau code ne remet pas le total à zéro. À chaque 15e échec, 1 heure sans nouveau code ni essai.                                                                                |
| Stockage        | Hash avec clé (HMAC) liée à l'opération. Le code n'est ni stocké, ni journalisé, ni compté dans une métrique.                                                                                            |
| Quotas          | Action `confirmation`, par adresse IP (60) et par inscription d'un navigateur (30), en plus des essais par code. Une inscription est comptée seule : un tiers ne peut pas épuiser le quota du titulaire. |

**Liaison au navigateur.** Le code ne confirme que l'inscription demandée par le navigateur qui le tape. À l'inscription, le serveur tire une clé aléatoire de 256 bits et la garde, avec l'email, dans la session de ce navigateur (cookie `__Host-EMPLOIGO_SESSION`, illisible par le script). La clé ne quitte jamais le serveur et la page ne la connaît pas. L'inscription n'en garde que le hash ; la session, elle, garde la clé et l'email jusqu'à la confirmation, une connexion, la déconnexion ou 30 minutes d'inactivité, et au plus 24 heures après le dernier code. La session est partagée par les onglets du navigateur et par la personne suivante sur un ordinateur partagé : l'inscription porte aussi un identifiant non secret, que l'API donne à la page qui l'a demandée (en-tête `Registration-Id`) et que cette page renvoie. Le renvoi et la confirmation lisent l'email et la clé dans la session, pour l'identifiant reçu seulement ; sinon, la réponse est 410 `NO_PENDING_REGISTRATION`, sans que l'email entre en jeu. C'est l'état côté serveur derrière un identifiant opaque que recommande l'[OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html), sans donnée personnelle dans un cookie ([ASVS 5.0](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x23-V14-Data-Protection.md), 14.3.3).

- Un navigateur dont la clé n'est pas celle de l'inscription ne peut pas confirmer. Il ne devine rien, et ses essais ne touchent pas les compteurs de l'inscription : ils sont comptés à part, dans Redis, par email et par clé.
- Un tiers qui inscrit l'adresse d'un autre avec son propre mot de passe ne fait jamais confirmer ses identifiants par le titulaire. Le code arrive chez le titulaire, mais la clé reste dans la session du navigateur du tiers.
- Chaque inscription acceptée tire une nouvelle clé et un nouvel identifiant, même dans le même navigateur et pour la même adresse. Une inscription refusée, même parce qu'un email vient de partir, ne garde rien : la page reste sur le formulaire. Une autre page du navigateur, ou la personne suivante qui ouvre le site, ne confirme donc jamais les identifiants tapés par quelqu'un d'autre. Seule la page de l'inscription le peut : laissée ouverte sur un ordinateur partagé, ou rouverte par l'historique, elle sert à qui la retrouve, comme toute page laissée ouverte. Un navigateur ne garde que sa dernière inscription, et l'écran du code n'en reprend aucune sans l'état de navigation de la page qui l'a demandée.
- La dernière demande pour une adresse remplace le mot de passe et la clé, et invalide le code précédent. Le titulaire qui inscrit son adresse après le tiers reprend l'inscription. Un tiers qui la reprend ensuite ne connecte pas le titulaire avec ses identifiants : le code que le titulaire lit est alors lié au navigateur du tiers, et le titulaire reçoit un refus.
- Pendant l'heure sans code, la page de l'inscription est refusée (429) au renvoi et à la confirmation. Une nouvelle inscription, même depuis cette page, tire une nouvelle clé : elle reçoit la réponse d'une adresse sans inscription, sans nouveau code et sans rien changer.

**Aucune énumération par l'inscription.** Que l'email ait une inscription en attente, un compte ou rien, l'écran de code et ses réponses sont les mêmes pour un navigateur : code faux (4 fois), trop d'essais (5e), expiré après 15 minutes, puis une heure d'attente (15e échec). Trois dispositions le garantissent ; leurs limites sont décrites plus bas.

- Une inscription pour l'email d'un compte n'enregistre rien : le serveur hache quand même le mot de passe, répond comme pour un email libre, et le titulaire reçoit l'email « Tu as déjà un compte », adapté à son mode de connexion (mot de passe, Google ou les deux). Ainsi, aucun mot de passe choisi par un tiers n'est enregistré pour l'adresse d'un compte.
- Un compte créé par Google clôt l'inscription en attente de son adresse, dans la même transaction, et efface son mot de passe. Le navigateur de cette inscription est ensuite traité comme celui d'un email avec compte : ses essais sont comptés dans Redis. Une inscription demandée pendant cette transaction peut encore être enregistrée, mais sa confirmation est refusée (409), sans second compte.
- Les essais d'un navigateur sans l'inscription sont comptés à part, dans Redis, avec les mêmes réponses (attente, expiration 15 minutes après le dernier code demandé par ce navigateur, essais épuisés). Pour l'email d'un compte, le navigateur de la dernière inscription tient l'adresse, comme celui d'une inscription : ses échecs, son heure d'attente, les essais et l'expiration de son code sont comptés sur l'adresse. Une nouvelle inscription d'un autre navigateur hérite des échecs, et tout code émis pour l'adresse (inscription ou renvoi) remplace le code en cours, comme pour une inscription. Tout autre navigateur garde ses propres compteurs, si bien qu'un tiers ne bloque jamais le titulaire d'un email libre.
  La connexion ne reprend jamais une inscription : le mot de passe d'une inscription en attente reçoit le refus de tout mauvais mot de passe (401), sans code ni email, quelle que soit l'adresse. Seule une nouvelle inscription renvoie un code.

Quand Google crée le compte d'une adresse, le navigateur de son inscription en attente perd les échecs déjà comptés en base : les essais du code en cours, le total vers le 15e échec et le reste d'une heure d'attente. Ses compteurs Redis repartent de zéro. Redis ne peut pas reprendre ces échecs : la base ne garde que le hash de la clé, et les compteurs Redis sont rangés sous la clé elle-même. Un navigateur qui avait déjà échoué peut donc voir sa réponse changer (code faux au lieu de « trop d'essais » ou d'une attente) et en déduire la création du compte. Seul le navigateur qui détient la clé de l'inscription le voit, et ses essais ne confirment jamais rien.

**Borne de devinette.** Seul le navigateur qui détient la clé peut deviner. Il a au plus 15 essais, puis 1 heure d'attente, soit au plus **360 essais par jour et par adresse**. Chaque code est tiré indépendamment parmi 1 000 000 valeurs : la chance de réussir est d'environ **0,036 % par jour** d'attaque continue, nulle pour un navigateur sans la clé. Cette chance se cumule tant que l'attaque dure (environ 1 % par mois), et l'attaque envoie au moins trois emails au titulaire par heure. La demande d'un nouveau code reste limitée par les quotas d'email (3 par 15 minutes et par adresse, 60 secondes entre deux envois).

**Limite assumée.** Un tiers qui a inscrit l'adresse d'un autre peut gêner le titulaire : les 15 échecs du tiers bloquent l'inscription pendant une heure, et le titulaire qui la reprend doit attendre la fin de ce blocage pour confirmer. Aucune donnée n'est exposée et aucun compte n'est pris.

Une inscription non demandée doit être ignorée : un tiers peut avoir choisi son mot de passe, puis accéder au compte s'il devine le code. L'email de code explique qu'il faut l'ignorer sans action. La confirmation ne remplace ni ne fusionne un compte existant. Le [guide API](backend/authentification.md#inscription-et-confirmation-de-lemail) précise les résultats et les refus.

### Liens de vérification et récupération

La page « Mot de passe oublié » permet de réinitialiser le mot de passe Emploi Go. Envoyer le lien à l'adresse vérifiée du compte.

| Situation                               | Règle                                                                                                                                                                                                          |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Demande de récupération                 | Afficher « Si un compte correspond à cette adresse, un message sera envoyé », que le compte existe ou non. Garder un temps de réponse comparable pour ne pas révéler l'existence du compte.                    |
| Demandes ou essais répétés              | Limiter leur fréquence. Une simple demande de récupération ne bloque pas le compte.                                                                                                                            |
| Lien de vérification ou de récupération | Autoriser uniquement l'action prévue sur le compte destinataire : par exemple, réinitialiser son mot de passe. Fixer une échéance et un usage unique. Un nouveau lien invalide le précédent pour cette action. |
| Lien expiré, utilisé ou remplacé        | Refuser l'opération.                                                                                                                                                                                           |
| Aide d'un administrateur autorisé       | Permettre l'envoi du lien à l'adresse vérifiée de l'utilisateur. L'administrateur ne reçoit pas le lien à sa place et ne choisit pas son mot de passe.                                                         |

Après une récupération réussie :

1. Informer l'utilisateur du changement de mot de passe.
2. Invalider ses anciennes sessions et ses moyens de reconnexion automatique sur tous les appareils.
3. Demander une connexion normale. Le lien de récupération ne connecte pas directement l'utilisateur.

Pour un compte utilisant uniquement Google, la page « Mot de passe oublié » ne crée pas de mot de passe Emploi Go. Garder le même message à l'écran. L'email envoyé à l'utilisateur peut lui rappeler de se connecter avec Google ou de suivre la récupération de compte Google s'il en a perdu l'accès. Emploi Go ne peut pas réinitialiser un mot de passe Google.

Pour changer l'email, le mot de passe ou une méthode de connexion, demander une nouvelle authentification explicite. Une reconnexion automatique ne suffit pas. Avant de remplacer l'email, confirmer la nouvelle adresse et notifier l'ancienne. Les accès à invalider sont précisés dans la section [sessions](#sessions-et-réglages-initiaux).

La protection technique des liens figure dans le [guide backend](backend/authentification.md#mot-de-passe-oublié), avec les [recommandations OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).

### Google

- Retrouver le compte Emploi Go à partir de l'identifiant technique fourni par Google. Un changement de l'email Google doit retrouver le même compte.
- Pour Gmail ou une adresse Google Workspace vérifiée selon les conditions du guide backend, accepter la confirmation fournie par Google. Pour une autre adresse associée au compte Google, envoyer un lien de confirmation depuis Emploi Go avant de créer ou d'associer le compte.
- Si l'adresse correspond à un compte Emploi Go existant, demander aussi la connexion à ce compte, puis la confirmation de l'association. Google devient alors une autre façon d'accéder au même compte. Une adresse identique ne suffit pas à autoriser cette association.
- Si le retrait de Google des méthodes de connexion est proposé, demander une nouvelle authentification et vérifier qu'une autre méthode valide permet d'accéder au compte.

Les vérifications des réponses Google sont décrites dans le [guide backend](backend/authentification.md#connexion-et-inscription-avec-google).

### Authentification multifacteur administrateur

Exiger un second facteur TOTP : un code temporaire produit par une application d'authentification. Prévoir des codes de secours à usage unique, conservés séparément par leur titulaire. Une connexion Google ne dispense pas de ce second facteur.

| Situation                              | Règle                                                                                                                                 |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Connexion avec un code TOTP            | Refuser un code déjà accepté et limiter les tentatives.                                                                               |
| Application d'authentification perdue  | Permettre la récupération avec un code de secours à usage unique, après vérification du mot de passe ou de la connexion Google.       |
| Application et codes de secours perdus | Faire vérifier l'identité de l'administrateur par une personne habilitée et tracer l'intervention. Connaître son email ne suffit pas. |

Pour remplacer l'application d'authentification :

1. Demander une nouvelle connexion par mot de passe ou Google et un code de l'ancienne application TOTP. Si celle-ci est perdue, suivre la récupération ci-dessus.
2. Configurer la nouvelle application et vérifier un code qu'elle produit avant de remplacer l'ancien facteur.
3. Invalider l'ancien facteur, les anciens codes de secours et les anciennes sessions. Fournir de nouveaux codes de secours et informer l'administrateur du changement.

La protection du secret TOTP et des codes de secours figure dans le [guide backend](backend/authentification.md#second-facteur-administrateur). Les règles de récupération suivent les [recommandations OWASP sur la MFA](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html).

### Sessions et réglages initiaux

Une session permet au serveur de reconnaître l'utilisateur pendant l'utilisation du site. La reconnexion automatique crée une nouvelle session sans afficher l'écran de connexion.

Pour le jeune, quatre cookies :

| Cookie                          | Rôle                                                               | Lisible par le script |
| ------------------------------- | ------------------------------------------------------------------ | --------------------- |
| `__Host-EMPLOIGO_SESSION`       | Session en cours.                                                  | Non                   |
| `__Host-EMPLOIGO_RECONNECTION`  | Reconnexion automatique.                                           | Non                   |
| `__Host-XSRF-TOKEN`             | Token CSRF, renvoyé par le frontend dans le header `X-CSRF-TOKEN`. | Oui                   |
| `__Host-EMPLOIGO_RECONNECTABLE` | Indicateur : une reconnexion est possible. Sans secret.            | Oui                   |

Ce sont les noms de production. Le préfixe `__Host-` est expliqué dans [Cookies et sous-domaines](#cookies-et-sous-domaines).

Un visiteur anonyme n'a ni session ni cookie de session. La session est créée à la connexion, à la reconnexion, au départ du parcours Google et à l'inscription locale acceptée, sauf si le [quota](backend/authentification.md#limitation-des-tentatives) refuse la demande : seuls ces actes explicites écrivent une ligne en base, pas une simple visite ni un robot qui lit l'API.

La reconnexion est donnée à une connexion Google, ou au navigateur où le jeune a coché « Rester connecté » (décoché par défaut). Sans la case, les cookies de reconnexion et l'indicateur sont effacés et le cookie de session n'a pas de date d'expiration : la session s'arrête à la fermeture du navigateur ou après l'inactivité. La famille de reconnexion est quand même créée en base, car chaque requête vérifie qu'elle n'est pas révoquée (déconnexion) et que la version des identifiants n'a pas changé (réinitialisation du mot de passe). Son secret n'est jamais envoyé, et son échéance est celle de la session (8 heures) : la purge la supprime ensuite. À l'inscription, la case du formulaire est gardée avec l'inscription en attente dans la session du navigateur, et vaut pour la session ouverte par le code. Le choix du pseudo rouvre la session comme elle était gardée. Google ne propose pas la case : ses connexions sont toujours gardées 30 jours.

L'indicateur est posé à la connexion avec « Rester connecté » ou par Google et à la reconnexion, pour la durée de la reconnexion. Il est effacé à la déconnexion et quand la reconnexion est refusée définitivement. Le frontend n'appelle la reconnexion que s'il est présent. Un faux indicateur ne fait que déclencher un appel refusé.

À l'expiration de la session :

1. Vérifier le cookie de reconnexion, l'état du compte et ses droits.
2. Créer une nouvelle session si tout est valide.
3. Sinon, demander une connexion par mot de passe ou Google.

L'inactivité de la session est réglée par `SESSION_TIMEOUT` (30 minutes par défaut). Les autres durées sont des règles du projet, pas des durées imposées par OWASP. Toute modification doit être documentée.

| Usage                         | Durée et limite                                                                 |
| ----------------------------- | ------------------------------------------------------------------------------- |
| Lien de récupération          | 30 minutes, usage unique.                                                       |
| Confirmation d'email          | 24 heures, usage unique.                                                        |
| Session jeune                 | 30 minutes d'inactivité (`SESSION_TIMEOUT`) et 8 heures au maximum.             |
| Reconnexion automatique jeune | 30 jours à partir de la dernière connexion ou reconnexion réussie.              |
| Session administrateur        | 15 minutes d'inactivité et 8 heures au maximum. Aucune reconnexion automatique. |

Chaque reconnexion réussie démarre une nouvelle période de 30 jours. Une reconnexion au jour 10 repousse donc l'échéance au jour 40. L'expiration d'une session après 8 heures n'impose pas de ressaisir le mot de passe tant que la reconnexion est valide. Pour une action sensible, demander une nouvelle authentification même après une reconnexion automatique.

La reconnexion n'a aucune durée maximale : un jeune qui revient au moins une fois tous les 30 jours reste connecté, comme sur les grands services en ligne. Cette durée est sûre parce que :

- le secret change à chaque reconnexion ;
- rejouer un ancien secret après la tolérance révoque toute la famille de reconnexion, comme la détection de réutilisation des refresh tokens de la [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700) ;
- la déconnexion et la suspension du compte révoquent la reconnexion, et la réinitialisation du mot de passe la rend inutilisable en changeant la version des identifiants ;
- une action sensible exige une nouvelle authentification.

La tolérance est de 30 secondes après la rotation (réglage : [guide de développement](backend/developpement.md#tolérance-de-réutilisation-du-secret-de-reconnexion)). Elle couvre une réponse perdue : le client abandonne la rotation après 15 secondes et garde l'ancien secret, que la lecture suivante rejoue. Si ce rejeu arrive dans les 30 secondes, l'ancien secret est servi comme le secret courant : nouvelle session, nouveau secret, sans révocation. C'est la pratique d'Auth0 (`leeway`), d'Okta (30 s par défaut, 60 s au plus) et de Supabase (10 s). Le compromis : un cookie volé juste avant une rotation et rejoué dans ces 30 secondes obtient une session. Le titulaire le révèle quand il rejoue un secret précédent hors de la tolérance qui suit la dernière rotation : la famille est révoquée avec un avertissement, voleur compris. Un voleur qui tourne le secret deux fois de suite ramène le secret du titulaire à un secret plus ancien que le précédent : celui-ci reçoit alors un simple refus, sans révocation, comme pour un secret courant volé. Une relance plus tardive que la tolérance, par exemple au retour du réseau après plusieurs minutes, révoque la famille comme pour un vol : le jeune se reconnecte alors avec son mot de passe. Seul le secret précédent est reconnu : un secret plus ancien reçoit un simple refus, sans révocation, alors qu'Auth0 déclenche la détection de réutilisation.

Appliquer ces limites côté serveur :

- Avant de recréer une session jeune, vérifier la validité de la reconnexion, l'état du compte et ses droits.
- Renouveler le secret de reconnexion et son échéance lors d'une reconnexion automatique réussie. Ne jamais réactiver un accès expiré ou invalidé.
- Renouveler l'identifiant de session après une authentification ou un changement de privilège.
- Lire un cookie de reconnexion ou un lien de récupération ou de confirmation Google (`<identifiant>.<secret>`, 80 caractères) en un seul endroit, avant toute transaction : un texte qui n'a pas cette forme est refusé sans emprunter de connexion du pool, que n'importe qui peut solliciter.
- Protéger les cookies de session. Conserver une protection CSRF pour refuser les actions forgées depuis un autre site. Le token est un cookie renvoyé dans un header (double soumission) : il ne dépend d'aucune session, et l'API lit le header ou, à défaut, le paramètre `_csrf` d'un formulaire, qu'un autre site ne peut pas remplir faute de lire le cookie. Cette protection est sûre parce que le cookie porte le préfixe `__Host-`, qui interdit à un sous-domaine de le déposer, et parce que `Secure` avec HSTS empêche un attaquant réseau en HTTP de l'injecter. Une faille XSS lirait le token, mais elle contournerait de toute façon toute protection CSRF.
- Renouveler le token CSRF à la connexion et à la reconnexion, et l'effacer à la déconnexion : l'écriture suivante en obtient un nouveau.
- Ne pas stocker de mots de passe ou de tokens d'authentification dans `localStorage` ou `sessionStorage`. Seul le thème choisi (`light` ou `dark`) y est gardé : ce n'est ni un secret ni une donnée personnelle.

À la déconnexion, révoquer côté serveur la session et la reconnexion de ce navigateur, effacer leurs cookies, annuler les requêtes du compte et vider son cache frontend. La déconnexion doit rester effective même si le navigateur est encore connecté à Google. Effacer les cookies seuls ne suffit pas.

Invalider un accès signifie que le serveur refuse ensuite l'ancien cookie. Appliquer cette invalidation automatiquement après l'action concernée :

| Action                                                                                         | Accès à invalider                                                                                                                                      |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Déconnexion                                                                                    | Session et reconnexion du navigateur concerné.                                                                                                         |
| Réinitialisation d'un mot de passe oublié ou suspension du compte                              | Toutes les sessions et reconnexions du compte.                                                                                                         |
| Réutilisation d'un ancien secret de reconnexion, après la tolérance                            | La session et la reconnexion de cette famille. Les autres navigateurs du compte restent connectés ; le jeune se reconnecte avec son mot de passe.      |
| Ajout de Google après authentification locale explicite et confirmation                        | Conserver les sessions et reconnexions existantes ; notifier le titulaire.                                                                             |
| Changement de l'email, du mot de passe, suppression ou remplacement d'une méthode de connexion | Les anciennes sessions et reconnexions. Une nouvelle session peut être créée après validation complète du changement et de l'authentification requise. |
| Remplacement du second facteur administrateur                                                  | Les anciennes sessions administrateur. Exiger le nouveau facteur pour une nouvelle connexion.                                                          |

Associer les sessions et moyens de reconnexion au compte pour pouvoir les invalider. Cela n'exige pas d'enregistrer les pages consultées ni de proposer une interface de gestion des appareils.

Le [guide backend](backend/authentification.md#sessions-et-reconnexion-automatique) décrit les cookies et la reconnexion ; le [guide données](backend/donnees.md#sessions) décrit le stockage. Les [recommandations OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) distinguent inactivité, durée maximale et révocation.

### Cookies et sous-domaines

Un sous-domaine, comme une recette (environnement de test avant la production) sur `recette.emploigo.fr`, peut déposer des cookies valables pour tout le domaine. Le navigateur les envoie ensuite à la production, qui ne peut pas savoir d'où ils viennent. Sans protection, un sous-domaine pourrait connecter un jeune au compte de quelqu'un d'autre, ou lui imposer une session dont il connaît le token CSRF.

Règles, qui permettent à la recette d'utiliser un sous-domaine de la production :

- En production, tout cookie posé par l'API porte le préfixe `__Host-` : session, reconnexion, token CSRF et indicateur de reconnexion. Le navigateur impose alors `Secure`, `Path=/` et l'absence de `Domain`. Il refuse qu'un autre sous-domaine dépose un cookie de ce nom.
- Avec le profil `local`, en HTTP, les noms restent sans préfixe, car le préfixe exige HTTPS.
- Ne jamais poser de cookie avec l'attribut `Domain`. Hors profil `local`, l'API refuse de démarrer si la configuration du cookie de session perd le préfixe, `Secure` ou `Path=/`, ou fixe un `Domain`.
- Garder la protection CSRF et le préfixe de son cookie. `SameSite` ne protège pas d'un sous-domaine : pour le navigateur, un sous-domaine et la production sont le même site. Sans le préfixe, un sous-domaine déposerait son propre token et le renverrait dans le header.
- Ne jamais autoriser un sous-domaine à appeler l'API de production (CORS).
- Ne laisser aucun enregistrement DNS du domaine pointer vers un service abandonné ou hébergé par un tiers.
- Ne jamais journaliser les cookies, ni au proxy ni dans l'API (voir [Logs et rapports](#logs-et-rapports)). Le cookie de reconnexion, en `Path=/`, accompagne toutes les requêtes du site.

Une recette compromise peut encore remplir le navigateur de cookies pour le domaine. Elle peut ainsi déconnecter l'utilisateur ou rendre ses requêtes trop grosses pour être acceptées. Elle ne peut pas prendre sa session.

## Données personnelles

### Responsabilités et conservation

Avant tout traitement réel, identifier l'organisme responsable et l'interlocuteur compétent. Cette responsabilité dépend de qui détermine les finalités et les moyens du traitement ; ne pas la supposer à partir du nom d'un partenaire. Voir la [définition de la CNIL](https://www.cnil.fr/fr/definition/responsable-de-traitement).

Faire préciser avec les personnes compétentes :

- les finalités, bases légales, destinataires et prestataires ;
- l'information des utilisateurs et l'exercice de leurs droits ;
- les durées de conservation propres aux comptes, favoris, démarches et rappels ;
- les modalités de suppression et d'archivage, y compris pour les sauvegardes ;
- le besoin éventuel d'une analyse d'impact, sans le déduire automatiquement du public concerné.

Les durées de supervision ne fixent pas celles des données métier. Après une restauration, traiter la réintroduction éventuelle de données supprimées ou d'anciens droits d'accès.

Les décisions à obtenir sont recensées dans les [questions de protection des données](questions-ouvertes.md#protection-des-données-personnelles). Le suivi de la conformité figure dans l'issue [#41 « Conformité RGPD »](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/41). Ce guide ne constitue pas une attestation de conformité juridique.

### Collecte et exposition

| Données ou usage                 | Règle                                                                                                                                                                                                                                                                                    |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Compte                           | Collecter uniquement les données nécessaires. Ne pas imposer une identité civile lorsque le pseudo suffit.                                                                                                                                                                               |
| Démarches                        | Enregistrer les actions déclarées, comme « J'ai postulé ». Ouvrir une fiche d'entreprise ne crée pas d'historique individuel de consultation.                                                                                                                                            |
| Géolocalisation                  | La demander à l'initiative de l'utilisateur. Permettre la recherche manuelle en cas de refus. Ne pas conserver durablement la position exacte ni l'inscrire dans une URL, un log ou une ressource publique : la carte l'envoie dans un header ([API](backend/api.md#position-du-jeune)). |
| Développement, CI et recette     | Utiliser des données fictives, sans copie de la base de production. Prévoir des accès de recette aux services externes, comme l'envoi d'emails et le stockage d'images. Envoyer les emails uniquement aux destinataires de recette.                                                      |
| Fond cartographique public       | Garder les fichiers du fond distincts des points métier. Ne jamais y intégrer de donnée privée, comme la position précise d'un jeune ou ses démarches : ces fichiers sont téléchargeables publiquement.                                                                                  |
| Réponses de compte et de session | Les exclure des caches HTTP partagés. Une réponse publique mise en cache doit être identique pour tous, sans donnée personnelle ni cookie de session.                                                                                                                                    |

## Entrées et secrets

- Valider les données reçues côté serveur.
- Fixer des limites adaptées à chaque fonction : poids des fichiers reçus, longueur des textes, dimensions des images et nombre de résultats par page.
- Limiter la durée des traitements et le nombre de tâches exécutées en même temps pour éviter de saturer le serveur.
- Ne pas révéler de requête SQL, de trace technique interne ou de secret dans les erreurs publiques.
- Aucune clé Redis des compteurs de quota ne contient un email, un pseudo, une adresse IP ni un hash sans secret de ces valeurs : ce sont des HMAC avec une clé de l'API. Voir la [forme des quotas](backend/donnees.md#forme-des-quotas) et la [clé des compteurs](secrets.md#clé-des-compteurs-de-quota).
- Garder les secrets hors de Git, des images applicatives, des logs, des arguments de commande et du code frontend distribué au navigateur.

Les [guides API](backend/api.md) et [worker](backend/worker.md) précisent la validation et les appels aux fournisseurs. Le [guide des secrets](secrets.md) définit leur stockage, leur distribution et leur renouvellement.

## Accès techniques et exposition

Accorder uniquement les droits nécessaires à chaque personne et à chaque service. Séparer les accès de recette et de production, ainsi que les droits de lecture, d'envoi de données et d'administration.

| Accès                                 | Règle                                                                                                                                                                                                               |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Développement et diagnostic           | Ils ne donnent pas automatiquement accès aux serveurs, aux secrets ou aux données métier de production.                                                                                                             |
| Déploiement et administration système | Utiliser des comptes et des clés nominatifs. Accorder chaque accès séparément : un développeur chargé du déploiement doit aussi recevoir l'autorisation de déployer.                                                |
| Services techniques                   | Donner à chaque programme seulement les permissions nécessaires. Par exemple, envoyer un email ne demande pas le droit d'administrer la messagerie.                                                                 |
| Santé publique                        | Indiquer si le service fonctionne, sans publier les adresses des serveurs internes ni les erreurs détaillées de connexion à la base.                                                                                |
| Supervision                           | Réserver les mesures détaillées, comme la mémoire utilisée et les temps de réponse, aux personnes et outils autorisés. Protéger aussi les interfaces qui permettent de les consulter ou de configurer les alertes.  |
| Worker                                | Exécuter les tâches en arrière-plan, comme l'envoi de rappels. Les demandes de l'interface, comme consulter ses favoris, passent par l'API. Ne pas exposer ces routes ni leur documentation OpenAPI dans le worker. |

Une information interne peut être consultée par les personnes autorisées sans être publiée sur Internet. Le droit de gérer les offres dans Emploi Go ne donne pas automatiquement accès à la supervision des serveurs.

Le filtrage réseau limite les connexions aux services, par exemple en empêchant Internet de joindre directement PostgreSQL. L'API doit toujours vérifier les droits de chaque utilisateur sur les données demandées.

Les configurations sont décrites dans les guides [exploitation](exploitation.md#réseau-et-https), [données](backend/donnees.md#postgresql-et-migrations) et [secrets](secrets.md#inventaire-et-séparation-des-droits).

## Diagnostic et incidents

### Logs et rapports

Séparer les rapports de diagnostic et les logs de sécurité, avec des droits de lecture distincts.

| Usage                            | Informations à conserver                                                                                                                                                                                                    |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Diagnostic pour les développeurs | Erreurs et performances techniques : par exemple, version de l'application et erreur d'affichage. Exclure les identifiants personnels, formulaires, body HTTP, cookies, tokens, positions géographiques et adresses IP.     |
| Analyse de sécurité              | Événements nécessaires à une enquête : connexions suspectes, changements de droits ou suspensions de comptes. Conserver l'adresse IP lorsqu'elle est utile à cette analyse, avec un accès réservé aux personnes habilitées. |

Définir pour chaque événement les champs collectés et les personnes autorisées à les lire. Filtrer les informations avant leur envoi et à leur réception par le collecteur. Ne jamais écrire dans les logs de mot de passe, de cookie ou de token secret.

Tracer par exemple « l'administrateur 17 a suspendu le compte 42 », sans recopier le profil, les favoris ou les démarches. Sélectionner les événements utiles ; ne pas enregistrer toutes les commandes, les clics ou les pages consultées. Ne pas enregistrer de rejeu de session.

Les contrôles Faro figurent dans la [qualité frontend](frontend/qualite.md#sécurité-et-confidentialité). Les accès et les purges relèvent de l'[exploitation](exploitation.md#supervision-et-logs). La durée de conservation des logs de sécurité y est définie une seule fois.

### Réagir à un incident

La procédure d'incident doit préciser comment :

1. Contacter les personnes responsables.
2. Conserver les preuves nécessaires.
3. Couper les accès compromis et révoquer les secrets exposés.
4. Reprendre le service depuis un environnement fiable.
5. Vérifier si des données personnelles ont été consultées sans autorisation, divulguées, modifiées, perdues ou rendues indisponibles.

En cas de violation de données personnelles :

- Notifier la CNIL sous 72 heures lorsque la violation présente un risque pour les personnes.
- Informer les personnes concernées lorsque le risque est élevé.
- Inscrire toute violation au registre, même si elle n'est pas notifiée.
- Décider de ces notifications avec le responsable compétent.

La reprise technique ne clôt pas l'analyse de l'incident. Voir les [recommandations de la CNIL](https://www.cnil.fr/fr/securite-gerer-les-incidents-et-les-violations).

## Headers HTTP

Les headers de sécurité demandent au navigateur d'appliquer des protections supplémentaires.

Qui les pose :

- Le serveur frontend (Caddy), pour les pages et les fichiers statiques.
- Spring Security, pour les réponses `/api`.

Valeurs cibles :

| Header                      | Valeur                                                                                                                                                                                                                                                                                                                                                                     | Pourquoi                                                                            |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `Content-Security-Policy`   | Pages : `default-src 'self'; script-src 'self' <empreintes>; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; style-src-attr 'none'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. API : `default-src 'none'; frame-ancestors 'none'` | Le navigateur n'exécute que les scripts du site, ce qui bloque l'injection de code. |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`                                                                                                                                                                                                                                                                                                                                      | Le navigateur refuse de revenir en HTTP pendant un an.                              |
| `X-Content-Type-Options`    | `nosniff`                                                                                                                                                                                                                                                                                                                                                                  | Le navigateur ne devine pas le type d'un fichier et respecte celui annoncé.         |
| `Referrer-Policy`           | `strict-origin-when-cross-origin`                                                                                                                                                                                                                                                                                                                                          | Les autres sites ne reçoivent pas le chemin complet des pages visitées.             |
| `Permissions-Policy`        | `camera=(), microphone=(), geolocation=(self)`                                                                                                                                                                                                                                                                                                                             | Les fonctions sensibles du navigateur sont coupées, sauf la position.               |

La CSP des pages est générée au build, jamais écrite à la main :

- React Router écrit des scripts inline dans chaque page HTML : contexte, chargement des modules, données et restauration du défilement. Leur contenu change avec chaque build et diffère d'une page à l'autre.
- Caddy sert les pages telles quelles : il ne peut pas y écrire un nonce différent à chaque réponse, et un nonce figé ne protège rien.
- Le build calcule donc l'empreinte `sha256` de chaque script inline de toutes ses pages HTML, prérendues et repli compris. Il écrit la CSP dans `build/csp.caddyfile`, que l'image copie et que le `Caddyfile` importe ([`tooling/csp.ts`](../frontend/tooling/csp.ts)).
- Un script inline ajouté à la page, par injection ou par erreur, n'a pas d'empreinte connue : le navigateur le bloque.
- `vite preview` envoie la même CSP : les tests navigateur échouent sur toute violation.

Les éléments `<style>` acceptent `'unsafe-inline'` (`style-src-elem`) :

- Les dialogues de Radix (`react-remove-scroll`) et le champ de code (`input-otp`) insèrent des éléments `<style>` au runtime.
- Leur contenu dépend par exemple de la largeur de la barre de défilement : aucune empreinte ne peut le prévoir. Sans `'unsafe-inline'`, le fond d'un dialogue défile.
- Un style injecté ne peut pas exécuter de code, et ses images et polices restent limitées au site par `img-src` et `font-src`.
- Les attributs `style` restent interdits (`style-src-attr 'none'`) : React et MapLibre passent par le CSSOM, que la CSP ne limite pas.

`eval` reste interdit : Zod est configuré sans compilation JIT (`jitless`), qui l'essaierait.

Les réponses de l'API ne sont pas des pages : leur CSP n'autorise rien.

Points d'attention :

- La carte MapLibre a besoin de `img-src data: blob:` et d'un worker (`worker-src blob:`).
- `geolocation=(self)` permet « Autour de moi », qui demande la position uniquement à l'action de l'utilisateur.
- Un collecteur de diagnostic navigateur (Faro) doit être ajouté à `connect-src` s'il est activé.

Contrôle : lancer un test externe ([Mozilla Observatory](https://developer.mozilla.org/en-US/observatory)) sur la recette après chaque changement de configuration. Un changement de build ne demande aucun geste : la CSP suit les pages.

## Vérifier les protections

Pour chaque changement concerné, utiliser des données fictives et vérifier notamment :

| Essai                                                               | Résultat attendu                                                                                       |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| Appeler l'API sans passer par l'interface                           | Les mêmes contrôles de droits s'appliquent.                                                            |
| Demander les démarches d'un autre compte                            | L'accès est refusé.                                                                                    |
| Retirer un droit administrateur, puis réessayer une action protégée | L'action est refusée.                                                                                  |
| Réutiliser une session expirée ou invalidée                         | L'ancienne session est refusée. Seul un moyen de reconnexion encore valide peut en créer une nouvelle. |
| Rendre indisponible la vérification de session                      | L'opération protégée est refusée.                                                                      |

Faire relire le changement selon les [règles de contribution](../CONTRIBUTING.md). La [qualité backend](backend/qualite.md) précise les tests.

Vérifier aussi les conditions réelles d'exploitation : tester depuis l'extérieur les accès réseau interdits et restaurer une sauvegarde dans un environnement d'essai. Les tests du code ne prouvent pas que le serveur de production est bien configuré ni qu'une sauvegarde est restaurable.

# Périmètre fonctionnel V1

Emploi Go aide les jeunes à découvrir les entreprises et métiers de leur territoire et à retrouver leurs démarches vers l'emploi. La V1 est une application web responsive, utilisable sur téléphone et ordinateur. Les données métier initiales couvrent Dijon Métropole.

Ce document décrit le périmètre cible de la V1. Il sert de référence pour la conception, le développement et la recette (environnement de test avant la production, où l'on valide une version). La validation des écarts avec le cahier des charges initial est suivie dans les [questions ouvertes](questions-ouvertes.md#validation-du-périmètre).

## Comptes et accès

### Création de compte et connexion

Le jeune crée un compte avec :

- une adresse email vérifiée ;
- un mot de passe ;
- un pseudo unique et modifiable ;
- un avatar composé d'éléments graphiques.

Il peut aussi s'inscrire et se connecter avec Google. Il peut se connecter avec son email ou son pseudo, récupérer son accès et demander la suppression de son compte.

### Pseudo et avatar

- Un pseudo compte 2 à 32 caractères : lettres sans accent, chiffres, `_` et `.`, sans deux `.` de suite. Seuls ces caractères sûrs sont admis : deux pseudos ne peuvent pas se ressembler à l'œil (un `а` cyrillique pour un `a` latin). Le `@` reste réservé aux emails.
- Un pseudo saisi en majuscules est enregistré en minuscules : `Alice` devient `alice`. L'unicité et la connexion par pseudo ignorent donc la casse.
- Un pseudo hors règle est refusé avec le message « 2 à 32 caractères : lettres sans accent, chiffres, _ et . », qui n'apparaît qu'en erreur.
- Un pseudo indisponible est refusé.
- Une modification de pseudo conserve le compte, ses favoris et ses démarches.
- L'avatar utilise un catalogue d'éléments versionnés, avec leurs licences ou autorisations d'usage.

### Google

- Un compte Google associé est retrouvé par son identifiant stable (`sub`, l'identifiant stable d'un compte Google).
- Un email identique à celui d'un compte local ne fusionne pas les comptes. Le jeune accède d'abord à son compte Emploi Go, puis y associe Google.
- La vérification de l'email suit les règles du [guide sécurité](securite.md#google).
- Google ne confère aucun droit administrateur.
- Google n'importe ni la photo de profil, ni l'identité civile.
- Un détachement de Google ne peut pas retirer le dernier moyen valide d'accès au compte.

### Session et reconnexion automatique

- Le jeune choisit « Rester connecté », décoché par défaut, quand il se connecte ou s'inscrit avec son email et un mot de passe. Cochée, il retrouve sa connexion aux visites suivantes sur le même navigateur. Le choix de l'inscription vaut pour la session ouverte par le code, puis par le pseudo. Sans la case, la session s'arrête à la fermeture du navigateur ou après l'inactivité : sur un ordinateur partagé, la personne suivante n'arrive pas dans son compte.
- Google n'a pas de case et garde toujours la connexion : sur un ordinateur partagé, une session Google restée ouverte reconnecterait de toute façon la personne suivante en un clic. Se déconnecter reste le seul arrêt immédiat, car un navigateur qui restaure ses onglets garde aussi la session.
- Les [durées](securite.md#sessions-et-réglages-initiaux) et les révocations de sécurité s'appliquent.
- La déconnexion arrête la session et la reconnexion automatique de ce navigateur, même si Google reste connecté.
- Effacer les cookies empêche la reprise locale. Les autorisations côté serveur ne sont pas révoquées pour autant.
- La politique des sessions administrateur est distincte.

### Modifications sensibles et récupération

- Changer l'email, le mot de passe ou une méthode de connexion exige une nouvelle authentification explicite.
- Le nouvel email est confirmé et l'ancienne adresse est notifiée. Le changement n'est effectif qu'après confirmation.
- La récupération du mot de passe local utilise un lien limité dans le temps et à usage unique.
- La réponse publique ne révèle jamais l'existence d'un compte.
- Un compte exclusivement Google récupère son accès auprès de Google. Aucune méthode locale n'est créée automatiquement.
- Une suspension ou un changement de sécurité révoque les accès concernés.

Les parcours et paramètres sont fixés dans le [guide sécurité](securite.md#identité-session-et-récupération).

## Carte, liste et recherche

### Accès et fond de carte

- La carte et ses fonctions exigent une connexion.
- Après connexion, la carte présente les entreprises, offres d'emploi et événements localisables.
- La carte fonctionne sans géolocalisation.
- Le fond géographique est public. Il ne contient aucune donnée personnelle Emploi Go et ne donne accès ni aux comptes, ni aux démarches.

### Navigation et position

- Le jeune peut déplacer et zoomer la carte.
- Il peut choisir manuellement une commune ou un lieu, puis rechercher dans un rayon autour de ce lieu.
- « Autour de moi » demande la position uniquement à l'activation de l'action.
- La géolocalisation reste facultative. Son refus laisse la recherche manuelle disponible.
- La position précise n'est pas conservée durablement.
- La distance affichée indique sa signification, notamment lorsqu'elle est calculée à vol d'oiseau.

### Liste

- Une liste donne accès aux résultats et aux actions essentielles sans manipuler la carte.
- Une offre ou un événement sans lieu connu y reste visible avec la mention « lieu non précisé ». Aucune position n'est inventée.
- La carte et la liste restent cohérentes avec les critères actifs.

### Recherche et filtres

La recherche filtre par :

- secteur ;
- métier, famille de métiers, ou plusieurs métiers ;
- opportunités disponibles ;
- type de résultat (les trois types sont visibles par défaut) ;
- nom d'entreprise.

L'interface :

- montre les filtres actifs et le nombre de résultats ;
- permet de retirer un critère ou de réinitialiser la recherche ;
- explique une absence de résultats.

Les filtres de contrat, de salaire ou de télétravail exigent une source fiable contenant ces informations.

### Combinaison des critères

- Les valeurs d'un même filtre se combinent par OU. Deux métiers sélectionnés acceptent l'un ou l'autre.
- Les filtres différents se combinent par ET. Un secteur restreint ensuite ce résultat.
- Le rayon part du lieu choisi manuellement ou de la position autorisée. Il s'applique aux éléments localisés.
- Une offre ou un événement sans lieu connu reste dans la liste s'il correspond aux autres critères. Il porte la mention « lieu non précisé », sans distance inventée, et n'est pas présenté comme localisé dans le rayon.
- Les rayons proposés restent cohérents avec la couverture et les données disponibles.

### Points et aperçus

- Les points distinguent les entreprises, offres et événements sans dépendre uniquement de la couleur.
- Les points superposés sont regroupés.
- Un point ou une ligne ouvre un aperçu, puis les détails correspondants.
- Les actions essentielles sont utilisables au clavier et sur écran tactile.

## Entreprises, métiers et opportunités

Une fiche entreprise présente :

- le nom, l'adresse, le secteur, la localisation et les métiers associés, avec des noms de métiers compréhensibles (les codes ROME (répertoire des métiers de France Travail) internes ne sont pas affichés) ;
- séparément, les caractéristiques de l'entreprise et les recrutements réellement connus ;
- la source et la date de vérification, quand elles existent.

La présence sur la carte ne signifie pas que l'entreprise recrute.

Les points entreprise représentent des établissements physiques et leur adresse.

- Une entreprise peut posséder plusieurs établissements.
- Ces sites restent distincts. Leurs informations ne sont pas regroupées sur un point de siège unique.
- Les sources permettent de distinguer les établissements et leurs liens avec l'entreprise lorsqu'ils sont connus.

Les opportunités regroupent les offres d'emploi et les événements utiles : forums, job datings, campagnes, recrutements collectifs et actions de partenaires.

- Une opportunité peut exister sans rattachement à une entreprise.
- Elle présente son type, son lieu, sa date, sa source et l'accès aux détails.
- Un contenu ancien ou non vérifié n'est pas présenté comme encore ouvert.
- Une collecte automatique exige une vérification humaine avant publication.

## Favoris et démarches personnelles

Le jeune peut :

- ajouter ou retirer une entreprise de ses favoris ;
- déclarer « Je veux contacter cette entreprise » ;
- déclarer « J'ai postulé ».

Ces actions ont des significations distinctes. Une intention de contact n'envoie aucun message. Une candidature déclarée n'envoie aucun dossier à la place du jeune.

L'espace personnel rassemble les favoris, les entreprises à contacter et les candidatures déclarées avec leur date. Ouvrir une fiche ne crée pas d'historique de consultation. Le jeune accède uniquement à ses propres favoris, démarches et demandes de rappel.

## Administration

La V1 comprend deux profils applicatifs : jeune et administrateur. Il n'existe pas de compte conseiller. L'administration fait partie de la même application, avec des droits vérifiés par le backend.

| Fonction                                                    | Jeune                                       | Administrateur habilité                                                                                                            |
| ----------------------------------------------------------- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Entreprises, métiers associés, offres et événements publiés | Consulter.                                  | Créer, corriger, vérifier, publier et retirer les contenus.                                                                        |
| Profil, favoris, démarches et rappels                       | Gérer uniquement les siens.                 | Aucun accès aux favoris, démarches ou rappels nominatifs.                                                                          |
| Comptes jeunes                                              | Gérer son accès et demander sa suppression. | Rechercher les comptes, consulter les informations minimales de support, suspendre, réactiver et traiter une suppression encadrée. |
| Récupération d'accès                                        | Utiliser son parcours vérifié.              | Déclencher un lien au titulaire, sans voir ni choisir son mot de passe.                                                            |
| Droits d'administration                                     | Aucun octroi possible.                      | Attribution et retrait par une procédure habilitée, sans auto-promotion.                                                           |

Les informations de support se limitent à l'identifiant, au pseudo, à l'email, à sa vérification et au statut du compte.

- Une suspension révoque les sessions. La décision et son auteur sont tracés.
- L'administration ne permet pas de se connecter à la place d'un jeune.
- Elle n'affiche pas les personnes intéressées par un événement.

Les comptes administrateurs :

- sont nominatifs ;
- exigent une authentification multifacteur avant la production ;
- voient leurs habilitations retirées au départ de la personne et revues périodiquement.

La suppression définitive d'un contenu référencé ou d'un compte suit les règles de conservation (voir les [questions ouvertes](questions-ouvertes.md#protection-des-données-personnelles)). Un retrait ou un archivage préserve les références nécessaires. La supervision des contenus porte sur leur état et leur qualité.

## Emails et rappels

Les emails de vérification de compte et de récupération d'accès sont distincts des rappels facultatifs.

### Demande de rappel

- Pour un événement avec un début daté et horodaté, le jeune peut demander un seul rappel par email, vingt-quatre heures avant le début.
- Il peut annuler sa demande.
- Le rappel ne confirme aucune inscription ni participation auprès de l'organisateur.
- Après l'échéance, une nouvelle demande n'est plus proposée.
- Aucun rappel n'est envoyé pour un événement passé ou sans horaire exploitable.

### Évolution de l'événement

- Une annulation, un retrait de publication ou un changement de date annule ou recalcule le rappel.
- Le worker contrôle la pertinence du rappel avant chaque tentative.
- Après un retard ou une panne, il reprend tant que l'événement n'a pas commencé et que la demande et l'échéance restent valides.
- Dès le début de l'événement, il abandonne sans nouvel appel au fournisseur.
- Les tentatives sont espacées, bornées et conçues pour éviter les doublons.
- La limite porte sur le déclenchement de l'envoi. L'heure de réception d'un email accepté n'est pas garantie.

Le [guide worker](backend/worker.md) fixe les exigences d'intégration.

## Fonctions reportées

Ces fonctions ne font pas partie du minimum V1 :

- les notifications push ;
- les relances automatiques des démarches ;
- les comptes conseillers ;
- la publication sur les stores mobiles.

Le tutoriel détaillé accompagne un parcours stabilisé. Il ne conditionne pas l'accès à la carte. Une évolution du périmètre fait l'objet d'une décision produit documentée et de critères de recette.

## Livraison et recette

Les fonctionnalités, données fictives et essais sont livrés progressivement. Une version candidate (la version proposée pour validation en recette) est vérifiée sur son périmètre disponible et sur les comportements déjà livrés.

La recette refuse :

- un défaut qui empêche un parcours essentiel ;
- un accès non autorisé ;
- une perte ou une corruption de données ;
- une barrière d'accessibilité ou de performance qui bloque l'usage normal.

Un défaut mineur peut être accepté explicitement, avec une issue et une priorité de suivi. La décision identifie la version testée, selon le [guide d'exploitation](exploitation.md#livraison-et-recette).

L'architecture est décrite dans le [guide global](architecture.md). Les arbitrages restants figurent dans les [questions ouvertes](questions-ouvertes.md).

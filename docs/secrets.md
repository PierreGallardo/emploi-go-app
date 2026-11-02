# Secrets et coffre

Ce guide explique où conserver les secrets techniques, qui peut les utiliser, comment les remplacer et comment les récupérer après un incident.

## En tant que développeur

- En local, aucun secret partagé n'est nécessaire.
- `backend/.env.example` contient des valeurs locales (`local-development-only`).
- Resend et Google sont facultatifs. Les tester avec ses propres comptes : voir le [guide de développement backend](backend/developpement.md#configuration-locale).
- L'accès aux secrets de recette et de production passe par la ou le responsable du coffre.

Le reste de ce guide concerne l'exploitation.

## Coffre et responsabilités

Le coffre retenu pour le projet est Bitwarden Secrets Manager, hébergé séparément du serveur applicatif. Il contient notamment les mots de passe des services et les clés d'accès aux fournisseurs.

| Responsabilité                | Rôle                                                                              |
| ----------------------------- | --------------------------------------------------------------------------------- |
| Responsable du coffre         | Gérer les accès, leur retrait et les procédures de sauvegarde et de récupération. |
| Remplaçant du responsable     | Pouvoir assurer la récupération lorsque le responsable est indisponible.          |
| Personne utilisant le coffre  | Utiliser un compte nominatif protégé par une authentification multifacteur (MFA). |
| Programme utilisant le coffre | Utiliser un compte machine distinct, limité aux secrets nécessaires.              |

Le droit de déployer ne donne pas automatiquement le droit de lire les secrets. Les développeurs disposent des accès de développement et de recette nécessaires ; les autres accès sont accordés séparément.

Ne pas élargir les droits pour contourner une limite de l'offre. Le coffre ne protège pas à lui seul les serveurs ou les postes sur lesquels les secrets sont utilisés.

## Inventaire et séparation des droits

Cette section est l'inventaire des secrets. Une clé d'envoi d'emails pour la recette et une autre pour la production sont deux entrées distinctes.

Pour chaque secret, renseigner dans le coffre ou dans un inventaire associé :

- son nom, son identifiant dans le coffre et son utilité ;
- son environnement, le fournisseur qui le délivre et la personne responsable ;
- les permissions accordées et les personnes ou programmes autorisés à l'utiliser ;
- son expiration éventuelle et les liens vers les procédures de remplacement et de récupération.

Les valeurs restent dans le coffre. Un inventaire versionné dans Git contient seulement les informations descriptives, jamais les valeurs secrètes.

Séparer les accès de recette, de production, de supervision et de récupération. Dans Bitwarden, regrouper dans un même projet uniquement les secrets accessibles aux mêmes personnes et programmes. Vérifier les droits réellement obtenus : une permission plus restrictive ne retire pas un accès déjà accordé au projet. Voir les [permissions des projets Bitwarden](https://bitwarden.com/help/projects/).

### Application et base de données

| Variable ou secret                  | Rôle                                                                       | Processus                            | Stockage                                  | Rotation                                                                                     |
| ----------------------------------- | -------------------------------------------------------------------------- | ------------------------------------ | ----------------------------------------- | -------------------------------------------------------------------------------------------- |
| `DB_PASSWORD`                       | Mot de passe PostgreSQL. Un compte distinct par processus.                 | API, worker, migration               | Coffre, puis fichier sous `/run/secrets/` | Remplacement coordonné avec PostgreSQL, puis redémarrage des services.                       |
| PostgreSQL administration           | Opérations d'exploitation, distinctes des services et de la migration.     | Administrateur habilité              | Coffre                                    | Remplacement par un administrateur.                                                          |
| `REDIS_PASSWORD`                    | Accès Redis (compteurs de protection, cache).                              | API ; aucun accès worker             | Coffre, puis fichier sous `/run/secrets/` | Remplacement coordonné avec Redis, puis redémarrage de l'API.                                |
| `CONFIRMATION_TOKEN_KEY`            | Clé des liens et preuves d'authentification (Base64, 32 à 64 octets).      | API ; le worker n'en a pas besoin    | Coffre, puis fichier sous `/run/secrets/` | [Procédure ci-dessous](#clé-des-liens-de-confirmation) : redémarrage coordonné.              |
| `QUOTA_KEY`                         | Clé des hashes des compteurs de quota dans Redis (Base64, 32 à 64 octets). | API ; le worker n'en a pas besoin    | Coffre, puis fichier sous `/run/secrets/` | [Procédure ci-dessous](#clé-des-compteurs-de-quota) : remise à zéro des compteurs.           |
| `RESEND_API_KEY`                    | Envoi des emails, limité aux expéditeurs autorisés.                        | Services qui envoient des emails     | Coffre, puis fichier sous `/run/secrets/` | Deux clés possibles en parallèle : créer, basculer, désactiver l'ancienne.                   |
| `SIRENE_API_KEY`                    | Lecture de l'API Sirene de l'INSEE pour l'import des établissements.       | Worker ; l'API n'en a pas besoin     | Coffre, puis fichier sous `/run/secrets/` | Clé du compte sur portail-api.insee.fr : en créer une autre, basculer, supprimer l'ancienne. |
| `GOOGLE_CLIENT_SECRET`              | Secret du client OAuth Google. L'identifiant client est public.            | API                                  | Coffre, puis fichier sous `/run/secrets/` | Secret propre à l'environnement, à remplacer dans la console Google.                         |
| Clé de chiffrement MFA/TOTP (admin) | Chiffrer les secrets TOTP des administrateurs, hors de PostgreSQL.         | Service de vérification MFA de l'API | Coffre, sauvegardée séparément de la base | Remplacement qui permet de relire les secrets TOTP déjà chiffrés.                            |
| `DEV_AUTH_PASSWORD`                 | Mot de passe du compte fictif de développement (20 à 128 caractères).      | Profil local uniquement              | Fichier local `backend/.env`              | Aucune : jamais utilisé en recette ni en production.                                         |

Règles communes :

- Aucun de ces secrets n'utilise un préfixe `VITE_` : les variables `VITE_*` sont publiques.
- En production, l'API et le worker n'ont pas le droit de modifier le schéma PostgreSQL, même par un groupe de permissions auquel ils appartiennent. Voir le [guide données](backend/donnees.md#postgresql-et-migrations) et l'[exploitation](exploitation.md#configuration-et-migrations).
- Les hashes des mots de passe des jeunes restent dans la base applicative.
- Le mécanisme de session ne demande pas de clé de signature JWT.
- Ajouter à l'inventaire les secrets d'un fournisseur ou d'un webhook dès que l'intégration les utilise.

### Images et sauvegardes

Un bucket est un espace de stockage de fichiers. Les images de l'application et les sauvegardes utilisent des accès distincts, chez deux fournisseurs : Cloudflare R2 pour les images et la copie A, OVH Object Storage pour la copie B.

| Secret ou accès                      | Utilisateur autorisé                        | Limite                                                                                                                                     |
| ------------------------------------ | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| R2 images                            | Service chargé des images                   | Accès uniquement au bucket des images, avec les opérations nécessaires.                                                                    |
| A : sauvegardes PostgreSQL (R2)      | pgBackRest                                  | Écriture et suppression selon la conservation prévue. Accès de lecture distinct pour la récupération.                                      |
| B : clé d'écriture des exports (OVH) | Procédure d'export sur le serveur           | Écriture seule : ni suppression ni levée du lock Object Lock. Le serveur ne peut pas relire B.                                             |
| B : lecture des exports (OVH)        | Procédure de restauration                   | Accès distinct de la clé d'écriture, conservé hors du serveur applicatif.                                                                  |
| Clé publique `age`                   | Procédure d'export sur le serveur           | Chiffrer les exports. Elle ne permet pas de les déchiffrer.                                                                                |
| Clé privée `age`                     | Procédure de restauration                   | Jamais sur le serveur. Conservée dans le coffre et récupérable sans le coffre ni le serveur, par exemple dans un export chiffré du coffre. |
| Chiffrement de A                     | Procédures de sauvegarde et de restauration | Phrase de chiffrement pgBackRest récupérable sans le serveur applicatif.                                                                   |

Les usages de A et B sont décrits dans les [procédures de sauvegarde](exploitation.md#sauvegardes-et-restauration).

### Réseau, supervision et alertes

| Secret ou accès                    | Utilisateur autorisé        | Utilité et limite                                                                                           |
| ---------------------------------- | --------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Token du tunnel Cloudflare         | `cloudflared`               | Ouvrir le tunnel de son environnement. Un token par environnement, lu dans un fichier sous `/run/secrets/`. |
| Envoi de mesures et logs           | Outils de collecte          | Envoyer les données, sans droit de lecture ou d'administration.                                             |
| Lecture des mesures et logs        | Grafana                     | Lire uniquement les sources autorisées ; administration séparée.                                            |
| Clé `secret_key` de Grafana        | Grafana                     | Protéger les secrets enregistrés dans sa base. Conserver cette clé pour la restauration.                    |
| Identifiants SMTP                  | Supervision                 | Envoyer les alertes par email.                                                                              |
| Secret du bot Nextcloud Talk       | Service d'envoi des alertes | Publier dans le salon prévu.                                                                                |
| Lecture du registre d'images privé | Procédure de déploiement    | Télécharger les images nécessaires, sans droit de publication.                                              |

La [clé de chiffrement Grafana](https://grafana.com/docs/grafana/latest/setup-grafana/configure-security/configure-database-encryption/) protège notamment les identifiants enregistrés pour accéder aux sources de données. La sauvegarde de la base doit permettre de retrouver aussi cette clé.

Les clés privées SSH et WireGuard personnelles restent sur l'appareil de leur détenteur. Installer uniquement les éléments publics nécessaires sur les serveurs. Prévoir des clés distinctes par personne et appareil pour pouvoir retirer un accès sans couper les autres. Les comptes OVH et Cloudflare ont leurs propres permissions et sont protégés par MFA.

## Fournir les secrets aux services

Pour le déploiement, appliquer le parcours suivant :

1. Utiliser le [CLI Bitwarden `bws`](https://bitwarden.com/help/secrets-manager-cli/) avec un compte machine autorisé à lire les seuls secrets nécessaires.
2. Récupérer les valeurs avant de lancer Compose. Arrêter la procédure si un secret obligatoire ne peut pas être obtenu.
3. Écrire les valeurs dans des fichiers protégés sur le serveur, séparés par environnement et par service. Définir leur propriétaire et leurs permissions, puis supprimer les copies temporaires inutiles.
4. Monter chaque fichier sous `/run/secrets/` uniquement dans les services autorisés à le lire.
5. Configurer et vérifier la lecture des fichiers par chaque service.

Le token permettant à `bws` d'ouvrir le coffre est lui-même un secret. Protéger sa copie de démarrage et son moyen de récupération hors du seul coffre qu'il ouvre. Prévoir sa révocation.

Ne jamais afficher les valeurs dans le terminal, les logs, les traces de débogage, l'historique ou les arguments de commande.

### Lecture des fichiers

| Composant                 | Point à vérifier                                                                                                                          |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Docker Compose            | Le montage donne accès au fichier du serveur. Il ne chiffre pas cette copie et ne la protège pas d'un administrateur du serveur.          |
| Spring Boot               | L'import `configtree:` permet de lire des fichiers de configuration. Les noms des fichiers doivent correspondre aux propriétés attendues. |
| Images utilisant `*_FILE` | Vérifier que l'image comprend cette variable. Ce nom n'est pas une fonction universelle de Docker.                                        |
| PostgreSQL                | `POSTGRES_PASSWORD_FILE` sert à initialiser une base vide. Modifier le fichier ne change pas le mot de passe dans une base existante.     |

Références : [secrets Compose](https://docs.docker.com/compose/how-tos/use-secrets/), [configuration Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.configtree), [image PostgreSQL](https://github.com/docker-library/docs/blob/master/postgres/README.md#docker-secrets).

Les fichiers `.env` réels, clés privées et exports restent hors Git. Les exemples contiennent uniquement des valeurs fictives. Aucun secret ne doit être intégré à une image applicative ou au frontend : les variables `VITE_*` et les configurations envoyées au navigateur sont publiques.

La configuration du poste de développement est décrite dans le [guide backend](backend/developpement.md#configuration-locale).

## Remplacer ou désactiver un secret

Une rotation remplace un secret. Une révocation rend l'ancien secret inutilisable auprès du service qui l'a délivré.

### Remplacement prévu

Le parcours dépend du service concerné :

| Possibilité du service                                 | Méthode                                                                                                         |
| ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| Deux clés peuvent fonctionner en même temps            | Créer la nouvelle clé, faire basculer les programmes, vérifier leur fonctionnement, puis désactiver l'ancienne. |
| Un seul mot de passe ou une seule clé peut fonctionner | Coordonner le changement et la mise à jour des programmes. Prévoir l'interruption éventuelle.                   |

Dans les deux cas :

1. Enregistrer la nouvelle valeur dans le coffre et actualiser l'inventaire.
2. Mettre à jour les fichiers protégés et recharger ou redémarrer les services concernés.
3. Vérifier que chaque service utilise bien la nouvelle valeur.
4. Retirer les anciennes copies et actualiser les moyens de récupération.

Modifier un fichier ne garantit pas qu'un programme l'a relu. La procédure doit préciser les redémarrages nécessaires et la marche à suivre si certains services échouent pendant le changement. Le remplacement du token du tunnel suit la même procédure : redémarrer `cloudflared` pour qu'il lise le nouveau token. Voir le [tunnel Cloudflare](exploitation.md#tunnel-cloudflare).

### Secret exposé

- Désactiver rapidement le secret chez le fournisseur qui l'a délivré.
- Traiter l'incident, conserver les preuves nécessaires et fournir de nouveaux accès aux services concernés.
- Ne pas réactiver un secret compromis pour rétablir le service.

Supprimer une valeur du coffre ou restaurer une ancienne sauvegarde ne désactive pas cette valeur chez le fournisseur.

## Clé des liens de confirmation

`CONFIRMATION_TOKEN_KEY` est décrite dans l'[inventaire](#application-et-base-de-données). Elle doit rester stable entre les redémarrages et identique sur toutes les instances API.

Pour la remplacer :

1. Générer une nouvelle clé Base64 de 32 à 64 octets aléatoires.
2. L'enregistrer dans le coffre et mettre à jour le fichier protégé.
3. Redémarrer toutes les instances API en même temps.
4. Vérifier qu'un nouveau lien d'inscription fonctionne.

Conséquences : les liens en attente sont invalidés. Un renvoi crée un nouveau lien.

## Clé des compteurs de quota

`QUOTA_KEY` est décrite dans l'[inventaire](#application-et-base-de-données). Elle sert à calculer les clés des compteurs de quota dans Redis ([règle](backend/donnees.md#forme-des-quotas)). Elle est distincte de `CONFIRMATION_TOKEN_KEY` : la compromission ou le remplacement de l'une ne touche pas l'autre. Elle doit rester stable entre les redémarrages et identique sur toutes les instances API. L'API refuse de démarrer sans elle hors du profil `local`, qui en fournit une valeur de développement.

Pour la remplacer :

1. Générer une nouvelle clé Base64 de 32 à 64 octets aléatoires.
2. L'enregistrer dans le coffre et mettre à jour le fichier protégé.
3. Redémarrer toutes les instances API en même temps, hors des heures de pointe.

Conséquences : tous les compteurs de quota repartent de zéro, une seule fois. Les échecs de connexion et les délais en cours sont oubliés, et aucune perte de compteurs n'est signalée (le témoin existe toujours). Les anciennes clés Redis ne sont plus lues et expirent d'elles-mêmes. Pendant un déploiement progressif, les instances comptent dans des clés différentes et les limites sont affaiblies le temps du déploiement. Aucune autre donnée n'est touchée. Il en va de même à la première mise en service de la clé : les compteurs posés par une version sans clé ne sont plus lus et expirent d'eux-mêmes (au plus la durée de leur fenêtre). Les sauvegardes et fichiers AOF de Redis faits avant la mise en service gardent ces anciennes clés jusqu'à leur réécriture ou la fin de leur conservation : les traiter comme des données personnelles.

## Retirer les accès lors d'un départ

Lors d'un départ ou d'un changement de fonction :

1. Retirer les permissions concernées dans le coffre et chez les fournisseurs.
2. Retirer les accès SSH et WireGuard concernés et désactiver les tokens personnels.
3. Examiner les secrets que la personne pouvait connaître, y compris les tokens machine, et remplacer ceux qui doivent l'être.
4. Vérifier le fonctionnement des services et actualiser les copies de récupération.

Avant le départ d'un responsable unique, transférer la responsabilité et vérifier que son successeur peut réellement accéder aux moyens de récupération.

## Sauvegarde et reprise du coffre

### Quel moyen utiliser ?

| Incident                       | Moyen de récupération                                                                                   |
| ------------------------------ | ------------------------------------------------------------------------------------------------------- |
| Serveur applicatif perdu       | Coffre accessible et procédure permettant de réinstaller les services.                                  |
| Second facteur Bitwarden perdu | Kit de récupération du compte humain, dont son mot de passe maître et son code de récupération MFA.     |
| Bitwarden indisponible         | Export chiffré conservé ailleurs, avec sa clé de déchiffrement accessible.                              |
| Secrets compromis              | Nouveaux secrets chez les fournisseurs et désactivation des anciens. Restaurer une copie ne suffit pas. |

Les sauvegardes PostgreSQL ne remplacent pas celles du coffre.

### Retrouver l'accès au compte Bitwarden

Le kit de récupération du compte contient :

- l'adresse du compte et l'adresse du service Bitwarden utilisé, notamment sa région ;
- le mot de passe maître ;
- le code de récupération MFA.

Conserver la copie papier sous enveloppe fermée dans un lieu protégé accessible uniquement aux personnes autorisées. Le code de récupération MFA ne remplace pas le mot de passe maître. Son utilisation désactive les méthodes MFA et génère un nouveau code : réactiver la MFA et mettre à jour le kit après utilisation. Voir la [récupération Bitwarden](https://bitwarden.com/help/two-step-recovery-code/).

Ce kit permet de récupérer l'accès au compte lorsque Bitwarden fonctionne. En cas de panne du service, utiliser la copie chiffrée des secrets.

### Sauvegarder les secrets

Depuis un poste protégé, une personne habilitée :

1. Exporte les données Secrets Manager.
2. Chiffre l'export avant de le conserver et vérifie son déchiffrement.
3. Supprime les copies de travail en clair.
4. Conserve une copie hors du serveur applicatif et du poste principal, puis une seconde dans un autre lieu protégé.

La phrase de chiffrement de l'export est distincte du mot de passe maître et conservée séparément. Elle doit rester accessible si le coffre ou le serveur est perdu.

Effectuer un export après les changements importants et les remplacements de secrets. Vérifier au moins chaque mois que la sauvegarde est à jour. La procédure précise la fréquence des exports, les copies à conserver et les personnes responsables. Une sauvegarde automatisée utilise ses propres permissions, sans élargir celles du compte de déploiement.

### Reconstituer les accès et tester la reprise

L'[export Secrets Manager](https://bitwarden.com/help/export-secrets-data/) contient les projets et les secrets, mais pas les comptes machine ni leurs tokens d'accès. Conserver une description des permissions et des affectations pour pouvoir les recréer. Après un import, vérifier les identifiants utilisés par les scripts et les remplacer si nécessaire.

Tester la récupération sans le coffre d'origine ni l'ancien serveur :

- déchiffrer l'export et retrouver les secrets ;
- recréer les permissions, comptes machine et tokens nécessaires ;
- vérifier les références utilisées par les scripts et le fonctionnement des services ;
- supprimer les copies temporaires après l'essai.

Utiliser des valeurs fictives pour les exercices ordinaires. Un essai avec un export réel reste réservé aux personnes habilitées, dans un environnement protégé.

La procédure de secours indique l'outil de chiffrement, les lieux de conservation, les protections et les personnes autorisées à intervenir, avec leur remplaçant. Les clés et accès nécessaires aux sauvegardes A et B, dont la clé privée `age`, doivent rester récupérables sans le coffre ni le serveur applicatif, selon le [guide de restauration](exploitation.md#sauvegardes-et-restauration).

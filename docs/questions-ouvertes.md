# Questions ouvertes

Ce registre liste les informations et les arbitrages à obtenir : métier, périmètre, responsabilités, budget, protection des données et supervision. Les décisions techniques retenues figurent dans les guides [architecture](architecture.md), [exploitation](exploitation.md), [sécurité](securite.md) et [secrets](secrets.md).

Les décisions sont suivies dans les issues GitHub :

- Protection des données personnelles : [#41 « Conformité RGPD »](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/41).
- Plan d'incident, objectifs de sauvegarde (RPO et RTO) et restauration : [#42](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/42).

Chaque ligne désigne une fonction à attribuer ou une information à obtenir. Une réponse obtenue est intégrée dans son guide de référence, puis retirée de ce registre.

## Validation du périmètre

Le [périmètre V1](perimetre-v1.md) est la référence d'implémentation. Le responsable du projet doit encore confirmer ces écarts avec le cahier des charges :

- pseudo unique ;
- avatar composé ;
- inscription et connexion Google ;
- offres sur la carte ;
- absence d'historique des simples consultations.

L'absence d'historique demande une validation explicite, car le cahier des charges demande de retrouver les entreprises consultées.

Le résultat attendu est une acceptation tracée du périmètre, ou une évolution documentée des besoins et des critères de recette.

## Données métier et recherche

Le territoire initial Dijon Métropole et la chaîne PostgreSQL → API → carte sont fixés. Ces informations sont nécessaires pour les données et les comportements métier :

| Sujet                     | Information ou arbitrage attendu                                                                                                                                                                |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Sources                   | Fournisseurs des entreprises, offres et événements, droits d'utilisation, champs disponibles et identifiants stables.                                                                           |
| Informations métier       | Informations disponibles sur les établissements et leurs adresses, leurs liens avec les entreprises juridiques, les métiers et le recrutement ; identifiants permettant de relier ces contenus. |
| Actualisation             | Mode d'alimentation, fréquence adaptée à chaque source et personnes habilitées à corriger les contenus. Une fréquence de vingt-quatre heures n'est pas arrêtée.                                 |
| Fin de vie des contenus   | Fermeture, retrait, archivage, expiration des opportunités et maintien des références de favoris ou démarches.                                                                                  |
| Coordonnées               | Disponibilité et fiabilité des coordonnées dans les sources métier, et besoins de compléments pour les adresses sans localisation.                                                              |
| Recherche de lieux        | Référentiel des communes ou lieux, couverture, droits d'utilisation et informations disponibles. Le fond cartographique ne remplit pas ce besoin à lui seul.                                    |
| Couverture cartographique | Zone utile au démarrage et besoins de navigation au-delà de Dijon, en cohérence avec le territoire des données métier.                                                                          |

Protomaps/PMTiles et MapLibre sont retenus. Le référentiel de recherche des communes ou lieux reste à choisir. La diffusion et la préparation des lots figurent dans l'[architecture](architecture.md#cartographie-et-fichiers) et l'[exploitation](exploitation.md#livraison-et-recette).

## Protection des données personnelles

Les décisions sont suivies dans l'issue [#41](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/41) : organisme responsable du traitement, finalités, durées de conservation, droits des personnes et analyse d'impact.

Les règles de minimisation, de géolocalisation facultative, de cloisonnement et de diagnostic filtré figurent dans la [sécurité](securite.md). Elles ne valident pas à elles seules le traitement sur le plan juridique.

## Supervision et canaux d'alerte

La supervision extérieure, le diagnostic Faro filtré, les deux canaux Nextcloud/email, les catégories de lecteurs et les durées de conservation sont fixés. Ces informations restent à obtenir :

- L'accès au Nextcloud de l'organisation et la disponibilité de Talk pour les alertes.
- Le fournisseur SMTP de la supervision et les dépendances réelles des deux canaux d'alerte.
- Le salon, ses membres, les destinataires et les lecteurs nominatifs des sources de diagnostic et de sécurité.
- Le fournisseur de le health check externe et le canal d'alerte de healthchecks.io.

Les décisions d'accès, de collecte et de conservation relèvent de l'[exploitation](exploitation.md#supervision-et-logs), avec les habilitations à attribuer ci-dessous.

## Procédures d'exploitation

Les [règles d'exploitation](exploitation.md) fixent le réseau, la livraison et les sauvegardes. Ces détails demandent encore un choix ou une validation :

- **Tunnel Cloudflare** : nom du domaine de la recette (distinct de la production), alerte en cas de coupure du tunnel et récupération du token.
- **Mises à jour** : jour et heure du redémarrage mensuel du serveur et de la reconstruction mensuelle des images.
- **Accès de secours OVH** : offre utilisée, console ou mode de secours disponible, procédure applicable et essai sans VPN ni système installé opérationnel.
- **Fichiers cartographiques** : machine exécutant les scripts de préparation et cadence de génération. Livrer en production les mêmes fichiers validés en test.
- **Copie B** : offre et région OVH compatibles avec Object Lock, essai sur un bucket jetable (dépôt, réécriture, suppression, cycle de vie), puis essai de `pg_restore`.
- **Reprise PostgreSQL depuis la copie B** : suivie dans l'issue [#42](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/42), avec le plan d'incident et les objectifs de sauvegarde.

## Équipe, habilitations et continuité

Une même personne peut cumuler plusieurs fonctions. Les attributions nominatives restent à faire.

| Fonction                     | Attribution à obtenir                                                                                                                           |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Produit et recette           | Arbitrages de périmètre/priorité, critères et acceptation fonctionnelle d'une candidate.                                                        |
| Développement et maintenance | Organisation des contributions, revues, mises à jour et qualité.                                                                                |
| Données et contenus          | Sources, validation, publication et actualisation.                                                                                              |
| Administration et livraison  | Serveurs, migrations, déploiements de test et production.                                                                                       |
| Habilitations et secrets     | Octroi/retrait des accès, administration du coffre, rotations et garde du secours.                                                              |
| Supervision et incidents     | Destinataires des alertes, intervention, restauration et retour du service.                                                                     |
| Protection des données       | Interlocuteur compétent pour les obligations et les incidents (voir [#41](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/41)). |
| Budget et renouvellements    | Autorisation des dépenses et suivi des contrats, domaines et échéances.                                                                         |
| Transmission                 | Documentation, remise des accès et reprise en cas de départ ou d'indisponibilité.                                                               |

Une seule personne est envisagée pour la responsabilité globale du coffre, sans suppléant. Désigner les gardiens habilités des moyens de récupération et leur suppléance. Les procédures correspondantes figurent dans les [secrets](secrets.md#sauvegarde-et-reprise-du-coffre).

## Budget et offres de production

Le budget et les hypothèses de charge restent à valider. Recueillir les coûts des serveurs applicatif et de supervision, du domaine, du coffre, du registre/CI, du stockage et des emails, ainsi que le temps de maintenance et les renouvellements.

Distinguer le public potentiel, les comptes, l'activité, la simultanéité et les pics d'envoi. Ils déterminent les quotas, le dimensionnement et les mesures utiles. Les fournisseurs retenus ne garantissent pas une production gratuite.

Bitwarden Secrets Manager Free est retenu pour les premiers essais. Choisir l'offre de production selon les humains et machines habilités, les quotas, les logs nécessaires, la récupération et le budget. L'offre GitHub pourra être réexaminée si des protections imposées deviennent nécessaires ; l'éligibilité éventuelle à un programme associatif reste à vérifier avec l'organisme identifié.

## Architecture backend

Ce sujet doit être tranché avant le premier domaine qui réagit au fait d'un autre.

| Sujet                                       | Information ou arbitrage attendu                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Communication entre domaines par événements | Quand un domaine doit réagir à un fait d'un autre, un appel direct dans les deux sens crée un cycle interdit. Par exemple, supprimer les données liées à un compte supprimé. Décider si les domaines échangent des événements, comment ils sont publiés et traités, et quelle garantie de livraison et de retry est exigée. Lié à [#41](https://github.com/Mission-locale-de-dijon/emploi-go-app/issues/41) pour la suppression des données personnelles. |

## Arbitrages à trancher

Ces deux arbitrages viennent de l'audit technique du 15/10/2026. Aucun n'est décidé : les guides ne changent pas avant la décision.

### Offre Bitwarden et suppléant du gardien du coffre (constat A2-12)

Le [guide des secrets](secrets.md#coffre-et-responsabilités) exige un projet par groupe d'accès, un compte machine par programme et un suppléant du responsable du coffre. L'[offre gratuite](https://bitwarden.com/help/secrets-manager-plans/) limite à 2 utilisateurs, 3 projets et 3 comptes machine.

| Option                                                                                                | Coût                                                                 | Effet sur la documentation                                                                                                                                      |
| ----------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Rester sur l'offre gratuite, sans suppléant                                                           | Aucun.                                                               | Aucun changement. Les projets (recette, production, supervision, récupération) et les comptes machine doivent tenir dans les limites. Un seul gardien subsiste. |
| Offre Teams, suppléant désigné parmi les utilisateurs                                                 | Prix par utilisateur et par mois, à vérifier sur la page des tarifs. | [Secrets](secrets.md#coffre-et-responsabilités) et [budget](#budget-et-offres-de-production) : offre retenue, suppléant nommé.                                  |
| Offre gratuite, suppléant hors coffre : second kit de récupération confié à une personne de confiance | Aucun coût de licence.                                               | [Secrets](secrets.md#sauvegarde-et-reprise-du-coffre) : procédure du second kit et personne désignée.                                                           |

### Supervision allégée (constat A2-14)

La supervision décrite dans l'[exploitation](exploitation.md#supervision-et-logs) comprend un gateway Loki multi-tenant, des organisations Grafana séparées, le diagnostic navigateur Faro public et un adaptateur Talk. Pour 1 à 3 personnes, son entretien est lourd.

| Option                                                                                                                                                                                                     | Coût                                                                                                                               | Effet sur la documentation                                                                                                                                                                                                                                                             |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Garder la supervision actuelle                                                                                                                                                                             | Temps d'entretien élevé : gateway, organisations, adaptateur et ingestion publique à exploiter et à tester.                        | Aucun changement.                                                                                                                                                                                                                                                                      |
| Supervision allégée : Prometheus avec exporters (hôte, PostgreSQL, Redis) et âge des sauvegardes, Loki en un seul binaire, alertes natives par email et par un service de notification tiers, Faro reporté | Moins de temps d'entretien. Perte du diagnostic navigateur et de la séparation des droits de lecture entre diagnostic et sécurité. | [Exploitation](exploitation.md#supervision-et-logs) : sections « Séparer diagnostic et sécurité » et « Envoyer et tester les alertes ». [Architecture](architecture.md#supervision-extérieure), [sécurité](securite.md#diagnostic-et-incidents) et questions de supervision ci-dessus. |

Les exigences de conservation et de confidentialité des logs de sécurité s'appliquent quelle que soit l'option.

# Organisation des dépôts

Cette page définit la répartition du code, des configurations et de la documentation entre l'application et son déploiement.

## Responsabilités

| Dépôt             | Responsabilité                                                                                   |
| ----------------- | ------------------------------------------------------------------------------------------------ |
| `emploi-go-app`   | Développer, tester et construire le frontend et le backend.                                      |
| `emploi-go-infra` | Configurer les serveurs et les environnements, déployer les versions et gérer leur exploitation. |

Le frontend et le backend partagent le même dépôt applicatif. Les règles de contribution, de branches et de maintenance sont définies dans [CONTRIBUTING.md](../CONTRIBUTING.md).

La documentation de déploiement est conservée ici dans le [guide d'exploitation](exploitation.md), jusqu'à son transfert dans le dépôt dédié `emploi-go-infra`.

## Où placer les fichiers ?

| Élément                                                            | Emplacement                                                                                     |
| ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------- |
| Code frontend et backend, tests et données de test fictives        | `emploi-go-app`                                                                                 |
| Migrations SQL, contrat OpenAPI et types générés                   | `emploi-go-app`                                                                                 |
| Scripts et Compose pour le développement local                     | `emploi-go-app`                                                                                 |
| Fichiers de construction des images et workflows applicatifs       | `emploi-go-app`                                                                                 |
| Architecture du code, conventions et guides de développement       | `emploi-go-app`                                                                                 |
| Compose et configurations des environnements test et production    | `emploi-go-infra`                                                                               |
| Références des images et des fichiers cartographiques sélectionnés | `emploi-go-infra`                                                                               |
| Configuration des serveurs, du réseau et de la supervision         | `emploi-go-infra`                                                                               |
| Procédures de déploiement, sauvegarde et restauration              | `emploi-go-infra`                                                                               |
| Vrais secrets, données personnelles, sauvegardes et logs           | Hors des dépôts Git ; voir les guides [secrets](secrets.md) et [exploitation](exploitation.md). |

Un fichier Docker ou Compose est placé selon son usage : construire l'application ou développer localement relève du dépôt applicatif ; configurer un environnement déployé relève du dépôt de déploiement.

## Lien entre les dépôts

- Construire les images depuis un commit identifié du dépôt applicatif et les publier dans un registre.
- Référencer dans `emploi-go-infra` les images choisies pour chaque environnement par leur **digest**, l'identifiant exact de leur contenu.

Le dépôt de déploiement référence les versions de l'application ; il ne recopie pas son code et ne l'intègre pas comme sous-module Git. Modifier le code ou fusionner une PR applicative ne sélectionne pas automatiquement une version pour la production.

## Accès et revue

Les accès sont distincts : contribuer à l'application n'accorde pas de droit de modification du déploiement ni d'accès aux serveurs ou aux secrets de production.

Sur les dépôts privés du projet avec GitHub Free, les [protections de branche](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) et les [rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets) ne sont pas disponibles. GitHub ne peut donc pas imposer une PR, une approbation indépendante ou la réussite des contrôles CI avant fusion.

Le respect de ces règles repose sur les membres de l'équipe. Chaque membre suit les [règles de contribution](../CONTRIBUTING.md), sans push direct vers `main` ou une branche de maintenance. La personne qui fusionne vérifie l'approbation indépendante et la réussite des contrôles CI.

## Guides associés

| Besoin                                                      | Guide                                                                      |
| ----------------------------------------------------------- | -------------------------------------------------------------------------- |
| Proposer un changement ou corriger une ancienne version     | [Contribution](../CONTRIBUTING.md)                                         |
| Comprendre les vérifications automatiques                   | [GitHub Actions](ci.md)                                                    |
| Construire, identifier et sélectionner une version à livrer | [Livraison et recette](exploitation.md#livraison-et-recette)               |
| Sauvegarder les données et restaurer les services           | [Sauvegardes et restauration](exploitation.md#sauvegardes-et-restauration) |

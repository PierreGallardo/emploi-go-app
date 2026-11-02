Remplir chaque rubrique. Écrire « Sans objet » plutôt que supprimer une rubrique. Ne pas inclure de secrets ni de données personnelles.

Le titre de la PR suit le format `type(zone): description` (voir CONTRIBUTING.md). Exemple : `feat(recherche): afficher un message quand aucune entreprise ne correspond`.

## Objectif et issue concernée

Décrire le problème ou le besoin, et le résultat apporté. Ajouter un exemple avant/après si utile.

Écrire `Closes #numero` si cette PR termine tout le travail de l'issue. Sinon, écrire `Liée à #numero` et préciser ce que cette PR couvre et ce qui reste à faire.

## Changements

Résumer les changements et expliquer les choix utiles à la revue. Signaler les points qui demandent une attention particulière et les limites connues. Pour une Draft PR, préciser ce qui reste à faire et le retour attendu.

## Dépendances entre PR

Lier les PR non fusionnées nécessaires à celle-ci et expliquer pourquoi. Ne pas lister les PR déjà intégrées à la branche cible.

## Vérifications effectuées

Lister les tests, les commandes et les vérifications manuelles, avec leur résultat. Pour une vérification manuelle, indiquer les conditions, les étapes, le résultat attendu et le résultat constaté, pour que la personne qui relit puisse la reproduire. Si une vérification importante n'a pas été faite, expliquer pourquoi.

## Impacts

Indiquer les effets sur l'API, les données, la configuration, les dépendances ou les droits d'accès. Préciser les changements incompatibles et les étapes nécessaires pour utiliser le changement.

## Captures d'interface

Joindre des captures avant/après pour un changement visuel. Pour une interaction, une courte vidéo est souvent plus claire.

## Checklist

- [ ] Documentation mise à jour
- [ ] Contrat API régénéré si les routes changent
- [ ] .env.example à jour si une variable est ajoutée
- [ ] Tests ajoutés ou mis à jour

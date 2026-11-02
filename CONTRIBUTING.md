# Contribuer à Emploi Go

Ce guide explique comment proposer, vérifier et faire fusionner un changement dans ce dépôt.

**Parcours :** issue → branche et commits → vérifications locales → PR → CI et revue → fusion.

## Préparer une tâche

Chercher une issue qui couvre déjà le travail. Si aucune ne convient, en créer une avec le modèle [Tâche](.github/ISSUE_TEMPLATE/tache.yml) ou [Bug](.github/ISSUE_TEMPLATE/bug.yml). Donner un titre qui résume le résultat souhaité ou le problème observé, puis préciser :

- l'objectif ou le problème à corriger ;
- le contexte et les limites du changement ;
- les résultats à vérifier pour considérer le travail terminé.

Citer les décisions ou les guides utiles à la compréhension de la tâche.

Une issue peut être traitée par plusieurs PR. Ne la fermer que lorsque tout le travail nécessaire est terminé. Utiliser `Closes #numero` uniquement si la PR termine l'issue ; sinon, écrire `Liée à #numero`.

## Branches et commits

Créer une branche de travail depuis un `main` à jour. Lui donner un nom `type/<numero>-description`, avec le numéro de l'issue. Le préfixe reprend un des types de commit ci-dessous :

- `feat/<numero>-recherche-entreprises`
- `fix/<numero>-erreur-connexion`
- `docs/<numero>-guide-developpement`
- `chore/<numero>-mettre-a-jour-dependances`

Les préfixes `maintenance/` et `hotfix/` sont réservés aux [anciennes versions](#corriger-une-ancienne-version).

Chaque commit représente une étape cohérente. Une PR peut contenir plusieurs commits.

### Convention de commit

Les messages suivent les Conventional Commits, en français : `type(zone): description`.

- `feat` : une nouvelle fonctionnalité.
- `fix` : une correction de bug.
- `docs` : un changement de documentation.
- `test` : un ajout ou une modification de tests.
- `refactor` : une réorganisation du code sans changement de comportement.
- `chore` : de la maintenance, comme une mise à jour de dépendances.
- `ci` : un changement des automatisations GitHub Actions.

La zone est facultative. Elle indique la partie concernée, par exemple `feat(carte): ajouter le filtre par secteur`.

La fusion se fait en **Squash and merge**. Le **titre de la PR** devient donc le commit sur `main` et suit ce format. Une vérification automatique en CI contrôle ce titre.

Tous les changements passent par une PR. Ne pas pousser directement ni forcer un push vers `main` ou une branche de maintenance.

### Corriger une ancienne version

Utiliser `main` lorsque son contenu peut être livré avec le correctif. Pour corriger une ancienne version sans inclure les nouveautés de `main` :

1. Utiliser la branche `maintenance/<série>`. Si elle n'existe pas, la créer depuis le tag de la version en service concernée.
2. Créer une branche de travail `hotfix/<numero>-<description>` depuis cette maintenance.
3. Proposer la PR vers `maintenance/<série>` et appliquer les mêmes règles de vérification et de revue.
4. Vérifier si la correction est aussi nécessaire dans `main`. Si oui :
   - Créer une branche de travail depuis `main`.
   - Y reprendre le correctif de maintenance avec `cherry-pick`. L'adapter si le code a changé.
   - Ouvrir une PR de cette branche vers `main`.

Si le problème est déjà résolu dans `main` ou ne le concerne plus, aucun report n'est nécessaire.

Conserver la branche de maintenance tant que la série reçoit des correctifs. La recette et la livraison sont décrites dans le [guide d'exploitation](docs/exploitation.md#données-de-recette-et-correctifs-danciennes-versions).

## Configuration et dépendances

- Inclure les fichiers de déclaration et de verrouillage modifiés lors d'un changement de dépendance.
- Mettre à jour le fichier d'exemple et la documentation lorsqu'une variable d'environnement change. Utiliser des valeurs fictives dans les exemples.

## Secrets et données personnelles

- Garder les vrais fichiers `.env`, les secrets et les données personnelles hors de Git, des issues, des PR et des logs.
- Utiliser des données fictives pour les exemples et les tests. Retirer les informations sensibles des captures et des rapports.
- Utiliser des identifiants de développement distincts de ceux de production.

Les guides [sécurité](docs/securite.md) et [gestion des secrets](docs/secrets.md) détaillent ces règles.

## Contrat API et types générés

Le contrat OpenAPI est généré depuis le backend ; les types frontend sont générés depuis ce contrat.

Si le changement modifie le contrat API, inclure dans la même PR :

- le code concerné ;
- les fichiers générés qui ont changé : `contracts/openapi.json` et `frontend/src/api/schema.d.ts` ;
- les adaptations frontend nécessaires.

Résoudre les conflits dans le code source, puis régénérer les fichiers. Ne pas modifier manuellement les fichiers générés.

Les commandes de synchronisation et de vérification sont décrites dans le [guide du contrat API](docs/api.md).

## Règles de rédaction de la documentation

Mettre à jour la documentation dans la même PR que le changement.

1. Décrire l'état cible du projet. L'avancement est suivi dans les issues, pas dans les guides.
2. Expliquer le quoi et le pourquoi. Le comment (classes, tables, requêtes, champs) reste dans le code.
3. Écrire des phrases courtes : une idée par phrase, une liste dès plus de deux éléments.
4. Écrire chaque règle à un seul endroit. Ailleurs, mettre un lien.
5. Garder en anglais tout terme technique qui apparaît dans le code, une bibliothèque ou un outil (use case, health check, endpoint, mock, token). Ne traduire que ce qui se dit naturellement en français (base de données, droits, mot de passe).

Employer les mêmes termes d'un guide à l'autre.

## Vérifications locales

Vérifier le changement avant de le proposer pour revue :

| Changement                            | Vérifications                                                                           |
| ------------------------------------- | --------------------------------------------------------------------------------------- |
| Code, dépendances ou configuration    | Exécuter les tests et les contrôles concernés, puis vérifier le fonctionnement modifié. |
| Documentation ou templates uniquement | Relire le contenu, les liens, les exemples et le formatage.                             |

Pour une modification de texte ou de mise en forme, vérifier le document suffit. Si une commande ou une procédure change, vérifier qu'elle fonctionne.

Dans la description de la PR, lister les tests et les vérifications effectués, en précisant s'ils ont réussi ou quel problème a été constaté. Si une vérification importante n'a pas été réalisée, expliquer pourquoi.

Les commandes sont décrites dans le [guide de développement](docs/developpement.md#vérifier-ses-modifications) et les guides qualité [frontend](docs/frontend/qualite.md) et [backend](docs/backend/qualite.md).

## Proposer une PR

Utiliser le [modèle de PR](.github/PULL_REQUEST_TEMPLATE.md) et remplir les rubriques utiles :

- l'objectif et l'issue concernée ;
- les changements effectués ;
- les vérifications exécutées et leurs résultats ;
- les effets sur l'API, les données, la configuration et la documentation ;
- les captures avant/après utiles pour une modification d'interface.

Garder la description à jour si le périmètre évolue. Signaler les points qui demandent une attention particulière et les limites connues. Pour une vérification manuelle, indiquer les conditions, les étapes, le résultat attendu et le résultat constaté, pour que la personne qui relit puisse la reproduire.

Ouvrir une **Draft PR** pour partager un travail en cours. Préciser ce qui reste à faire et le retour attendu, puis la passer en **Ready for review** lorsque le changement est prêt. Une PR terminée peut être ouverte directement pour revue.

### Dépendances entre PR

Si la PR nécessite une autre PR non fusionnée, indiquer son lien et expliquer pourquoi. Il n'est pas nécessaire de citer les anciennes PR déjà intégrées à la branche cible.

Attendre la fusion de la PR nécessaire, puis mettre sa branche de travail à jour depuis **la branche cible** : généralement `main`, ou `maintenance/<série>` pour un correctif d'ancienne version. Résoudre les conflits avant la fusion.

## Revue et fusion

Avant la fusion :

- Faire examiner et approuver les changements par une personne autre que l'auteur.
- Traiter les remarques et résoudre les discussions.
- Vérifier que la branche est à jour par rapport à sa cible et que les conflits sont résolus.
- Vérifier que les contrôles [CI](docs/ci.md) réussissent et corriger les échecs.

La personne qui fusionne vérifie ces points. Utiliser **Squash and merge** avec un titre de PR conforme à la [convention de commit](#convention-de-commit), puis supprimer la branche de travail.

L'auteur peut fusionner s'il est habilité, après l'approbation indépendante ; il ne peut pas approuver sa propre PR.

# Scripts du dépôt

Ce guide décrit l'organisation des scripts et les règles pour les modifier. Pour lancer l'application et ses tests, suivre le [guide de développement](developpement.md).

Les commandes npm sont définies dans le [`package.json` racine](../package.json). Elles appellent un outil directement ou un fichier du dossier [`scripts/`](../scripts/).

## Écrire un script

Les scripts et leurs tests sont en TypeScript. Node.js 24 les exécute directement, sans compilation, grâce au « type stripping » : Node retire les annotations de type avant l'exécution.

Cette technique limite la syntaxe. Les constructions qui produisent du code, comme `enum` ou `namespace` avec du code, sont interdites.

- Écrire les imports de fichiers avec l'extension `.ts`.
- Utiliser `import type` pour importer des types.
- Utiliser des chemins relatifs, sans alias.
- Respecter la syntaxe limitée du type stripping. L'option `erasableSyntaxOnly` vérifie cette contrainte.

Les règles de typage sont définies dans [`tsconfig.scripts.json`](../tsconfig.scripts.json).

## Vérifier les scripts

Depuis la racine :

```sh
npm run check -- scripts
```

Ce bloc vérifie les types sans exécuter les scripts ni produire de fichiers JavaScript (`noEmit`), puis exécute les tests du dossier `scripts/` avec Node.js. `npm test -- scripts` lance seulement les tests. `npm run typecheck` vérifie les types des scripts et du frontend.

## Organisation des fichiers

### Scripts

| Fichier             | Utilité                                                                                                                                                                     |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dev.ts`            | Lancer les composants demandés, surveiller les sources Java avec `fs.watch`, suivre la santé du backend et synchroniser le contrat de l'API.                                |
| `dev-options.ts`    | Valider les options de ports, remplacer un port par défaut occupé et préparer les adresses, le proxy et les commandes de lancement.                                         |
| `dev-build.ts`      | Calculer l'empreinte des sources (`sourceFingerprint`), vider les classes compilées (`resetClasses`) et publier une nouvelle version à DevTools.                            |
| `services.ts`       | Choisir le projet Docker Compose de la copie et les ports de PostgreSQL et Redis, démarrer, arrêter ou remettre à zéro ces services, et retirer ceux des copies supprimées. |
| `lock.ts`           | Lock commun avec signe de vie : attendre son tour, reprendre le lock d'une commande morte ou remplacer la session de développement précédente.                              |
| `api.ts`            | Récupérer le contrat OpenAPI, générer les types frontend et comparer les fichiers.                                                                                          |
| `api-check.ts`      | Vérifier le contrat et les types depuis un backend temporaire construit à partir du code courant.                                                                           |
| `api-isolated.ts`   | Compiler le backend dans `backend/target-isolated/`, puis démarrer un backend temporaire avec ses propres PostgreSQL et Redis et le nettoyer.                               |
| `check.ts`          | Lancer en parallèle les blocs de `npm run check` et de `npm test`, et n'afficher la sortie que des blocs en échec.                                                          |
| `e2e.ts`            | Démarrer l'API temporaire pendant la construction du frontend et l'installation de Chromium, puis lancer les tests navigateur.                                              |
| `dev-process.ts`    | Lancer et arrêter les processus, créer et charger `backend/.env`, vérifier Docker et les ports, et attendre la réponse de santé du backend.                                 |
| `errors.ts`         | Extraire un code ou un message d'erreur depuis une valeur de type inconnu.                                                                                                  |
| `local-mailbox.ts`  | Fournir la boîte email locale, lancée par `npm run dev` en mode `local` et par les E2E, qui garde les messages en mémoire.                                                  |
| `google-fixture.ts` | Simuler Google pour les tests E2E, avec des réponses OIDC signées et une vérification PKCE.                                                                                 |
| `map-assets.ts`     | Vérifier (ou actualiser avec `--refresh-manifest`) les tailles et hashes des ressources cartographiques.                                                                    |
| `reference-data.ts` | Écrire la migration des données de référence officielles (NAF de l'INSEE, ROME / NAF de France Travail) dans le fichier donné.                                              |
| `open-data.ts`      | Lire les fichiers officiels (zip, feuilles xlsx) et remplacer un fichier généré seulement par un résultat complet.                                                          |

### Tests

| Fichier                  | Comportements vérifiés                                                                                                            |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| `api.test.ts`            | Tester la génération du contrat et des types, ainsi que la conservation des fichiers après un échec.                              |
| `check-cli.test.ts`      | Vérifier que la vérification du contrat signale l'absence de Docker avec un message d'erreur.                                     |
| `api-isolated.test.ts`   | Ignorer les réglages du poste, réutiliser le JAR isolé tant que les sources ne changent pas et recompiler après une sauvegarde.   |
| `check.test.ts`          | Laisser les autres blocs finir après un échec, ne rapporter que le bloc en échec et refuser un bloc inconnu.                      |
| `dev.test.ts`            | Réduire un échec de compilation aux erreurs du compilateur.                                                                       |
| `dev-options.test.ts`    | Vérifier les options de ports, le choix d'un port libre, les adresses de lancement et le refus d'un proxy frontend vers lui-même. |
| `dev-recovery.test.ts`   | Interrompre l'attente du backend lorsque les sources changent ou que le processus Java s'arrête.                                  |
| `dev-build.test.ts`      | Retirer les fichiers obsolètes, conserver la version en cours pendant la compilation et reconnaître la nouvelle version démarrée. |
| `dev-process.test.ts`    | Tester les processus, les ports, le masquage des secrets, la surveillance des fichiers et la création de `backend/.env`.          |
| `lock.test.ts`           | Reprendre le lock d'une commande morte, attendre un propriétaire vivant et remplacer la session de développement précédente.      |
| `services.test.ts`       | Séparer les conteneurs de la copie principale et des worktrees, et choisir les ports de PostgreSQL et Redis.                      |
| `local-mailbox.test.ts`  | Tester la boîte email locale.                                                                                                     |
| `google-fixture.test.ts` | Tester le faux fournisseur Google des E2E.                                                                                        |
| `map-assets.test.ts`     | Tester la vérification des ressources cartographiques.                                                                            |
| `reference-data.test.ts` | Tester la migration des données de référence et son refus d'un fichier officiel inattendu.                                        |
| `open-data.test.ts`      | Tester la lecture des archives et des feuilles, et l'écriture d'un fichier généré.                                                |

## Compilation et redémarrage du backend

Chaque usage compile le backend dans son propre dossier :

| Dossier                       | Utilisation                                                                              |
| ----------------------------- | ---------------------------------------------------------------------------------------- |
| `backend/target-dev/build/`   | Compiler la prochaine version pour la session de développement.                          |
| `backend/target-dev/runtime/` | Conserver les fichiers utilisés par l'API et le worker en cours.                         |
| `backend/target/`             | Compiler et tester le backend avec `npm test` et `npm run check`.                        |
| `backend/target-isolated/`    | Construire le JAR du contrat et des E2E, réutilisé tant que les sources ne changent pas. |

Après une modification de `backend/src/main/` ou de `backend/pom.xml` :

1. `dev.ts` est prévenu par `fs.watch`, compare l'empreinte des sources à celle de la dernière compilation, puis compile dans `build/` à partir de classes vides.
2. Si elle réussit et que les sources n'ont pas changé entre-temps, `dev-build.ts` copie les fichiers dans `runtime/` et retire les fichiers obsolètes.
3. Le script met à jour le fichier `.reload-trigger` pour déclencher le redémarrage avec DevTools.
4. L'API et le worker lancés par la session signalent chacun leur nouvelle version et leur identifiant de processus (PID) dans un fichier. Le script vérifie ces informations et leur état de santé.
5. Si la session comprend l'API, le script synchronise son contrat OpenAPI et les types frontend.

Cette vérification empêche d'exporter le contrat depuis une ancienne version encore en cours de redémarrage. Une modification de `pom.xml` relance les processus Java pour charger les nouvelles dépendances.

Si la compilation échoue, `dev.ts` arrête les processus API et worker de la session et n'affiche que les erreurs du compilateur. Les fichiers de `runtime/`, le contrat et les types précédents sont conservés, mais aucun backend de la session ne reste actif. Le script attend une sauvegarde corrective avant de recompiler et de redémarrer ; le frontend et les services Docker restent disponibles.

La vérification du contrat et `test:e2e` démarrent une copie du JAR de `backend/target-isolated/` dans un dossier temporaire, avec leurs propres conteneurs PostgreSQL et Redis. Les logs de ce backend restent cachés ; leur fin s'affiche si son démarrage échoue. Ces commandes nettoient leurs ressources en fin d'exécution et n'utilisent pas les données des services de développement.

## Locks et arrêt

`lock.ts` protège chaque dossier de compilation et l'écriture du contrat avec un fichier de lock, dans le dossier temporaire du système :

- Le lock contient le PID de son propriétaire. Un PID mort libère le lock tout de suite.
- Le propriétaire touche son fichier toutes les 2 secondes. Un PID vivant dont le fichier reste inchangé 10 minutes est traité comme un PID réutilisé : le lock est repris.
- Un propriétaire suspendu (`Ctrl+Z`) garde son lock : ses processus enfants écrivent peut-être encore dans le dossier.
- Les locks vivent dans un dossier temporaire propre à l'utilisateur, fermé aux autres comptes du poste.
- `npm test`, `npm run check`, la vérification du contrat, les E2E, `api:sync` et `api:generate` attendent leur tour (`whenBusy: 'wait'`).
- Une nouvelle session `dev` demande à la précédente de s'arrêter, puis prend sa place (`whenBusy: 'replace'`).

La session de développement transmet son PID à l'API, au worker et à Vite. Chacun vérifie chaque seconde qu'elle existe encore et s'arrête sinon : [`DevelopmentSessionWatch`](../backend/src/main/java/fr/emploigo/config/DevelopmentSessionWatch.java) côté Java, [`dev-session-plugin.ts`](../frontend/tooling/dev-session-plugin.ts) côté Vite. Un terminal fermé déclenche le même nettoyage que `Ctrl+C`.

## Modifier une commande

1. Modifier le script concerné et, si nécessaire, sa déclaration dans `package.json`.
2. Adapter les tests au comportement modifié.
3. Mettre à jour les instructions du [guide de développement](developpement.md) si l'utilisation de la commande change.
4. Exécuter les vérifications de scripts ci-dessus et les essais nécessaires au changement.

Sources :

- [TypeScript dans Node.js](https://nodejs.org/docs/latest-v24.x/api/typescript.html)
- [Option TypeScript noEmit](https://www.typescriptlang.org/tsconfig/noEmit.html)
- [Scripts npm](https://docs.npmjs.com/cli/v11/using-npm/scripts/)
- [Spring Boot DevTools](https://docs.spring.io/spring-boot/reference/using/devtools.html)
- [Répertoire de compilation Maven](https://maven.apache.org/pom.html#directories)

# Contrat API et types du frontend

Ce guide explique comment maintenir le contrat de l'API et les types TypeScript du frontend en accord avec le code Java. Il présente les commandes de synchronisation, les étapes à suivre après une modification et les solutions aux problèmes courants.

La conception des routes, DTO et autorisations relève du [guide API backend](backend/api.md) ; les appels HTTP et leur utilisation dans l'interface sont décrits dans la [couche API frontend](frontend/api-layer.md). Ces échanges suivent l'[architecture commune](architecture.md) et les [règles de sécurité](securite.md).

## Du Java aux types TypeScript

Les routes et DTO Java définissent les données reçues et renvoyées par l'API. springdoc en produit une description au format OpenAPI. Le script de synchronisation enregistre ce contrat, puis utilise `openapi-typescript` pour générer les types du frontend.

```text
Routes et DTO Java
    ↓ springdoc
Description OpenAPI de l'API
    ↓ export par le script
contracts/openapi.json
    ↓ openapi-typescript
frontend/src/api/schema.d.ts
    ↓ utilisés par openapi-fetch
Appels HTTP typés du frontend
```

**Le contrat et les types générés sont versionnés avec le code et ne se modifient pas manuellement.** Le client commun `frontend/src/api/client.ts` utilise ces types pour les appels HTTP.

Les types permettent à TypeScript de détecter les routes et champs incorrects à la compilation. Ils ne valident pas les réponses JSON réellement reçues : les validations et tests restent nécessaires, selon les guides backend et frontend.

## Commandes

Depuis la racine du dépôt :

| Commande                    | Usage                                                                                                                                                 |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run dev`               | Démarrer le développement avec compilation Java, relance de l'API et synchronisation automatique du contrat et des types.                             |
| `npm run api:sync`          | Exporter le contrat depuis une API locale à jour, puis générer les types.                                                                             |
| `npm run api:generate`      | Générer les types depuis le contrat enregistré, sans démarrer le backend.                                                                             |
| `npm run check -- contract` | Démarrer une API et des services temporaires depuis le backend courant, puis comparer le contrat et les types sans modifier les fichiers enregistrés. |

### Synchroniser depuis l'API locale

**L'API doit être recompilée et démarrée depuis la branche courante.** Un serveur encore ouvert depuis une autre branche peut fournir un ancien contrat. `npm run dev` coordonne la compilation, la relance et la synchronisation ; `api:sync` ne compile pas et ne démarre pas l'API.

Pour une API sur un autre port :

```sh
npm run api:sync -- --api-port 8180
```

`--api-port` prend la priorité. Sans cette option, la commande utilise l'URL définie par `API_TARGET` dans le terminal, ou `http://127.0.0.1:8080` par défaut. Voir les [ports du backend](backend/developpement.md#ports-et-supervision).

`api:generate` part du contrat enregistré, sans consulter l'API. Elle peut aussi remettre ce contrat au format normalisé du dépôt ; elle ne vérifie pas qu'il correspond au Java courant.

### Vérifier depuis le code courant

`npm run check -- contract` nécessite Docker opérationnel et le JDK du projet. Ce bloc de `npm run check` :

1. Compile le backend courant dans `backend/target-isolated/`, ou réutilise ce JAR si les sources n'ont pas changé depuis.
2. Démarre une API avec PostgreSQL et Redis temporaires, sur des ports libres. Les logs de cette API restent cachés ; leur fin s'affiche si elle ne démarre pas.
3. Exporte le contrat et génère les types pour les comparer aux fichiers versionnés. Un écart fait échouer le contrôle ; aucun fichier versionné n'est modifié.
4. Arrête le processus Java et supprime les conteneurs, leurs volumes et les fichiers temporaires.

La base de développement et ses volumes restent séparés. Le [guide CI](ci.md) décrit l'utilisation de ce contrôle dans les PR.

## Modifier une route ou un DTO

1. Modifier les routes ou DTO Java.
2. Avec `npm run dev`, attendre le message « Backend reloaded in … ». Si le contrat a changé, le message « API contract updated » le précède. Si les processus sont démarrés séparément, recompiler et relancer l'API, puis exécuter `npm run api:sync`.
3. Adapter les appels et l'interface aux nouveaux types.
4. Inclure le Java, `contracts/openapi.json`, `frontend/src/api/schema.d.ts` et les adaptations frontend dans le même changement.
5. Exécuter `npm run check -- contract` et les vérifications adaptées au changement avant la PR, selon [CONTRIBUTING](../CONTRIBUTING.md#vérifications-locales).

Le dossier `contracts/` porte aussi les règles que l'OpenAPI ne peut pas exprimer et que les deux piles doivent juger pareil. `contracts/password-rule-cases.json` fixe les cas de la [règle de mot de passe](securite.md#règles-pour-lutilisateur) (composition comprise) : `RegistrationPasswordTest` (Java) et `password-rule.test.ts` (Vitest) le lisent, et un cas ajouté couvre les deux côtés. De même, `contracts/username-rule-cases.json` fixe les cas de la [règle du pseudo](perimetre-v1.md#pseudo-et-avatar) : `AccountIdentifierTest` (Java) et `schemas.test.ts` (Vitest) le lisent.

Une modification uniquement visuelle du frontend ne change pas le contrat. Les règles sur les champs obligatoires, facultatifs, nullables et les erreurs prévues sont définies dans le [guide backend](backend/api.md#routes-et-dto).

**Une PR commune frontend/backend ne garantit pas la compatibilité avec un frontend déjà ouvert chez un utilisateur.** Privilégier les ajouts compatibles et prévoir une transition pour les suppressions ou renommages.

## Échecs de synchronisation et conflits

La synchronisation prépare et valide le contrat et les types avant de remplacer les fichiers. Un échec d'export ou de génération conserve les derniers fichiers valides et affiche une erreur. **Ces fichiers ne sont alors pas nécessairement à jour.**

| Situation                             | Action                                                                                                                                           |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Export ou génération en échec         | Corriger l'erreur, vérifier que l'API courante est prête, puis relancer la synchronisation.                                                      |
| Le bloc `contract` signale un écart   | Démarrer l'API de la branche courante, synchroniser, examiner les différences et inclure les fichiers actualisés dans le changement.             |
| Conflit Git dans les fichiers générés | Résoudre d'abord le Java, puis recompiler et relancer ce backend pour régénérer le contrat et les types. Ne pas résoudre ces fichiers à la main. |

Une seule synchronisation ou génération modifie ces fichiers à la fois dans ce dépôt ; une autre attend son tour. Le bloc `contract` ne prend pas ce lock puisqu'il compare les fichiers sans les modifier.

Le script trie les clés du contrat et utilise les versions d'outils fixées dans les dépendances et le lock npm. Cela limite les différences de fichiers sans changement réel de l'API.

## Références

- [springdoc](https://springdoc.org/) et [ordre déterministe du contrat](https://springdoc.org/properties.html).
- [Génération et contrôle openapi-typescript](https://openapi-ts.dev/cli).
- [API Node de génération](https://openapi-ts.dev/node).
- [Client openapi-fetch](https://openapi-ts.dev/openapi-fetch/).

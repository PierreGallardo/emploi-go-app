# Couche API frontend

Ce guide explique comment appeler l'API depuis une fonctionnalité, gérer ses
données en cache et traiter les erreurs. Il précise aussi les règles de session
et de confidentialité dans le navigateur.

| Emplacement                     | Responsabilité                                                       |
| ------------------------------- | -------------------------------------------------------------------- |
| `src/api/client.ts`             | Transport HTTP commun, typé par le contrat API.                      |
| `features/<feature>/api.ts`     | Appels HTTP et contrôle des réponses de la fonctionnalité.           |
| `features/<feature>/queries.ts` | Clés, options de query et de mutation, actualisation du cache.       |
| Composants de la fonctionnalité | Déclenchement des actions et affichage des résultats ou des erreurs. |

La génération du contrat, les commandes de synchronisation et les règles des
fichiers générés sont décrites dans le [guide API commun](../api.md).
Ce document porte sur leur utilisation dans le frontend.

Le [guide API backend](../backend/api.md) définit les réponses HTTP et les contrôles
côté serveur. Le [guide sécurité](../securite.md) porte les règles communes
d'identité et de confidentialité.

## Transport commun

[`src/api/client.ts`](../../frontend/src/api/client.ts) exporte le client `api`
créé avec `openapi-fetch` et les types de `src/api/schema.d.ts`.
Sa configuration contient :

| Option                       | Effet                                                                      |
| ---------------------------- | -------------------------------------------------------------------------- |
| `baseUrl: ''`                | Utiliser des chemins relatifs à l'origine courante.                        |
| `credentials: 'same-origin'` | Laisser le navigateur envoyer les cookies aux requêtes de la même origine. |

Les fonctions de requête utilisent des chemins `/api/...` relatifs.
Vite les transmet au backend local ; l'hébergement route ce préfixe vers le
backend du même environnement. Ne pas introduire une URL d'API propre à test ou
production dans les composants ou le bundle.

Les réglages communs du transport appartiennent à ce client. Il ne connaît ni
une clé Query ni un composant ni une fonctionnalité. Un middleware traite le
`401` des queries protégées, pour toutes les fonctionnalités : voir
[Session et protection des données](#session-et-protection-des-données).
Les appels HTTP de l'application passent par ce client depuis les fichiers
`features/<feature>/api.ts`.

Les types contrôlent les routes, paramètres et données à la compilation.
Ils ne valident pas les réponses JSON à l'exécution. Vérifier explicitement les
contraintes de réponse dont dépend l'usage,
sans maintenir une copie manuelle complète du contrat.
Voir [openapi-fetch](https://openapi-ts.dev/openapi-fetch/).

## Fonctions de requête

`features/<feature>/api.ts` reçoit les paramètres explicites d'une opération,
appelle `api` et renvoie les données attendues. Il ne dépend pas de React ni
de TanStack Query et ne modifie aucun cache.

Pour chaque appel :

1. Recevoir les paramètres de l'opération et, pour une lecture annulable, un
   `AbortSignal`.
2. Appeler le client commun et lui transmettre ce signal pour permettre
   l'annulation. Donner à la requête un timeout avec le helper commun
   `src/lib/deadline.ts`, qui le combine au signal de l'appelant : une connexion
   bloquée sur un réseau mobile doit finir en erreur avec une action
   « Réessayer ». Les lectures de la carte l'ont fixé à 15 s, reconnexion et
   rejeu éventuels compris. Quand il est dépassé, la reconnexion se poursuit sans
   la lecture, et « Réessayer » la retrouve faite.
3. Vérifier le résultat HTTP et les contraintes de réponse nécessaires à l'usage.
4. Renvoyer les données attendues en cas de succès ; sinon, faire remonter
   l'erreur ou l'annulation. Une erreur ne devient pas un tableau vide ou une
   valeur indéfinie présentée comme un succès.

Exemple fictif : `getEvent` reçoit un identifiant d'événement (`eventId`) et un
signal d'annulation.

- Elle renvoie l'événement obtenu après vérification de la réponse.
- Un refus d'accès ou une réponse invalide reste une erreur.
- Elle ne produit jamais un événement vide.

Les types d'entrée et de sortie s'appuient sur le contrat généré et l'inférence.
Une transformation pour l'affichage peut définir un type propre à l'interface,
mais ne doit pas masquer une erreur de contrat ou d'autorisation.

## Options de lecture

`features/<feature>/queries.ts` regroupe les clés et options des lectures.
Utiliser `queryOptions` pour garder la clé, la fonction et leurs types ensemble.
Le composant utilise `useQuery` avec ces options ; un préchargement réutilise
la même définition.

Extrait fictif pour un événement, à adapter au contrat de la fonctionnalité :

```ts
export const eventQueryOptions = (eventId: string) =>
  queryOptions({
    queryKey: ['events', 'detail', eventId],
    queryFn: ({ signal }) => getEvent({ eventId, signal }),
  });
```

La clé de cache `queryKey` identifie le résultat que TanStack Query conserve et
réutilise. Ici, chaque événement possède une entrée distincte.
Voir [les options Query](https://tanstack.com/query/latest/docs/framework/react/guides/query-options)
et [l'annulation des requêtes](https://tanstack.com/query/latest/docs/framework/react/guides/query-cancellation).

Construire les clés selon ces règles :

- Inclure tous les paramètres dont dépend le résultat : identifiant, filtres,
  pagination ou contexte de compte si la donnée lui appartient.
- Utiliser des valeurs sérialisables, c'est-à-dire représentables en JSON, et
  cohérentes avec les paramètres envoyés à l'API.
- Ne pas partager une clé entre des réponses différentes, ni y placer un token
  ou une donnée sensible inutile.

Voir [les clés Query](https://tanstack.com/query/latest/docs/framework/react/guides/query-keys).

## Staleness et refetch

Le cache Query est un cache en mémoire du navigateur, distinct du cache HTTP,
de Cloudflare et de Redis. Il facilite le chargement et la réutilisation ;
il n'est pas une référence durable ni une preuve d'autorisation.

Définir les options selon l'usage :

| Option ou comportement | Choix à faire                                                                                                                                            |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `staleTime`            | Durée pendant laquelle une donnée est considérée comme fraîche. Son expiration ne supprime pas la donnée et ne déclenche pas, à elle seule, une requête. |
| `gcTime`               | Délai avant suppression d'une entrée inactive, lorsqu'aucun composant ne l'utilise.                                                                      |
| Refetch                | Comportement au mount, au retour du focus et à la reconnexion.                                                                                           |
| Tentatives après échec | Erreurs pour lesquelles réessayer est utile et nombre de tentatives adapté.                                                                              |

Voir [les comportements par défaut](https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults).

Ne pas désactiver globalement les refetch pour contourner un défaut.
Éviter les lectures séquentielles lorsqu'elles sont indépendantes, et charger
uniquement les données utiles à l'écran. Passer le signal Query jusqu'à la
requête pour permettre l'annulation du travail devenu inutile.

## Mutations et invalidation

Une mutation est une opération qui modifie des données côté serveur, par exemple
l'ajout d'un événement aux favoris (`addFavorite`). Les fonctions d'écriture restent dans
`api.ts` ; leurs options et la stratégie de cache restent dans `queries.ts`.
Une mutation n'est déclenchée que par l'action prévue, jamais pendant le render.

Après confirmation du serveur :

1. Actualiser les données depuis une réponse fiable, ou **invalider** leurs clés :
   indiquer au cache que ces résultats doivent être relus.
2. Inclure toutes les vues affectées, par exemple le détail et les listes
   contenant l'élément.
3. Attendre cette mise à jour si l'interface présente ensuite un résultat qui
   en dépend.

Voir [l'invalidation après mutation](https://tanstack.com/query/latest/docs/framework/react/guides/invalidations-from-mutations).

Ne pas rejouer automatiquement une écriture sans garantie adaptée côté serveur.
Une perte de réponse peut survenir après une écriture effectivement réalisée.
Un optimistic update affiche le résultat attendu avant la confirmation du
serveur. Si elle est utile, prévoir l'annulation des lectures
concurrentes, le rollback sur échec et la réconciliation avec le serveur.

## Session et protection des données

La fonctionnalité `auth` gère la session côté navigateur.

- La session est conservée dans TanStack Query.
- Un `401` déclenche une seule tentative de reconnexion automatique (nouvelle session sans écran de connexion), seulement si le cookie indicateur de reconnexion (cookie sans secret qui signale qu'une reconnexion est possible) est présent. Sans lui, le visiteur reste anonyme, sans autre requête.
- Si cette tentative est refusée, l'interface demande une connexion explicite.
- Une panne reste une erreur, sans demande de connexion.
- Le token CSRF est lu dans son cookie avant chaque mutation. S'il manque, `GET /api/auth/csrf` le pose.
- Une mutation n'est pas rejouée automatiquement.

Le parcours complet est décrit dans le guide
[authentification](../backend/authentification.md).

Le `401` d'une query protégée (la carte, par exemple) est traité une seule
fois, au même endroit, pour toutes les fonctionnalités.

- Un middleware du client commun (`src/api/unauthorized.ts`) intercepte le `401`
  des lectures `GET` et `HEAD` hors `/api/auth/`. Il est dans le client parce que
  toutes les requêtes y passent, et qu'une fonctionnalité ne peut pas importer
  `auth`.
- La politique reste dans `auth` : `providers.tsx` installe la reconnexion dans le
  client au mount de l'application. Une seule reconnexion est installée à la fois ;
  en installer une seconde est une erreur.
- La reconnexion est un refetch de la session. Elle passe donc par le lock
  Web Locks et par l'attente demandée après un refus (`429`), et elle rejoint la
  refetch que l'écran a déjà en cours (puis relit la session si cette
  refetch a abouti, car sa réponse peut dater d'avant le `401`). Sans compte connu à l'envoi de la
  lecture, il n'y a pas de reconnexion.
- Un `401` qui arrive pendant une reconnexion l'attend. Une lecture partie
  avant la fin d'une reconnexion qui a échoué reçoit cet échec, même si son `401`
  arrive plus tard : l'API voit une seule tentative. Après une réussite, un `401`
  tardif relit la session pour confirmer le compte du moment, sans nouvelle
  reprise tant que la session est valide. Une lecture envoyée après la fin de la
  tentative en lance une nouvelle.
- La lecture n'est renvoyée qu'une fois, et seulement si la reconnexion rétablit
  le compte pour lequel elle a été envoyée. Le renvoi ne repasse pas par
  le middleware : un second `401` est rendu tel quel, sans boucle. Une lecture
  annulée ou dont le délai est passé pendant la reconnexion n'est pas renvoyée.
- Si la reconnexion ne rétablit pas le même compte, la lecture garde son `401` :
  l'écran protégé redirige vers la connexion ou affiche « Connexion indisponible »,
  comme pour toute autre vérification de la session.
- Une panne de la reconnexion (réseau, `5xx`, limite, timeout) termine la lecture en
  erreur, sans demande de connexion. Un `403`, un `5xx` ou une panne de la
  lecture elle-même ne déclenchent aucune reconnexion.
- Une écriture n'est jamais renvoyée.
- La garantie d'une seule reprise vaut pour les reconnexions des queries protégées. Le refetch de la session par un écran (retour du focus, « Réessayer »), ou par un autre onglet, est une autre source de reprise : si la réponse d'une rotation est perdue, une query protégée dont le `401` arrive ensuite peut rejouer l'ancien secret. L'API le sert alors comme un rejeu proche de la rotation.

Plusieurs onglets peuvent tenter une reconnexion en même temps.
[Web Locks](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API)
coordonne ces onglets.

- Web Locks exige un contexte sécurisé : HTTPS ou localhost.
- Un navigateur sans Web Locks provoque une erreur explicite.
- Les actions attendent la fin d'une reconnexion en cours. Ainsi, ses cookies ne
  remplacent pas ceux d'une connexion suivante.
- Cette coordination complète les vérifications du serveur.
- Une opération attend le lock au plus 15 s, puis s'exécute sous lui au plus 15 s. Passé ce timeout, elle est abandonnée avec le message « Cette action a pris trop de temps ». Sans timeout, une requête bloquée sur un réseau mobile garderait le lock pour tous les onglets.
- Le lock n'est rendu qu'après l'abandon des requêtes de l'opération. Le navigateur enregistre les cookies d'une réponse dès l'arrivée de ses headers, avant que l'abandon atteigne le réseau : une réponse tardive ne remplace donc pas les cookies de l'onglet suivant, même si la page était suspendue en arrière-plan. Un timeout dépassé ne prouve pas que l'API ou les cookies soient restés dans leur état précédent.
- La demande de reprise, qui renouvelle le secret de reconnexion, a son propre timeout de 15 s, compté depuis son envoi. L'annulation de la lecture par l'écran ne l'interrompt pas. Une lecture qui se reconnecte peut donc tenir le lock environ 30 s, et une opération qui attend derrière elle peut expirer.
- Si la réponse d'une reprise est perdue, le navigateur garde l'ancien secret alors que le serveur en a déjà un nouveau. Le serveur sert ce rejeu s'il arrive dans sa tolérance, plus longue que ce délai.
- La lecture suivante rejoue l'ancien secret : « Réessayer », le retour du focus ou du réseau, la lecture d'un autre onglet et le `401` d'une query protégée lisent de nouveau la session. Rejoué dans la tolérance du serveur, il est servi : la session est rétablie avec un nouveau secret. Rejoué après la tolérance, il révoque la reconnexion (voir [sécurité](../securite.md#sessions-et-réglages-initiaux)) et le jeune se connecte avec son mot de passe. Le client ne bloque pas ce rejeu : l'API rétablit la session en servant un nouveau secret.
- Un timeout dépassé s'affiche comme une panne. Pendant l'attente du lock ou la lecture de la session, un bandeau avec « Réessayer » laisse le contenu en place. Pendant la reprise, la session n'est plus confirmée : l'écran « Connexion indisponible » remplace le contenu et propose aussi « Réessayer ». Pour une écriture, le message apparaît dans le formulaire, qui garde sa saisie et se renvoie avec son bouton d'envoi.
- Une écriture qui change la session (connexion, déconnexion, accès ou inscription Google, changement de mot de passe, confirmation) peut avoir été acceptée par l'API avant le timeout : ses cookies sont déjà enregistrés, mais le cache n'a pas été mis à jour. Un timeout dépassé fait donc relire la session, y compris pour la liaison Google, qui conserve la session mais vide le cache. L'écriture n'est pas renvoyée.
- Le timeout vient d'un helper commun, `src/lib/deadline.ts`, pas de `AbortSignal.timeout` ni de `AbortSignal.any` : `any` manque à Safari 16.4 à 17.3, et `timeout` lève un `AbortError` sur Chrome 103 à 123.

Vérifier la session au mount, au retour du focus et à la reconnexion réseau.

- Une session confirmée il y a moins d'une minute n'est pas relue à ces
  occasions. Une déconnexion ou un compte remplacé dans un autre onglet
  n'attend pas ce délai : voir [Plusieurs onglets](#plusieurs-onglets).
- Masquer le contenu protégé seulement si la session est inconnue, `null`, change de compte ou n'est plus confirmée.
- Tant que la session est inconnue (premier chargement), afficher tout de suite l'app shell : le header avec la marque, un bouton « Mon compte » désactivé et des skeletons à la place du contenu. Aucune donnée protégée n'est affichée avant la réponse. Jamais d'écran qui annonce une vérification. Un seul statut `sr-only` (`LoadingStatus` : « Chargement… », puis « Chargement terminé. » pendant 5 s, voir [fin de chargement](conventions.md#fin-de-chargement), sauf quand un titre focalisé annonce déjà l'écran) et `aria-busy` sur `<main>` l'annoncent aux technologies d'assistance. Les écrans de connexion et d'inscription gardent leur carte et leur titre, avec des skeletons de champs.
- Ne pas le masquer pendant une revérification (retour du focus, reconnexion réseau) : il reste affiché avec la dernière session connue, jusqu'à la réponse du serveur. Le focus et le menu du compte restent en place.
- Quand un écran en remplace un autre, il suit la [règle du focus perdu](conventions.md#focus-quand-un-écran-en-remplace-un-autre). L'erreur plein écran prend toujours le focus sur son titre. Le contenu le reçoit sur `<main>`, après une erreur, un changement de compte ou la disparition du bandeau d'erreur où se trouvait le focus. Au premier chargement réussi et pendant une revérification qui confirme le même compte, le focus reste où il est.
- Une panne de la lecture de la session (réseau, `5xx`) ne retire pas le contenu : un bandeau l'annonce, avec une action « Réessayer ». Sans session connue, l'erreur remplace l'écran.
- Pendant une nouvelle lecture (« Réessayer », retour du focus ou du réseau), l'erreur reste affichée, sans skeleton. Le bouton garde le focus, passe en attente et annonce « Vérification en cours… ». Il en va de même sur la connexion et l'inscription.
- TanStack Query efface l'erreur d'une requête sans données dès qu'il la relance. L'écran garde donc l'erreur jusqu'à la réponse (`useSessionCheckFailure`).
- Une session n'est plus confirmée quand le serveur répond `401` et que la reprise automatique échoue ensuite, quelle qu'en soit la cause (limite, panne, réseau). L'écran protégé est alors remplacé par le message. Si la reprise est limitée (`429`), il propose aussi le lien vers la connexion par mot de passe, qui reste utilisable.
- Après un refus de reprise avec `Retry-After` (au plus une heure ; une minute pour un `429` sans valeur utilisable), l'onglet ne demande plus de reprise avant l'échéance : ni au retour du focus, ni à la reconnexion réseau, ni par « Réessayer », qui reste désactivé et indique l'heure de la fin de l'attente, arrondie à la minute supérieure (le bouton se réactive à l'échéance exacte : réactiver plus tôt que l'heure annoncée ne gêne pas, l'inverse ferait réessayer trop tôt). La lecture de la session continue, car une connexion faite dans un autre onglet la rétablit. Une nouvelle connexion remet l'attente à zéro ; un rechargement de la page aussi.
- Un `429` `MAIL_RECENTLY_SENT` signifie qu'un email vient d'être demandé pour cette adresse il y a moins d'une minute. Le renvoi du code l'affiche avec l'heure du prochain renvoi, et la demande de lien ou l'email Google affiche le résultat d'un envoi accepté. L'inscription reste sur le formulaire avec l'attente : le code de cet email ne se saisit que sur la page qui l'a demandé.
- Un `410` `NO_PENDING_REGISTRATION` signifie que la session n'a plus l'inscription de cette page (confirmée, remplacée, expirée ou oubliée à une connexion) : l'écran du code propose de recommencer l'inscription.
- Un `410` `USED_LINK` sur le nouveau mot de passe remplace le formulaire par un message qui renvoie à la connexion avec le nouveau mot de passe, sans rejouer le changement.
- Le `429` d'une écriture d'authentification porte la même attente (`Retry-After`, au plus une heure ; une minute sans valeur utilisable) dans `AuthenticationError.retryAt`. Les autres statuts n'en portent pas : pour une écriture, un `503` garde son message de panne, même avec `Retry-After: 5` : cette valeur est une indication fixe des compteurs, que tous les `503` ne portent pas, et une heure arrondie à la minute dirait jusqu'à 55 s de trop pour un jeune qui n'y est pour rien. Le plafond de calculs de mot de passe est la seule exception : son `503` porte le code `BUSY`, et l'écran annonce « quelques secondes » sans lire `Retry-After`, dans la connexion, l'inscription, la récupération et l'accès Google à un compte existant.
- Le `202` de l'inscription, du renvoi du code et de la demande de récupération porte aussi `Retry-After` : le délai avant un nouvel email à cette adresse, le même avec ou sans compte. L'écran du code et celui de la récupération comptent les secondes sur le bouton « Renvoyer dans N s », retenu jusqu'à zéro. « Modifier » rend au formulaire l'email, jamais le mot de passe.
- Chaque envoi que le serveur limite passe en `aria-disabled` jusqu'à l'échéance : connexion, inscription, renvoi du lien, demande de récupération, nouveau mot de passe, étapes Google et départ vers Google. Le mécanisme est commun (`useRetryHold`, `RetryButton`, `RetryStatus` et `RetryHint` de `retry-wait.tsx`). L'heure est dans l'alerte du refus (annoncée avec lui, où que soit le focus) et reliée au bouton par `aria-describedby`, comme pour « Réessayer ». Le bouton garde le focus (un bouton `disabled` le rendrait à la page : Chromium le met sur `body`), donc il reste `aria-disabled` aussi pendant l'envoi, et son clic est avalé tant qu'il est retenu : la touche Entrée d'un champ clique le bouton par défaut, donc elle n'envoie rien non plus. La saisie reste libre.
- Le code d'inscription n'a pas de bouton : il part au sixième chiffre, une seule fois, et les cases sont désactivées pendant la vérification ou un renvoi. Pendant une attente, rien ne part et l'heure est reliée aux cases. Après un refus, les cases sont vidées et reprennent le focus : les six chiffres suivants repartent, rien n'est rejoué tout seul.
- Ce que retient un bouton suit ce que le serveur compte pour son action (voir [limitation des tentatives](../backend/authentification.md#limitation-des-tentatives)) :
  - l'identifiant envoyé, pour la connexion et la vérification du compte Google, qui partagent le compteur de la connexion : le bouton n'est retenu que si l'identifiant saisi est celui de la dernière tentative, à la casse et aux espaces de bord près ;
  - l'adresse email, pour l'inscription, le renvoi du lien et la demande de récupération : même règle ;
  - le reste (nouveau mot de passe, confirmation de l'inscription, pseudo, email, association et confirmation de l'email Google, départ vers Google) se compte par adresse IP, par parcours ou par compte : le jeune ne saisit rien qu'on puisse comparer, et le bouton est retenu quoi qu'il tape.
- La comparaison de l'identifiant et de l'email reste volontairement plus simple que celle du serveur, qui juge : une comparaison incomplète coûte au pire un `429` de plus, qui relance l'attente. Un autre jeune sur le même poste n'attend pas.
- À l'échéance, un seul timer réactive le bouton, sans tentative automatique, et une région `role="status"`, montée dès le refus et vide jusque-là (et seulement tant que le critère du bouton s'applique), annonce « Tu peux réessayer. ». Si la vérification de la session échoue en même temps, son bouton « Réessayer » a sa propre région : les deux annonces peuvent se suivre (limite connue). Le départ vers Google a le style désactivé des consignes de Google (38 % du fond et du texte, 12 % du bord).
- Ne pas présenter une déconnexion échouée comme un succès.

Utiliser la session vérifiée par le backend pour les opérations protégées.
Laisser le backend délivrer son cookie de session : `HttpOnly`, `Secure` en
HTTPS, avec une politique `SameSite` adaptée. Le navigateur l'envoie aux
requêtes concernées ; le JavaScript de l'application ne lit pas l'identifiant
de session.

- Ne pas stocker de mot de passe ou de token d'authentification dans
  `localStorage` ou `sessionStorage`.
- Transmettre la protection CSRF attendue pour les actions concernées : elle
  protège contre une demande envoyée à l'insu de l'utilisateur depuis un autre
  site. Le token est un cookie lisible, renvoyé dans le header `X-CSRF-TOKEN`.
  Ne pas la désactiver pour faciliter un appel.
- Les noms des cookies lisibles portent le préfixe `__Host-` en HTTPS et n'en
  ont pas en HTTP local. Le frontend les déduit du protocole.
- Traiter l'expiration de session, le refus d'accès et le renouvellement des
  informations CSRF selon le contrat du backend.

Un `401` et un `403` ne se traitent pas comme une panne réseau ni par des
tentatives répétées sans changement du contexte d'accès.
Voir la [protection CSRF pour les SPA avec Spring Security](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-javascript-spa).

Lors d'une déconnexion ou d'un changement de compte :

1. Annuler les requêtes personnalisées actives.
2. Retirer les données du compte précédent du cache.
3. Vérifier que l'interface ne les réaffiche pas dans le nouveau contexte.

Les écritures de l'onglet (connexion, déconnexion, récupération, étapes Google)
appliquent cette règle elles-mêmes, avant d'écrire la session. Tout refetch de
la session qui découvre un autre compte, ou aucun, l'applique aussi, quel qu'en
soit le chemin : retour du focus, reconnexion réseau, « Réessayer », refetch
après 60 s, reconnexion d'une query protégée, signal d'un autre onglet,
refetch après un timeout dépassé. Un seul observateur du cache s'en charge
(`features/auth/session/account-change.ts`, installé par `providers.tsx`) :

- Il n'agit que sur la réponse de l'API : une écriture `setQueryData` de l'onglet
  a déjà vidé le cache. Une lecture qui répond pendant une connexion de l'onglet
  peut faire vider une fois de plus, sans conséquence. Il n'agit que quand le
  compte connu change ou que la session devient `null` : la première réponse
  n'est pas un changement.
- Il reconnaît le compte à son `accountId`, identifiant opaque donné par l'API,
  et non au pseudo : deux comptes qui n'ont pas encore de pseudo restent
  distincts.
- Il annule les requêtes en cours et retire toutes les requêtes sauf la session,
  dans la même étape que l'arrivée de la nouvelle session, avant tout rendu. Les
  mutations restent : un formulaire en cours d'envoi n'est pas à oublier. La
  requête de la session reste en place, avec sa réponse : l'écran ne repasse
  pas par « session inconnue ».

Le backend reste responsable de la fin de session et de la révocation des
autorisations concernées. Vider le cache frontend ne remplace pas ces contrôles.

Les messages, logs et rapports techniques ne doivent pas transmettre les
body HTTP, cookies, tokens, valeurs de formulaire, identifiants de compte ou
géolocalisation. Les composants présentent un message utile et contrôlé ;
un contenu serveur arbitraire n'est pas affiché comme HTML.
Voir les [critères de qualité](qualite.md#sécurité-et-confidentialité).

### Plusieurs onglets

Les onglets d'un navigateur partagent leurs cookies, pas leur cache. Un onglet
qui ignore qu'un autre a changé la session enverrait ses requêtes avec les
cookies du nouveau compte, mais rangerait leurs résultats sous la clé de
l'ancien.

- Une écriture confirmée qui change la session prévient les autres onglets par
  un `BroadcastChannel` : connexion, déconnexion, accès ou inscription Google,
  changement de mot de passe, confirmation qui ouvre une session. L'inscription,
  la liaison Google (elle conserve les sessions) et les écritures sans effet sur
  les cookies ne préviennent personne.
- Une écriture qui dépasse son délai prévient aussi : ses cookies ont pu changer.
  Le signal ne coûte qu'une lecture à qui n'a rien à apprendre, donc prévenir
  dans le doute est sans risque.
- Le message est une constante. Aucune donnée de session ni identifiant ne
  circule : chaque onglet lit la session lui-même, sous le lock.
- L'onglet qui reçoit lit la session (il rejoint une lecture en cours, puis la
  relit une fois). Si le compte est le même, rien ne bouge. Si le compte a
  changé ou si la session est terminée, la règle ci-dessus s'applique, comme
  pour tout refetch : l'écran protégé redirige vers la connexion ou remonte
  son contenu. La synchronisation ne vide rien elle-même.
- Il n'y a pas de boucle : un onglet ne lit jamais à cause de sa propre annonce
  et n'annonce jamais ce qu'il apprend. Les signaux reçus pendant une lecture
  ne donnent qu'une lecture de plus.
- Une page restaurée du cache de navigation (bfcache) relit la session à son
  retour : un navigateur peut ne pas lui livrer les signaux reçus pendant son
  sommeil.

Deux limites gardent le délai d'une minute :

- Un navigateur sans `BroadcastChannel`. Tout navigateur qui peut verrouiller en
  a un (Web Locks et le canal existent depuis Safari 15.4), donc le cas reste
  théorique.
- Le retour de Google pour un compte déjà lié. Le serveur ouvre la session
  pendant la redirection, avant que l'application ne soit chargée : aucun écran
  ne sait qu'une session vient de s'ouvrir. Les autres onglets l'apprennent à
  leur prochaine vérification, une minute après la dernière au plus.

### Confirmation et récupération

Les pages de confirmation et de récupération protègent le secret du lien.

- Le secret est retiré du fragment de l'URL.
- Il reste uniquement en mémoire.
- Une action explicite est attendue avant tout envoi.
- Un nouveau lien invalide l'état de l'ancien parcours.
- Une réponse tardive ne remplace pas le nouvel état et ne provoque aucune redirection.

Après une panne, l'utilisateur peut relancer l'opération.

- La saisie est renvoyée telle quelle, sans identifiant de demande : le serveur juge chaque requête.
- Les mots de passe sont effacés et les mutations réinitialisées après acceptation.
- Un email accepté par le fournisseur n'est pas une garantie de réception.

Après la confirmation :

- La navigation suit le résultat du serveur.
- Une récupération réussie demande toujours une connexion normale.
- Le changement du mot de passe est confirmé par la réponse du serveur. L'email de confirmation part une seule fois, sans reprise dans l'écran.

Les [règles d'authentification](../backend/authentification.md#mot-de-passe-oublié) précisent ces résultats.

## Exemple complet

Exemple fictif : lecture d'un événement dans la fonctionnalité `events`.
Les chemins et types sont à adapter au contrat réel.

```ts
// features/events/api.ts
export async function getEvent({
  eventId,
  signal,
}: {
  eventId: string;
  signal?: AbortSignal;
}) {
  const { data, error } = await api.GET('/api/events/{id}', {
    params: { path: { id: eventId } },
    signal,
  });
  if (error || !data) throw new Error('Event request failed');
  return data;
}
```

```ts
// features/events/queries.ts
export const eventQueryOptions = (eventId: string) =>
  queryOptions({
    queryKey: ['events', 'detail', eventId],
    queryFn: ({ signal }) => getEvent({ eventId, signal }),
  });
```

- `api.ts` transmet le signal d'annulation et vérifie la réponse.
- `queries.ts` définit la clé et la fonction de lecture.
- Le composant appelle `useQuery(eventQueryOptions(eventId))` et affiche les
  états de chargement, d'erreur et de succès.
- La politique de reprise (`retry`) s'adapte à chaque lecture.
- Une opération protégée suit aussi les règles de session et de CSRF.

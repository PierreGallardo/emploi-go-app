# Carte de Dijon Métropole

La carte authentifiée permet de découvrir les établissements de Dijon Métropole.

- Elle propose des points, une liste accessible et des fiches.
- « Quoi ? » cherche un métier, une entreprise ou une activité ; « Où ? » une adresse, une commune ou un arrêt Divia.
- Les filtres (secteurs, type, taille, transports, rayon) s'appliquent à la carte et à la liste, et restent dans l'URL ; la position cherchée n'y est jamais.
- Une fiche présente une activité, l'arrêt Divia le plus proche et les métiers qui s'exercent dans cette activité.
- Une fiche ne signifie pas que l'entreprise recrute.

## Comment la carte est construite

```text
OpenStreetMap (carte collaborative mondiale)
  → Protomaps (publie chaque jour un fichier carte du monde entier au format
    PMTiles ; conserve environ une semaine de builds)
    → `pmtiles extract` (lit à distance seulement la zone de Dijon)
      → `dijon.pmtiles` + style, polices, icônes, licences, rangés dans
        frontend/public/cartes/<dossier versionné>/
        → le site sert ces fichiers ; MapLibre les affiche dans le navigateur ;
          l'API fournit les établissements affichés par-dessus
```

- Un fichier PMTiles contient toutes les tuiles (petits morceaux de carte) d'une zone.
- La carte est construite sur le poste d'un développeur, pas sur le serveur.
- Les fichiers commités dans le repo sont la référence.
- On ne retourne vers Protomaps que pour mettre la carte à jour.

## Recherche et données affichées

Les établissements viennent de l'[import des établissements](backend/worker.md#import-des-établissements) ; les arrêts et les adresses, de l'[import des arrêts et des adresses](backend/worker.md#import-des-fichiers-ouverts).

- L'API ne sert que les établissements visibles et retenus par l'import ; un autre reçoit `404`, comme un SIRET inconnu. Les routes exigent le rôle `JEUNE` ; leur liste est dans le [contrat](../contracts/openapi.json).
- Le texte cherché ignore accents et casse, et tolère une faute de frappe ([recherche sans accent ni casse](backend/donnees.md#recherche-sans-accent-ni-casse)).
- Un métier mène aux établissements dont l'activité l'exerce, selon la [correspondance des métiers et des activités](backend/donnees.md#métiers-et-activités) ; chaque métier n'est proposé qu'une fois, sous le secteur où il a le plus d'établissements. « Quoi ? » propose d'abord les métiers qui commencent par le texte tapé, puis ceux du plus grand nombre d'établissements.
- « Accessible en transports » garde les établissements à 300 m au plus d'un arrêt Divia.
- La liste est paginée, avec le nombre total de résultats. Un établissement sans coordonnées reste dans la liste, sans point inventé.
- Les noms et adresses des registres, en capitales, sont remis en casse française. Un mot sans voyelle et les sigles les plus courants des registres (« CHU », « EHPAD », « SARL ») restent en capitales. Une enseigne codée (« REF 161529 ») laisse la place à la raison sociale.
- La position cherchée et le texte de « Où ? » passent par des headers, jamais par l'URL : voir la [règle de l'API](backend/api.md#position-du-jeune).

### Établissements visibles en local

L'import crée les établissements invisibles : nous les rendons visibles un par un. En local, après l'import réel ([développement](backend/developpement.md#import-des-établissements)), rendre visibles tous ceux que l'import retient et qui ont une position :

```bash
docker compose -f backend/compose.yml exec postgres psql -U emploigo -d emploigo -c "UPDATE establishments SET visible = TRUE WHERE exclusion_reason IS NULL AND location IS NOT NULL"
```

Pour se connecter après `npm run dev`, voir [Se connecter en local](../README.md#se-connecter-en-local). Vérifier ensuite :

- la carte, la liste et les filtres ;
- un groupement de points ;
- une fiche et son arrêt le plus proche ;
- les suggestions de « Quoi ? » et de « Où ? » ;
- le clavier et le petit écran.

Aucun service cartographique payant ni clé n'est nécessaire.

## Fond et ressources publiques

Le répertoire [`frontend/public/cartes/dijon-20260930-v2`](../frontend/public/cartes/dijon-20260930-v2) contient :

- l'archive PMTiles ;
- le style français clair ;
- les glyphes (polices) et les sprites (icônes) ;
- les licences.

Le [manifeste](../frontend/public/cartes/dijon-20260930-v2/manifest.json) décrit leur provenance, leur couverture, leurs versions et leurs hashes SHA-256.

Ces ressources publiques restent distinctes des établissements, que l'API sert aux jeunes connectés. Si le fond ne charge pas, les fiches HTML restent consultables.

Le moteur de carte et le `style.json` sont demandés ensemble, sans attendre l'un l'autre. Quand le navigateur porte l'indicateur de reconnexion, ils le sont dès l'entrée sur la carte, pendant la vérification de session. Sans indicateur, ils attendent la session confirmée : un visiteur anonyme ne télécharge pas la carte.

### Licence ODbL et attribution

- `dijon.pmtiles` est une base de données dérivée d'OpenStreetMap, mise à disposition sous ODbL 1.0.
- Les données Emploi Go restent dans une couche séparée et ne sont jamais intégrées à l'archive.
- L'attribution « © contributeurs OpenStreetMap » reste visible sur la carte. Elle est définie dans le `style.json` et affichée par le contrôle `AttributionControl` de MapLibre.
- Le crédit Protomaps reste visible avec elle.
- Les données ouvertes affichées sont citées avec leur date de publication, sous Licence Ouverte 2.0 : « DiviaMobilités / Dijon Métropole » pour les arrêts, « Base Adresse Nationale » pour les adresses. La mention accompagne celle du fond de carte : elle reste visible à tout zoom, même quand les arrêts sont masqués. Les logos de Divia ne sont pas repris.
- Les [notices de licence](../frontend/public/cartes/dijon-20260930-v2/licences/README.md) accompagnent les fichiers.

## Mettre à jour la carte

Un nouveau fond est publié dans un nouveau répertoire versionné, nommé `dijon-<date>-v<n>`. `<date>` s'écrit `AAAAMMJJ` et `<n>` est le numéro de version. Dans les commandes ci-dessous, `<dossier>` désigne ce répertoire.

Préparer l'outil :

1. Télécharger le binaire officiel [go-pmtiles 1.31.2](https://github.com/protomaps/go-pmtiles/releases/tag/v1.31.2).
2. Vérifier son SHA-256 annoncé par la release.
3. Conserver l'outil hors Git.
4. Installer [tippecanoe](https://github.com/felt/tippecanoe) 2.79.0 (`brew install tippecanoe`) pour son outil `tile-join`.

Choisir `<date>` : prendre le build le plus récent listé sur <https://docs.protomaps.com/basemaps/downloads>.

Depuis la racine du repo, avec `pmtiles` disponible dans le terminal :

```sh
pmtiles extract https://build.protomaps.com/<date>.pmtiles extrait.pmtiles --bbox=4.866506,47.189555,5.235783,47.427760 --maxzoom=15 --download-threads=4
tile-join -L pois -pk -o frontend/public/cartes/<dossier>/dijon.pmtiles extrait.pmtiles
pmtiles show extrait.pmtiles --header-json > header.json
pmtiles show extrait.pmtiles --metadata > metadata.json
```

`tile-join` retire la couche `pois` (commerces, écoles, pharmacies…) : la carte n'affiche que nos établissements par-dessus le fond, et ces points d'intérêt pèsent 11 % des données des tuiles. Il remplace aussi les bornes et les métadonnées de l'archive : retirer `pois` de `vector_layers` dans `metadata.json`, puis les rétablir.

```sh
pmtiles edit frontend/public/cartes/<dossier>/dijon.pmtiles --header-json=header.json --metadata=metadata.json
pmtiles verify frontend/public/cartes/<dossier>/dijon.pmtiles
pmtiles show frontend/public/cartes/<dossier>/dijon.pmtiles
```

L'extraction distante utilise HTTP Range : ne pas télécharger l'archive mondiale entière. Le [CLI officiel](https://docs.protomaps.com/pmtiles/cli) décrit ces commandes.

Pour le style :

1. Télécharger le paquet exact avec `npm pack @protomaps/basemaps@5.7.2` dans un dossier temporaire, puis l'extraire.
2. Importer son module `dist/esm/index.js`.
3. Conserver la structure du `style.json` local.
4. Remplacer `layers` par `layers('protomaps', namedFlavor('light'), { lang: 'fr' })`, selon [l'API officielle](https://docs.protomaps.com/basemaps/maplibre), en retirant les couches dont `source-layer` vaut `pois`. Les sprites restent nécessaires aux flèches et écussons des routes.
5. Copier les sprites et glyphes du commit d'assets indiqué dans le manifeste, avec leurs notices de licence.
6. Garder les URL de source, glyphes et sprite sous le répertoire versionné.

Si le nom du dossier change, il est référencé dans le code et les tests : rechercher l'ancien nom dans tout le repo et le remplacer partout.

Après une modification volontaire et le formatage des JSON, actualiser puis vérifier les hashes :

```sh
node scripts/map-assets.ts --refresh-manifest
node scripts/map-assets.ts
```

- Le contrôle compare les tailles, les hashes, le header PMTiles et les couches de l'archive et du style.
- Il tourne aussi dans les tests des scripts (`npm test -- scripts`) et en CI.
- Vérifier ensuite l'archive avec le CLI et tester le rendu réel.
- Les [règles de distribution](exploitation.md#fichiers-cartographiques) précisent le comportement HTTP et le cache à vérifier en déploiement.

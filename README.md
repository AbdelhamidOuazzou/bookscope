# BookScope — plateforme de découverte de livres connectée

Projet 2 — Big Data et NoSQL, M2 SLCD (Université Côte d'Azur, 2026–2027).
Rendu le **lundi 9 novembre 2026**, soutenance le **mardi 10 novembre 2026 à 14 h**.

BookScope explore le catalogue Goodbooks-10k à partir des liens entre lecteurs et livres :
livres liés (F1), comparaison de lecteurs (F2), recommandations expliquées (F3), chemins entre
livres (F4), liens entre centres d'intérêt (F5) et import idempotent de notes (F6). Le même code
tourne sur **un serveur MongoDB local** (Partie I) et sur **un cluster MongoDB shardé** de
plusieurs instances sur la même machine (Partie II).

> **État : prototype en Java.** Les six services, les deux déploiements, la comparaison
> automatique et la vérification indépendante fonctionnent sur les vraies données.
> E3 est mesurée. E1, E2, E4, la représentation alternative et le rapport restent à faire
> (voir [Feuille de route](#feuille-de-route)).

## Prérequis

- Java 17+ (Maven est téléchargé automatiquement par `./mvnw`)
- MongoDB 7+ avec `mongod` **et** `mongos` dans le `PATH` (testé avec MongoDB 9.0.2)
- ~1 Go de disque pour les données et les bases

## Démarrage rapide

Dans Git Bash, depuis la racine du projet :

```bash
./mvnw -B -DskipTests package                                  # construit target/bookscope.jar
java -jar target/bookscope.jar download                        # Goodbooks-10k -> data/raw/
java -jar target/bookscope.jar prepare                         # sélection + hold-out -> data/prepared/
java -jar target/bookscope.jar cluster start local             # mongod sur le port 27100
java -jar target/bookscope.jar cluster start sharded           # config + 2 shards + mongos (port 27200)
java -jar target/bookscope.jar load --uri mongodb://localhost:27100
java -jar target/bookscope.jar load --uri mongodb://localhost:27200 --placement range
```

Les `dbpath` sont dans `~/.bookscope/db/` (variable `BOOKSCOPE_DB_ROOT`) : sous Windows, les
noms de fichiers WiredTiger dépassent vite la limite de 260 caractères dans un dossier profond.

## Utilisation

Le déploiement se choisit avec `--uri` (ou `BOOKSCOPE_URI`). Toutes les commandes acceptent
`--json` pour la sortie brute. Dans les exemples, `bookscope` remplace `java -jar target/bookscope.jar`.

```bash
bookscope explore --title "the hobbit"                 # F1 : titre ambigu -> liste des candidats
bookscope explore --id 349                             # F1 : 'Salem's Lot et ses livres liés
bookscope compare 32055 36002                          # F2 : deux lecteurs
bookscope compare 32055 36002 --truncate-a 5           # F2 avec historique tronqué
bookscope recommend 32055                              # F3
bookscope recommend 32055 --truncate 5                 # F3 pour un historique court
bookscope trace 349 7                                  # F4 : 'Salem's Lot -> The Hobbit
bookscope interests vampires romance                   # F5
bookscope update data/prepared/update_batch.jsonl      # F6 (jsonl ou csv)
bookscope records --users 32055 --books 7 --tags       # enregistrements sources
bookscope --uri mongodb://localhost:27200 info         # déploiement, placement, chargement
```

Interface web (mêmes services, avec le choix du déploiement et la commande équivalente affichée) :

```bash
bookscope serve --port 8080                            # puis ouvrir http://localhost:8080
```

Vérifications :

```bash
bookscope compare-deployments                          # local vs shardé, résultats identiques
bookscope verify-csv --f2 32055 36002 --f4 349 7 --check mongodb://localhost:27100
./mvnw -B test
```

Expérience E3 (résultats dans `results/`) :

```bash
bookscope e3 --uri mongodb://localhost:27100 --output results
```

Représentation alternative, un document par lecteur (résultats dans `results/`) :

```bash
bookscope representation --uri mongodb://localhost:27100 --output results
```

Perte d'une instance :

```bash
bookscope cluster stop-shard 1
bookscope --uri mongodb://localhost:27200 compare 32055 36002   # -> status: unavailable
bookscope cluster start-shard 1
```

Déploiement distribué dans Docker (chaque instance dans son conteneur, 1 CPU et 1 Go chacun,
2 ms de délai réseau ajoutés à chaque paquet sortant ; `mongos` sur le port 27300) :

```bash
docker compose -f docker/docker-compose.yml up -d --build      # cfg, shard0, shard1, mongos
bookscope load --uri mongodb://localhost:27300 --placement range
bookscope compare-deployments --a mongodb://localhost:27200 --b mongodb://localhost:27300
bookscope time-deployments --uris mongodb://localhost:27100,mongodb://localhost:27200,mongodb://localhost:27300
docker compose -f docker/docker-compose.yml stop shard1         # panne d'une "machine"
docker compose -f docker/docker-compose.yml start shard1
docker compose -f docker/docker-compose.yml down -v             # tout supprimer
```

## Données et sélection

| | |
|---|---|
| Règle de sélection (par défaut) | lecteurs avec `user_id` de 30000 à 39999 (`prepare --readers` pour en changer) |
| Lecteurs | 10 000 |
| Notes stockées | 1 084 334 (après hold-out) |
| Livres notés / catalogue | 9 999 / 10 000 |
| Listes de lecture (`to_read`) | 184 504 |
| Tags / liens livre–tag | 34 250 / 999 898 |
| Jeu d'évaluation (E3) | 6 012 notes : les 3 plus récentes de ~20 % des lecteurs |
| Lot de mise à jour (F6) | 1 058 notes : les 2 plus récentes de ~5 % d'autres lecteurs |

Les deux jeux mis de côté sont disjoints (lecteurs différents, répartis par
`sha1("bookscope:<user_id>") % 100`) et retirés **avant** tout chargement. L'ordre des lignes de
`ratings.csv` sert d'ordre temporel relatif (`seq`).

Notes par lecteur : min 19, médiane 108, p95 153, max 200.
Notes par livre : min 1, médiane 46, p95 378, max 4 273.

Les lecteurs gardent tout leur historique, mais seuls leurs liens restent visibles : deux livres
reliés surtout par des lecteurs hors de cette plage paraîtront moins liés, et le livre 7803 n'a
aucune note dans la sélection.

Anomalies relevées automatiquement dans `data/prepared/report.json` (avec identifiants) :

| Anomalie | Nombre | Exemple |
|---|---:|---|
| Livres sans `language_code` | 1 084 | book_id 45 |
| Livres sans `isbn` | 700 | book_id 106 |
| Livres sans `original_title` | 585 | book_id 75 |
| Livres sans année de publication | 21 | book_id 220 |
| Titres partagés par plusieurs livres | 33 | « 'Salem's Lot » : 349 et 1292 |
| Paires (livre, tag) en double dans `book_tags.csv` | 8 | ligne 211 : goodreads 3, tag `owned` (6 065 / 6 066) |
| Comptes de tags ≤ 0 | 6 | ligne 922 053 : book 1935, count −1 |
| Tags de statut (to-read, owned, favorites…) | 994 | `to-read` sur 9 983 livres |
| Livres à la fois notés et dans `to_read` du même lecteur | 460 | user 30126, book 2 |

## Architecture

```
src/main/java/bookscope/
  Main.java      interface en ligne de commande
  Store.java     connexion et requêtes MongoDB
  Catalog.java   catalogue en mémoire, pondérations
  Explore.java   F1     Compare.java    F2     Recommend.java  F3
  Trace.java     F4     Interests.java  F5     Update.java     F6 + traçabilité
  tools/
    DownloadData  PrepareData  LoadData  Cluster
    CompareDeployments  VerifyCsv  ExperimentE3
src/test/java/bookscope/ServicesTest.java
docs/DESIGN.md   choix de modélisation, placement, premiers résultats
results/         mesures brutes des expériences
```

Détails des choix (modèle, index, placement, politique F6, comportement en panne) :
[docs/DESIGN.md](docs/DESIGN.md).

## Feuille de route

- [ ] Partie I : mesurer une représentation alternative (ex. historique embarqué dans un
      document lecteur, ou relations « co-liked » précalculées) sur ≥ 2 requêtes
- [ ] Partie II : configuration alternative (`--placement hashed` ou `--shards 3`) et analyse
      des instances contactées pour une requête lecteur, une requête livre et F4
- [ ] Panne : réponse F2/F3 plus fine (lecteur intact vs lecteur partiellement perdu)
- [ ] E1 : trois tailles (dont les 6 M de notes : `prepare --all-readers`)
- [ ] E2 : déposer la prédiction **avant** de mesurer ; charges personnalisée / exploration
- [x] E3 : précision / rappel@k vs baseline popularité, historiques courts (k=5) et complets (à analyser dans le rapport)
- [ ] E4 (bonus) : cas difficile — piste : `dystopia` / `young-adult` en F5, où les livres
      portant les deux tags sont exclus des deux côtés
- [ ] Rapport (≤ 10 pages) et soutenance

## Données

Goodbooks-10k, Zygmunt Zając — https://github.com/zygmuntz/goodbooks-10k. Les fichiers CSV,
les données préparées et les bases ne sont pas versionnés.

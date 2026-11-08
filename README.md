# BookScope — plateforme de découverte de livres connectée

Projet 2 — Big Data et NoSQL, M2 SLCD (Université Côte d'Azur, 2026–2027).
Rendu le **lundi 9 novembre 2026**, soutenance le **mardi 10 novembre 2026 à 14 h**.

BookScope explore le catalogue Goodbooks-10k à partir des liens entre lecteurs et livres :
livres liés (F1), comparaison de lecteurs (F2), recommandations expliquées (F3), chemins entre
livres (F4), liens entre centres d'intérêt (F5) et import idempotent de notes (F6). Le même code
tourne sur **un serveur MongoDB local** (Partie I) et sur **un cluster MongoDB shardé** de
plusieurs instances sur la même machine (Partie II).

> **État : version rendue.** Les six services, le serveur seul, le cluster de deux shards
> (plages et hachage), la version Docker, la comparaison automatique et la vérification
> indépendante fonctionnent sur les vraies données. E1 à E4 et la représentation alternative
> sont mesurées ; les mesures brutes sont dans `results/`.
>
> Auteurs : Amine Kerfai et Abdelhamid Ouazzou.

## Documents

| Fichier | Contenu |
|---|---|
| [docs/Rapport_BookScope.pdf](docs/Rapport_BookScope.pdf) | le rapport (source : `docs/rapport_bookscope.tex`) |
| [docs/BookScope_soutenance.pptx](docs/BookScope_soutenance.pptx) | la présentation de la soutenance |
| [docs/DESIGN.md](docs/DESIGN.md) | les choix de conception et les premiers résultats |
| `results/` | les mesures brutes (CSV) et leurs résumés (JSON) |

## Prérequis

- Java 17+ (Maven est téléchargé automatiquement par `./mvnw`)
- MongoDB 7+ avec `mongod` **et** `mongos` dans le `PATH` (testé avec MongoDB 9.0.2)
- ~1 Go de disque pour les données et les bases (environ 3 Go pour E1 et E2 avec toutes les notes)
- Facultatif : Docker Desktop, pour la version du cluster en conteneurs

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

Expérience E4 (F5, cas difficile, résultats dans `results/`) :

```bash
bookscope e4 --uri mongodb://localhost:27100 --output results
bookscope interests dystopia young-adult --adjust-shared      # nouvelle règle (par défaut : ancienne)
```

Représentation alternative, un document par lecteur (résultats dans `results/`) :

```bash
bookscope representation --uri mongodb://localhost:27100 --output results   # representation_*
bookscope alt-model --uri mongodb://localhost:27100 --requests 200          # alt_model_* (seconde mesure)
```

Expériences E1 et E2 (outil `bench`, résultats `results/bench_<label>_*`). Chaque taille est
préparée dans son propre dossier puis chargée à la place de la sélection courante :

```bash
# E1 : trois tailles sur le serveur seul, lots de 30 requêtes
bookscope prepare --readers 30000 39999 --output data/prepared_s
bookscope prepare --readers 20000 49999 --output data/prepared_m
bookscope prepare --all-readers --output data/prepared_l
bookscope load --uri mongodb://localhost:27100 --prepared data/prepared_s
bookscope bench --uri mongodb://localhost:27100 --label e1_s --shares 1,0 --requests 30
#   puis la même chose avec data/prepared_m (e1_m) et data/prepared_l (e1_l)

# E2 : jeu complet, trois configurations, lots de 40 requêtes
bookscope bench --uri mongodb://localhost:27100 --label e2_local --requests 40
bookscope load --uri mongodb://localhost:27200 --placement range --prepared data/prepared_l
bookscope bench --uri mongodb://localhost:27200 --label e2_range --requests 40
bookscope load --uri mongodb://localhost:27200 --placement hashed --prepared data/prepared_l
bookscope bench --uri mongodb://localhost:27200 --label e2_hashed --requests 40
```

Après E1 et E2, recharger la sélection de 10 000 lecteurs (`load` sans `--prepared`) avant de
relancer les services, E3 ou E4, qui s'appuient sur `data/prepared/`.

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
  WebServer.java interface web (page dans src/main/resources/web/index.html)
  tools/
    DownloadData  PrepareData  LoadData  Cluster
    CompareDeployments  TimeDeployments  VerifyCsv
    ExperimentE3  ExperimentE4  ExperimentRepresentation  AltModel  Bench
src/test/java/bookscope/ServicesTest.java
docker/          cluster en conteneurs (docker-compose.yml)
docs/            rapport, présentation, DESIGN.md
results/         mesures brutes des expériences
```

Détails des choix (modèle, index, placement, politique F6, comportement en panne) :
[docs/DESIGN.md](docs/DESIGN.md).

## Feuille de route

- [x] Les six services F1 à F6, en ligne de commande et dans l'interface web
- [x] Partie I : représentation alternative (un document par lecteur) mesurée sur 2 requêtes ;
      la représentation actuelle est gardée
- [x] Partie II : deux shards par plages, seconde répartition par hachage, version Docker ;
      résultats identiques (14/14) sur tous les déploiements
- [x] Panne d'une instance : `unavailable`, sans résultat partiel
- [x] E1 : trois tailles (1,08 M, 3,27 M et 5,94 M de notes)
- [x] E2 : prédiction, puis serveur seul, plages et hachage avec cinq mélanges des deux charges
- [x] E3 : précision et rappel à 10 contre la popularité, historiques complets et tronqués à 5
- [x] E4 (bonus) : `dystopia` / `young-adult` en F5, correction `--adjust-shared` mesurée sur
      14 paires, cas non résolu (lift 0,65 → 0,76)
- [x] Vérification indépendante depuis les CSV (F2 et F4)
- [x] Rapport et présentation
- [ ] Piste : enregistrer le nombre de lecteurs au chargement, pour que F2 réponde en cas de
      panne quand les deux lecteurs sont sur le shard en vie

## Données

Goodbooks-10k, Zygmunt Zając — https://github.com/zygmuntz/goodbooks-10k. Les fichiers CSV,
les données préparées et les bases ne sont pas versionnés.

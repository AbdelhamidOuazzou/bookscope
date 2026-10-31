# BookScope — plateforme de découverte de livres connectée

Projet 2 — Big Data et NoSQL, M2 SLCD (Université Côte d'Azur, 2026–2027).
Rendu le **vendredi 6 novembre 2026**, soutenance le **mardi 10 novembre 2026 à 14 h**.

BookScope explore le catalogue Goodbooks-10k à partir des liens entre lecteurs et livres :
livres liés (F1), comparaison de lecteurs (F2), recommandations expliquées (F3), chemins entre
livres (F4), liens entre centres d'intérêt (F5) et import idempotent de notes (F6). Le même code
tourne sur **un serveur MongoDB local** (Partie I) et sur **un cluster MongoDB shardé** de
plusieurs instances sur la même machine (Partie II).

> **État : premier prototype.** Les six services, les deux déploiements, la comparaison
> automatique et la vérification indépendante fonctionnent sur les vraies données.
> Les expériences E1–E4, la mesure d'une représentation alternative et le rapport restent à faire
> (voir [Feuille de route](#feuille-de-route)).

## Prérequis

- Python 3.10+ et `pip install -r requirements.txt` (seulement `pymongo`)
- MongoDB 7+ avec `mongod` **et** `mongos` dans le `PATH` (testé avec MongoDB 9.0.2)
- ~1 Go de disque pour les données et les bases

## Démarrage rapide

```bash
pip install -r requirements.txt
python scripts/download_data.py                  # Goodbooks-10k -> data/raw/
python scripts/prepare_data.py                   # sélection + hold-out -> data/prepared/
python scripts/cluster.py start local            # mongod sur le port 27100
python scripts/cluster.py start sharded          # config + 2 shards + mongos (port 27200)
python scripts/load_data.py --uri mongodb://localhost:27100
python scripts/load_data.py --uri mongodb://localhost:27200 --placement range
```

Les `dbpath` sont dans `~/.bookscope/db/` (variable `BOOKSCOPE_DB_ROOT`) : sous Windows, les
noms de fichiers WiredTiger dépassent vite la limite de 260 caractères dans un dossier profond.

## Utilisation

Le déploiement se choisit avec `--uri` (ou `BOOKSCOPE_URI`). Toutes les commandes acceptent
`--json` pour la sortie brute.

```bash
python -m bookscope explore --title "the hobbit"       # F1 : titre ambigu -> liste des candidats
python -m bookscope explore --id 349                   # F1 : 'Salem's Lot et ses livres liés
python -m bookscope compare 7 3022                     # F2 : deux lecteurs
python -m bookscope compare 7 3022 --truncate-a 5      # F2 avec historique tronqué
python -m bookscope recommend 7                        # F3
python -m bookscope recommend 7 --truncate 5           # F3 pour un historique court
python -m bookscope trace 349 7                        # F4 : 'Salem's Lot -> The Hobbit
python -m bookscope interests vampires romance         # F5
python -m bookscope update data/prepared/update_batch.jsonl   # F6 (jsonl ou csv)
python -m bookscope records --users 7 --books 914 --tags      # enregistrements sources
python -m bookscope --uri mongodb://localhost:27200 info      # déploiement, placement, chargement
```

Vérifications :

```bash
python scripts/compare_deployments.py                  # local vs shardé, résultats identiques
python scripts/verify_csv.py --f2 7 3022 --f4 349 7 --check mongodb://localhost:27100
python -m unittest discover tests
```

Perte d'une instance :

```bash
python scripts/cluster.py stop-shard 1
python -m bookscope --uri mongodb://localhost:27200 compare 7 48077   # -> status: unavailable
python scripts/cluster.py start-shard 1
```

## Données et sélection

| | |
|---|---|
| Règle de sélection (par défaut) | lecteurs avec `user_id % 5 == 2` — **à faire enregistrer auprès de l'enseignante** (`--modulo/--remainder` pour en changer) |
| Lecteurs | 10 685 |
| Notes stockées | 1 190 937 (après hold-out) |
| Livres notés / catalogue | 9 999 / 10 000 |
| Listes de lecture (`to_read`) | 183 865 |
| Tags / liens livre–tag | 34 250 / 999 898 |
| Jeu d'évaluation (E3) | 6 303 notes : les 3 plus récentes de ~20 % des lecteurs |
| Lot de mise à jour (F6) | 1 040 notes : les 2 plus récentes de ~5 % d'autres lecteurs |

Les deux jeux mis de côté sont disjoints (lecteurs différents, répartis par
`sha1("bookscope:<user_id>") % 100`) et retirés **avant** tout chargement. L'ordre des lignes de
`ratings.csv` sert d'ordre temporel relatif (`seq`).

Notes par lecteur : min 21, médiane 110, p95 156, max 193.
Notes par livre : min 1, médiane 50, p95 420, max 4 635.

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
| Livres à la fois notés et dans `to_read` du même lecteur | 465 | user 662, book 45 |

## Architecture

```
bookscope/
  store.py       connexion, modèle de données, catalogue en mémoire, pondérations
  explore.py     F1     compare.py  F2     recommend.py  F3
  trace.py       F4     interests.py F5    update.py     F6 + traçabilité
  cli.py         interface en ligne de commande
scripts/
  download_data.py  prepare_data.py  load_data.py  cluster.py
  compare_deployments.py  verify_csv.py
tests/test_services.py
docs/DESIGN.md   choix de modélisation, placement, premiers résultats
```

Détails des choix (modèle, index, placement, politique F6, comportement en panne) :
[docs/DESIGN.md](docs/DESIGN.md).

## Feuille de route

- [ ] Faire enregistrer la règle de sélection (aucun autre groupe ne doit avoir la même)
- [ ] Partie I : mesurer une représentation alternative (ex. historique embarqué dans un
      document lecteur, ou relations « co-liked » précalculées) sur ≥ 2 requêtes
- [ ] Partie II : configuration alternative (`--placement hashed` ou `--shards 3`) et analyse
      des instances contactées pour une requête lecteur, une requête livre et F4
- [ ] Panne : réponse F2/F3 plus fine (lecteur intact vs lecteur partiellement perdu)
- [ ] E1 : trois tailles (dont les 6 M de notes : `prepare_data.py --all-readers`)
- [ ] E2 : déposer la prédiction **avant** de mesurer ; charges personnalisée / exploration
- [ ] E3 : précision / rappel@k vs baseline popularité, historiques courts (k=5) et complets
- [ ] E4 (bonus) : cas difficile — piste : `dystopia` / `young-adult` en F5, où les livres
      portant les deux tags sont exclus des deux côtés
- [ ] Rapport (≤ 10 pages) et soutenance

## Données

Goodbooks-10k, Zygmunt Zając — https://github.com/zygmuntz/goodbooks-10k. Les fichiers CSV,
les données préparées et les bases ne sont pas versionnés.

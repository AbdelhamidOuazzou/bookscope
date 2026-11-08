# BookScope

Projet 2 de Big Data et NoSQL (M2 SLCD, Université Côte d'Azur, 2026-2027).
Amine Kerfai et Abdelhamid Ouazzou.

BookScope permet d'explorer le jeu de données Goodbooks-10k à partir des liens entre lecteurs
et livres. Il propose six services :

- F1 : explorer un livre et ses livres liés
- F2 : comparer deux lecteurs
- F3 : recommander des livres à un lecteur, avec une explication
- F4 : trouver un chemin entre deux livres
- F5 : relier deux centres d'intérêt (tags)
- F6 : importer un lot de notes

Le même code tourne sur un serveur MongoDB seul (partie I) et sur un cluster shardé avec deux
shards (partie II).

Le rapport est dans `docs/Rapport_BookScope.pdf` et la présentation dans
`docs/BookScope_soutenance.pptx`. Les choix de conception sont détaillés dans `docs/DESIGN.md`,
et les mesures brutes des expériences sont dans `results/`.

## Prérequis

- Java 17 ou plus récent (Maven est téléchargé par `./mvnw`)
- MongoDB 7 ou plus récent, avec `mongod` et `mongos` dans le `PATH` (nous avons utilisé 9.0.2)
- environ 1 Go de disque, ou 3 Go pour refaire E1 et E2
- Docker Desktop, seulement pour la version du cluster en conteneurs

## Installation

Dans Git Bash, à la racine du projet :

```bash
./mvnw -B -DskipTests package
java -jar target/bookscope.jar download
java -jar target/bookscope.jar prepare
java -jar target/bookscope.jar cluster start local
java -jar target/bookscope.jar cluster start sharded
java -jar target/bookscope.jar load --uri mongodb://localhost:27100
java -jar target/bookscope.jar load --uri mongodb://localhost:27200 --placement range
```

Ces commandes font, dans l'ordre :

1. compiler le projet ;
2. télécharger les CSV ;
3. choisir les lecteurs 30000 à 39999 et mettre de côté les jeux d'évaluation et de mise à jour ;
4. lancer le serveur seul, sur le port 27100 ;
5. lancer le cluster, avec mongos sur le port 27200 ;
6. charger les données dans les deux déploiements.

Les bases sont créées dans `~/.bookscope/db/`, qu'on peut changer avec `BOOKSCOPE_DB_ROOT`.
On les a mises là parce que sous Windows, WiredTiger dépasse vite la limite de 260 caractères
pour les chemins de fichiers.

## Utilisation

Dans les exemples, `bookscope` veut dire `java -jar target/bookscope.jar`. Le déploiement se
choisit avec `--uri`, et `--json` donne la sortie brute.

```bash
bookscope explore --title "the hobbit"           # F1, avec un titre ambigu
bookscope explore --id 349                       # F1
bookscope compare 32055 36002                    # F2
bookscope recommend 32055                        # F3
bookscope recommend 32055 --truncate 5           # F3, en ne gardant que les 5 premières notes
bookscope trace 349 7                            # F4
bookscope interests vampires romance             # F5
bookscope update data/prepared/update_batch.jsonl  # F6 (jsonl ou csv)
bookscope records --users 32055 --books 7 --tags # les enregistrements d'origine
bookscope --uri mongodb://localhost:27200 info
```

Il y a aussi une interface web, qui propose les mêmes services :

```bash
bookscope serve --port 8080
```

Ensuite, ouvrir http://localhost:8080 dans un navigateur.

## Vérifications

```bash
bookscope compare-deployments      # vérifie que le serveur seul et le cluster donnent les mêmes résultats
bookscope verify-csv --f2 32055 36002 --f4 349 7 --check mongodb://localhost:27100
./mvnw -B test
```

`verify-csv` recalcule F2 et F4 directement à partir des CSV, sans passer par la base.

## Expériences

E3 et E4 :

```bash
bookscope e3 --uri mongodb://localhost:27100 --output results
bookscope e4 --uri mongodb://localhost:27100 --output results
```

La représentation alternative, avec un document par lecteur :

```bash
bookscope representation --uri mongodb://localhost:27100 --output results
bookscope alt-model --uri mongodb://localhost:27100 --requests 200
```

E1 et E2. Chaque taille de données est préparée dans son propre dossier :

```bash
bookscope prepare --readers 30000 39999 --output data/prepared_s
bookscope prepare --readers 20000 49999 --output data/prepared_m
bookscope prepare --all-readers --output data/prepared_l

# E1 : serveur seul, lots de 30 requêtes (même chose ensuite avec prepared_m et prepared_l)
bookscope load --uri mongodb://localhost:27100 --prepared data/prepared_s
bookscope bench --uri mongodb://localhost:27100 --label e1_s --shares 1,0 --requests 30

# E2 : jeu complet, lots de 40 requêtes
bookscope bench --uri mongodb://localhost:27100 --label e2_local --requests 40
bookscope load --uri mongodb://localhost:27200 --placement range --prepared data/prepared_l
bookscope bench --uri mongodb://localhost:27200 --label e2_range --requests 40
bookscope load --uri mongodb://localhost:27200 --placement hashed --prepared data/prepared_l
bookscope bench --uri mongodb://localhost:27200 --label e2_hashed --requests 40
```

Après E1 et E2, il faut recharger la sélection normale (`load` sans `--prepared`) avant de
relancer les services, E3 ou E4.

## Panne d'un shard

```bash
bookscope cluster stop-shard 1
bookscope --uri mongodb://localhost:27200 compare 32055 36002   # renvoie "unavailable"
bookscope cluster start-shard 1
```

## Cluster dans Docker

Chaque instance tourne dans son propre conteneur, avec 1 CPU, 1 Go de mémoire et 2 ms de délai
réseau. mongos écoute sur le port 27300.

```bash
docker compose -f docker/docker-compose.yml up -d --build
bookscope load --uri mongodb://localhost:27300 --placement range
bookscope time-deployments --uris mongodb://localhost:27100,mongodb://localhost:27200,mongodb://localhost:27300
docker compose -f docker/docker-compose.yml stop shard1     # simule la panne d'une machine
docker compose -f docker/docker-compose.yml down -v
```

## Organisation du code

```
src/main/java/bookscope/
  Main.java          ligne de commande
  Store.java         accès à MongoDB
  Catalog.java       catalogue gardé en mémoire
  Explore.java (F1), Compare.java (F2), Recommend.java (F3),
  Trace.java (F4), Interests.java (F5), Update.java (F6)
  WebServer.java     interface web
  tools/             téléchargement, préparation, chargement, cluster, expériences
docker/              cluster en conteneurs
docs/                rapport, présentation, DESIGN.md
results/             mesures brutes
```

## Données

Goodbooks-10k de Zygmunt Zając : https://github.com/zygmuntz/goodbooks-10k.
Les CSV et les bases ne sont pas dans le dépôt : `download` et `load` les recréent.

## Dépôt

Le code et son historique sont aussi sur GitHub : https://github.com/AbdelhamidOuazzou/bookscope

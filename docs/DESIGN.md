# BookScope — choix de conception (prototype)

## Technologie

**Java 17** avec le driver officiel MongoDB et **MongoDB** (serveur `mongod`, accès réseau). Les données sont naturellement des documents
(livre + ses tags) et des faits lecteur–livre ; MongoDB offre aussi un sharding natif
(`mongos` + shards + serveur de configuration), ce qui permet de garder **le même code** pour les
deux déploiements : seul l'URI change.

## Modèle de données (base `bookscope`)

| Collection | Contenu | Clé / index | Distribution (Partie II) |
|---|---|---|---|
| `books` | métadonnées + `tags: [{t, n}]` triés par compte | `_id = book_id`, `tags.t` | non shardée |
| `tags` | nom, `df` (nb de livres), `total`, `status_like` | `_id = tag_id`, `name` | non shardée |
| `ratings` | `user_id, book_id, rating, seq` | `_id = "u:b"` ; `(book_id, rating, user_id)`, `(user_id, rating, book_id)`, `(user_id, seq)` | shardée par `user_id` |
| `to_read` | `user_id, book_id, seq` | `_id = "u:b"`, `book_id`, `user_id` | shardée par `user_id` |
| `user_stats` | `n, sum` par lecteur | `_id = user_id` | shardée par `_id` (même découpage) |
| `book_stats` | `n, sum, n_pos` par livre | `_id = book_id` | non shardée |
| `meta`, `rating_changes` | compteur `seq`, infos de chargement, audit F6 | | non shardées |

- **Identité d'une note** : la paire `(user_id, book_id)`, matérialisée par `_id = "u:b"`.
- Les index composés couvrent les deux accès dominants : « qui a aimé ce livre ? »
  (`book_id, rating`) et « que ce lecteur a-t-il aimé ? » (`user_id, rating`).
- `book_stats` et `user_stats` sont des agrégats précalculés au chargement et **maintenus par
  F6** (incréments), autorisés par le sujet.
- Le catalogue (10 000 livres) est lu une fois par processus ; l'activité des lecteurs est
  toujours interrogée sur le serveur.

## Pondérations communes

- Note positive : `rating >= 4`.
- Poids d'un livre (F2) : `log((N_lecteurs + 1) / (n_livre + 1))` — s'accorder sur un best-seller
  compte moins que sur un livre rare.
- Tag **générique** : tag de statut (`to-read`, `owned`, `favorites`, `read-in-2016`…) ou porté
  par ≥ 50 % des livres. Les tags génériques ne servent jamais de preuve d'un lien.
- Vecteurs de tags : `log(1 + n) × idf` sur les tags spécifiques, normalisés L2.

## Services

| | Méthode | Garde-fous demandés par le sujet |
|---|---|---|
| F1 | lecteurs communs (cosinus + lift sur les « likers ») et similarité de tags spécifiques | titre ambigu → liste, jamais de choix silencieux ; chaque lien porte sa base (`shared readership` / `specific tags only` / `generic tags only`) et le nombre de livres reliés seulement par des tags génériques |
| F2 | corrélation de Pearson pondérée sur notes centrées, × `min(commun, 25)/25` | centrage par lecteur (niveaux d'activité/sévérité), poids anti-popularité, statut `insufficient_overlap` sous 5 livres communs |
| F3 | filtrage collaboratif par lecteurs voisins (50), similarité normalisée par `sqrt(activité)`, pénalité `n_pos^0.35` | historiques < 10 notes : mélange 50/50 avec le profil de tags ; troncature signalée ; part dans le top 100 et médiane de popularité rapportées |
| F4 | lecteurs communs (lift vs indépendance, pondération 1/log2 de l'activité) puis chemins à 2 sauts via un livre intermédiaire | livres « hubs » (1 % les plus notés) pénalisés ; verdict explicite « no convincing connection » avec raisons |
| F5 | appartenance d'un livre = tag dans ses 10 premiers tags de contenu et ≥ 20 fois ; lecteurs *attachés* (part de likes ≥ 2× la moyenne) ; lift directionnel A→B contre l'attendu hors A | recalcul sans les 1 % de livres les plus notés ni les lecteurs au-dessus du p95 d'activité |
| F6 | upsert par `(user_id, book_id)` ; la dernière note importée gagne ; l'ancienne va dans `rating_changes` | ré-import sans effet ; lignes invalides rejetées avec raison ; stats mises à jour |

**Traçabilité** : chaque résultat contient les identifiants qui le justifient (lecteurs, livres,
comptes de tags) et `java -jar target/bookscope.jar records --users … --books … --tags` liste les
enregistrements stockés correspondants.

## Placement (Partie II)

Configuration par défaut (`--placement range`, 2 shards) : l'activité des lecteurs est découpée
en **plages contiguës de `user_id`** choisies pour que chaque shard ait le même nombre de notes
(coupure à `user_id = 35004`). Le balancer est arrêté pour garder ce placement fixe. Tout
l'historique d'un lecteur est donc sur une seule instance.

| Requête | Instances contactées | Travail local | Échanges |
|---|---|---|---|
| Lecteur (F2, historique de F3) | le shard du lecteur (ciblage par `user_id`) | tout | aucun entre shards |
| Livre (« qui a aimé X ? ») | tous les shards (scatter-gather) | filtrage par index `book_id` | listes de `user_id` vers `mongos` |
| F4 / F1 (lecteurs → livres) | tous les shards, deux fois | `$group` partiel par shard | sous-totaux fusionnés par `mongos` |

Alternative prévue : `--placement hashed` (hachage de `user_id`) ou `--shards 3`.

## Panne d'une instance

`mongos` refuse une requête qui doit toucher un shard indisponible (erreur 133), et BookScope
répond `status: unavailable` sans aucun résultat partiel. Avec un seul membre par replica set,
rien n'est récupérable tant que le shard est arrêté ; un vrai déploiement répliquerait chaque
shard (3 membres) pour basculer sur un secondaire. Les instances partagent ici CPU, mémoire et
disque : ce n'est pas un cluster de machines indépendantes.

## Déploiement dans Docker (08/11/2026)

Limite constatée : sur une seule machine, les instances partagent processeur, mémoire et disque, et
les échanges entre `mongos` et les shards ne passent pas par un vrai réseau. Pour s'en rapprocher,
`docker/docker-compose.yml` place chaque instance (config, shard0, shard1, `mongos`) dans un conteneur
limité à 1 CPU et 1 Go, et ajoute 2 ms à chaque paquet sortant (`tc netem`), soit environ 4 ms par
aller-retour. Même image MongoDB 9.0, même placement par plages (coupure à 35004).

- `compare-deployments` entre le cluster local (27200) et Docker (27300) : **14/14 identiques**.
- Temps médians sur 5 exécutions (ms, catalogue déjà chargé, `results/deployments_timing.csv`) :

| Requête | Local 1 serveur | 2 shards, même machine | 2 shards, Docker |
|---|---|---|---|
| F2 même shard 32305/33065 | 14 | 11 | 46 |
| F2 deux shards 32055/36002 | 10 | 12 | 44 |
| F3 lecteur 32055 | 283 | 234 | 432 |
| F4 349 → 7 | 428 | 474 | 600 |
| F5 vampires/romance | 1 216 | 1 128 | 962 |

  Les requêtes courtes (F2) sont dominées par les allers-retours réseau : environ 4 fois plus lentes
  dans Docker. Les requêtes longues (F5) dépendent surtout du calcul et ne ralentissent pas.
- Conteneur `shard1` arrêté : F2 (même sur shard0), F3 renvoient `unavailable` après 16 s
  (erreur 133, aucun primaire pour `shard1`) ; après redémarrage, la même requête répond en 1,1 s.
- Ce n'est toujours pas un vrai cluster : les conteneurs partagent le disque de la machine, et une
  panne de la machine les arrête tous.

## Premiers résultats (lecteurs 30000 à 39999, 07/11/2026)

- Chargement : 27 s en local, 42 s via `mongos` (2 shards) pour 1,08 M de notes.
- `compare-deployments` : **14/14 requêtes identiques**, dont des lecteurs sur des
  shards différents (32055 / 36002).
- `verify-csv` : F2 (32055 / 36002) et F4 (349 → 7) recalculés depuis les CSV,
  **identiques** sur les deux déploiements.
- Shard 1 arrêté : F2 (32055 / 36002) et F3 (32055) renvoient `unavailable`.
- Matériel : Windows 11, 8 cœurs, 16 Go ; MongoDB 9.0.2, cache WiredTiger 0,5 Go par `mongod` ;
  Java 17 (Temurin), mongodb-driver-sync 5.1.4.

## E3 : qualité des recommandations (07/11/2026)

1 870 lecteurs éligibles (au moins une note cachée ≥ 4), listes de 10 livres. Valeurs : moyenne ± erreur
type. Mesures brutes : `results/e3_raw.csv`.

| Méthode | Précision@10 | Rappel@10 | Au moins un bon livre | Part du top 100 |
|---|---|---|---|---|
| F3, historique complet | 0,0143 ± 0,0011 | 0,0615 ± 0,0045 | 11,0 % ± 0,7 | 9,3 % |
| F3, 5 premières notes | 0,0052 ± 0,0006 | 0,0242 ± 0,0028 | 4,8 % ± 0,5 | 17,1 % |
| F3, 5 premières notes, sans tags | 0,0051 ± 0,0006 | 0,0235 ± 0,0027 | 4,7 % ± 0,5 | 18,3 % |
| Popularité | 0,0043 ± 0,0005 | 0,0183 ± 0,0024 | 3,8 % ± 0,4 | 100 % |

Les comptes de tags couvrent tout Goodreads et peuvent inclure la période cachée. Les retirer change très
peu le score sur les historiques tronqués, ce qui limite l'effet de cette fuite possible sur cette mesure.

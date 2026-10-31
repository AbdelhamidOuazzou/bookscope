# BookScope — choix de conception (prototype)

## Technologie

**MongoDB** (serveur `mongod`, accès réseau). Les données sont naturellement des documents
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
comptes de tags) et `python -m bookscope records --users … --books … --tags` liste les
enregistrements stockés correspondants.

## Placement (Partie II)

Configuration par défaut (`--placement range`, 2 shards) : l'activité des lecteurs est découpée
en **plages contiguës de `user_id`** choisies pour que chaque shard ait le même nombre de notes
(coupure à `user_id = 25952`). Le balancer est arrêté pour garder ce placement fixe. Tout
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

## Premiers résultats (prototype, 31/10/2026)

- Chargement : 24 s en local, 38 s via `mongos` (2 shards) pour 1,19 M de notes.
- `scripts/compare_deployments.py` : **14/14 requêtes identiques**, dont des lecteurs sur des
  shards différents (7 / 48077, 2552 / 39052).
- `scripts/verify_csv.py` : F2 (7 / 3022) et F4 (349 → 7) recalculés depuis les CSV, **identiques**.
- Shard 1 arrêté : F2 (7 / 48077) et F3 (7) renvoient `unavailable` après ~15 s.
- Matériel : Windows 11, 8 cœurs, 16 Go ; MongoDB 9.0.2, cache WiredTiger 0,5 Go par `mongod` ;
  Python 3.12, pymongo 4.19.

#!/usr/bin/env python3
"""Load the prepared JSON Lines files into a BookScope deployment.

  python scripts/load_data.py --uri mongodb://localhost:27100                      # local server
  python scripts/load_data.py --uri mongodb://localhost:27200 --placement range    # via mongos
  python scripts/load_data.py --uri mongodb://localhost:27200 --placement hashed   # alternative

Placement (sharded deployment only). Reader activity (ratings, to_read, user_stats) is
partitioned by user_id so that a reader's whole history lives on one instance:
  range   contiguous user_id ranges, split points chosen so that every shard holds about
          the same number of ratings; the balancer is stopped to keep the placement fixed.
  hashed  hashed user_id, chunks spread and balanced by MongoDB.
The catalogue (books, tags, book_stats) is small and is stored unsharded.
"""
import argparse
import json
import sys
import time
from collections import Counter
from pathlib import Path

from bson.min_key import MinKey
from pymongo import ASCENDING, MongoClient

DB_NAME = 'bookscope'
BATCH = 20000
ACTIVITY = ('ratings', 'to_read', 'user_stats')


def read_jsonl(path):
    with path.open(encoding='utf-8') as f:
        for line in f:
            yield json.loads(line)


def insert(coll, docs):
    batch, n = [], 0
    for doc in docs:
        batch.append(doc)
        if len(batch) == BATCH:
            coll.insert_many(batch, ordered=False)
            n += len(batch)
            batch = []
    if batch:
        coll.insert_many(batch, ordered=False)
        n += len(batch)
    return n


def with_pair_id(docs):
    for d in docs:
        d['_id'] = f"{d['user_id']}:{d['book_id']}"
        yield d


def split_points(prepared, n_shards):
    """user_id boundaries giving each shard about the same number of ratings."""
    per_user = Counter(d['user_id'] for d in read_jsonl(prepared / 'ratings.jsonl'))
    total, cum, points = sum(per_user.values()), 0, []
    for user_id in sorted(per_user):
        if len(points) < n_shards - 1 and cum >= total * (len(points) + 1) / n_shards:
            points.append(user_id)
        cum += per_user[user_id]
    return points


def shard_collections(client, prepared, placement):
    admin = client.admin
    shards = sorted(s['_id'] for s in admin.command('listShards')['shards'])
    admin.command('enableSharding', DB_NAME, primaryShard=shards[0])
    keys = {'ratings': 'user_id', 'to_read': 'user_id', 'user_stats': '_id'}
    if placement == 'hashed':
        admin.command('balancerStart')
        for coll, field in keys.items():
            admin.command('shardCollection', f'{DB_NAME}.{coll}', key={field: 'hashed'})
        return {'placement': 'hashed', 'shards': shards}
    admin.command('balancerStop')
    points = split_points(prepared, len(shards))
    for coll, field in keys.items():
        ns = f'{DB_NAME}.{coll}'
        admin.command('shardCollection', ns, key={field: 1})
        for p in points:
            admin.command('split', ns, middle={field: p})
        lowers = [MinKey()] + points
        for shard, lower in zip(shards, lowers):
            try:
                admin.command('moveChunk', ns, find={field: lower}, to=shard, _waitForDelete=True)
            except Exception as exc:  # already on that shard
                if 'already' not in str(exc).lower():
                    raise
    ranges = [{'shard': s, 'from_user_id': None if i == 0 else points[i - 1],
               'to_user_id_excl': points[i] if i < len(points) else None} for i, s in enumerate(shards)]
    return {'placement': 'range', 'shards': shards, 'ranges': ranges}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--uri', default='mongodb://localhost:27100')
    ap.add_argument('--prepared', type=Path, default=Path('data/prepared'))
    ap.add_argument('--placement', choices=['range', 'hashed'], default='range')
    args = ap.parse_args()

    client = MongoClient(args.uri)
    sharded = client.admin.command('hello').get('msg') == 'isdbgrid'
    db = client[DB_NAME]
    timings = {}
    t0 = time.perf_counter()
    client.drop_database(DB_NAME)

    layout = {'placement': 'single server'}
    if sharded:
        layout = shard_collections(client, args.prepared, args.placement)
        print(f"Sharding: {json.dumps(layout)}")

    def step(name, fn):
        t = time.perf_counter()
        result = fn()
        timings[name] = round(time.perf_counter() - t, 2)
        print(f'{name:<12} {result if result is not None else "":>10}  {timings[name]:>7.2f} s')

    step('books', lambda: insert(db.books, read_jsonl(args.prepared / 'books.jsonl')))
    step('tags', lambda: insert(db.tags, read_jsonl(args.prepared / 'tags.jsonl')))
    step('ratings', lambda: insert(db.ratings, with_pair_id(read_jsonl(args.prepared / 'ratings.jsonl'))))
    step('to_read', lambda: insert(db.to_read, with_pair_id(read_jsonl(args.prepared / 'to_read.jsonl'))))

    def indexes():
        db.ratings.create_index([('book_id', ASCENDING), ('rating', ASCENDING), ('user_id', ASCENDING)])
        db.ratings.create_index([('user_id', ASCENDING), ('rating', ASCENDING), ('book_id', ASCENDING)])
        db.ratings.create_index([('user_id', ASCENDING), ('seq', ASCENDING)])
        db.to_read.create_index([('book_id', ASCENDING)])
        db.to_read.create_index([('user_id', ASCENDING)])
        db.books.create_index([('tags.t', ASCENDING)])
        db.tags.create_index([('name', ASCENDING)])
    step('indexes', indexes)

    def stats():
        db.ratings.aggregate([
            {'$group': {'_id': '$book_id', 'n': {'$sum': 1}, 'sum': {'$sum': '$rating'},
                        'n_pos': {'$sum': {'$cond': [{'$gte': ['$rating', 4]}, 1, 0]}}}},
            {'$merge': {'into': 'book_stats', 'on': '_id'}}], allowDiskUse=True)
        db.ratings.aggregate([
            {'$group': {'_id': '$user_id', 'n': {'$sum': 1}, 'sum': {'$sum': '$rating'}}},
            {'$merge': {'into': 'user_stats', 'on': '_id'}}], allowDiskUse=True)
        last = db.ratings.find_one(sort=[('seq', -1)])['seq']
        db.meta.replace_one({'_id': 'seq'}, {'_id': 'seq', 'value': last}, upsert=True)
    step('statistics', stats)

    timings['total'] = round(time.perf_counter() - t0, 2)
    size = db.command('dbStats')
    storage = {k: size.get(k) for k in ('dataSize', 'storageSize', 'indexSize', 'totalSize')}
    if sharded:
        storage['per_shard'] = {s: {k: v.get(k) for k in ('dataSize', 'storageSize', 'indexSize')}
                                for s, v in size.get('raw', {}).items()}
    report = json.loads((args.prepared / 'report.json').read_text(encoding='utf-8'))
    db.meta.replace_one({'_id': 'load'}, {'_id': 'load', 'selection_rule': report['selection_rule'],
                                          'counts': report['counts'], 'layout': layout,
                                          'timings_s': timings, 'storage_bytes': storage}, upsert=True)
    print(f"Loaded in {timings['total']} s; storage: {json.dumps(storage)}")
    return 0


if __name__ == '__main__':
    sys.exit(main())

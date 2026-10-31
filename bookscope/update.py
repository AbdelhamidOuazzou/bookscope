"""F6 - import a batch of reader activity.

Identity of a rating: the pair (user_id, book_id), stored as _id "<user_id>:<book_id>".
Conflict policy: the latest imported score wins. When a pair already exists with a different
score, the stored rating is replaced, it moves to the end of the reader's history (new seq),
and the previous value is kept in rating_changes. A pair with the same score is left untouched,
so importing the same batch twice changes nothing. Inside one batch, the last row of a pair wins.
book_stats and user_stats are updated incrementally in the same pass.
"""
import csv
import hashlib
import json
from pathlib import Path

from pymongo import InsertOne, ReturnDocument, UpdateOne

from .store import LIKE, BookScopeError


def read_batch(path):
    path = Path(path)
    rows = []
    if path.suffix == '.csv':
        with path.open(encoding='utf-8') as f:
            rows = list(csv.DictReader(f))
    else:
        with path.open(encoding='utf-8') as f:
            rows = [json.loads(line) for line in f if line.strip()]
    return rows, hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def update(store, path):
    cat = store.catalog
    rows, batch_id = read_batch(path)
    rejected, latest = [], {}
    for i, row in enumerate(rows, 1):
        try:
            user_id, book_id, rating = int(row['user_id']), int(row['book_id']), int(row['rating'])
        except (KeyError, TypeError, ValueError):
            rejected.append({'row': i, 'reason': 'missing or non-integer user_id/book_id/rating'})
            continue
        if not 1 <= rating <= 5:
            rejected.append({'row': i, 'reason': f'rating {rating} outside 1..5'})
        elif book_id not in cat.books:
            rejected.append({'row': i, 'reason': f'unknown book_id {book_id}'})
        else:
            latest[(user_id, book_id)] = rating  # later rows of the same pair win
    duplicates_in_batch = sum(1 for _ in rows) - len(rejected) - len(latest)

    existing = {}
    keys = [f'{u}:{b}' for u, b in latest]
    for start in range(0, len(keys), 5000):
        for d in store.db.ratings.find({'_id': {'$in': keys[start:start + 5000]}}, {'rating': 1}):
            existing[d['_id']] = d['rating']
    known_readers = {d['_id'] for d in store.db.user_stats.find({'_id': {'$in': sorted({u for u, _ in latest})}}, {'_id': 1})}

    changes = [(u, b, r) for (u, b), r in latest.items() if existing.get(f'{u}:{b}') != r]
    if changes:
        seq_end = store.db.meta.find_one_and_update({'_id': 'seq'}, {'$inc': {'value': len(changes)}},
                                                    return_document=ReturnDocument.AFTER)['value']
    ops, book_inc, user_inc, audit = [], {}, {}, []
    inserted = replaced = 0
    for offset, (u, b, r) in enumerate(changes):
        seq = seq_end - len(changes) + 1 + offset
        key = f'{u}:{b}'
        old = existing.get(key)
        bs = book_inc.setdefault(b, {'n': 0, 'sum': 0, 'n_pos': 0})
        us = user_inc.setdefault(u, {'n': 0, 'sum': 0})
        if old is None:
            ops.append(InsertOne({'_id': key, 'user_id': u, 'book_id': b, 'rating': r, 'seq': seq}))
            bs['n'] += 1
            us['n'] += 1
            inserted += 1
        else:
            ops.append(UpdateOne({'_id': key, 'user_id': u}, {'$set': {'rating': r, 'seq': seq}}))
            audit.append({'_id': f'{key}:{batch_id}', 'user_id': u, 'book_id': b, 'old_rating': old,
                          'new_rating': r, 'batch': batch_id})
            replaced += 1
        bs['sum'] += r - (old or 0)
        bs['n_pos'] += (r >= LIKE) - (old is not None and old >= LIKE)
        us['sum'] += r - (old or 0)
    if ops:
        store.db.ratings.bulk_write(ops, ordered=True)
        store.db.book_stats.bulk_write([UpdateOne({'_id': b}, {'$inc': inc}, upsert=True) for b, inc in book_inc.items()])
        store.db.user_stats.bulk_write([UpdateOne({'_id': u}, {'$inc': inc}, upsert=True) for u, inc in user_inc.items()])
    if audit:
        store.db.rating_changes.insert_many(audit)
    store.refresh()
    return {
        'service': 'F6', 'status': 'ok' if not rejected else 'ok_with_rejections', 'batch': str(path),
        'batch_id': batch_id, 'rows': len(rows), 'inserted': inserted, 'replaced_different_score': replaced,
        'unchanged_already_stored': len(latest) - len(changes), 'duplicates_inside_batch': duplicates_in_batch,
        'new_readers': sorted({u for u, _ in latest} - known_readers)[:20],
        'rejected': rejected[:20], 'rejected_total': len(rejected),
        'policy': 'identity = (user_id, book_id); latest imported score wins; previous score kept in rating_changes',
    }


def records(store, users=(), books=(), with_tags=False):
    """List the stored records behind a result (traceability)."""
    cat = store.catalog
    query = {}
    if users:
        query['user_id'] = {'$in': list(users)}
    if books:
        query['book_id'] = {'$in': list(books)}
    if not query:
        raise BookScopeError('give at least one user or book')
    ratings = list(store.db.ratings.find(query, {'_id': 0}).sort([('user_id', 1), ('seq', 1)]).limit(500))
    out = {'service': 'records', 'status': 'ok', 'ratings': ratings, 'ratings_shown': len(ratings)}
    if with_tags and books:
        out['book_tags'] = {b: [{'tag': cat.tags[e['t']]['name'], 'tag_id': e['t'], 'count': e['n']}
                                for e in cat.books[b]['tags'][:20]] for b in books if b in cat.books}
    return out

"""F3 - recommend books a reader has not rated."""
import math
import statistics

from .store import LIKE, rnd

NEIGHBOURS = 50
CANDIDATE_NEIGHBOURS = 1000
MIN_SUPPORT = 2
POPULARITY_PENALTY = 0.35
SHORT_HISTORY = 10  # below this many ratings, reader evidence is blended with tag evidence


def find_neighbours(store, user_id, liked, limit=NEIGHBOURS):
    """Readers who liked the same books, weighted by book rarity and normalised by activity."""
    cat = store.catalog
    pipeline = [{'$match': {'book_id': {'$in': liked}, 'rating': {'$gte': LIKE}, 'user_id': {'$ne': user_id}}},
                {'$group': {'_id': '$user_id', 'books': {'$push': '$book_id'}}}]
    raw = []
    for d in store.db.ratings.aggregate(pipeline, allowDiskUse=True):
        books = sorted(d['books'])
        raw.append((sum(cat.book_weight(b) for b in books), d['_id'], books))
    raw.sort(key=lambda x: (-x[0], x[1]))
    raw = raw[:CANDIDATE_NEIGHBOURS]
    activity = store.activity(u for _, u, _ in raw)
    # Very active readers share many books with everyone: divide by sqrt(their activity).
    scored = sorted(((s / math.sqrt(activity.get(u, 1)), u, books) for s, u, books in raw),
                    key=lambda x: (-x[0], x[1]))[:limit]
    return [{'user_id': u, 'similarity': rnd(s), 'shared_liked_books': books, 'ratings': activity.get(u)}
            for s, u, books in scored]


def recommend(store, user_id, k=10, truncate=None, penalty=POPULARITY_PENALTY):
    cat = store.catalog
    history = store.history(user_id, truncate)
    if not history:
        return {'service': 'F3', 'status': 'not_found', 'message': f'reader {user_id} has no stored ratings'}
    rated = {r['book_id'] for r in history}
    liked = sorted(r['book_id'] for r in history if r['rating'] >= LIKE)
    basis_note = None
    if not liked:
        top = max(r['rating'] for r in history)
        liked = sorted(r['book_id'] for r in history if r['rating'] == top)
        basis_note = f'no rating >= {LIKE}; the reader\'s highest-rated books ({top}) are used instead'

    neighbours = find_neighbours(store, user_id, liked)
    sims = {n['user_id']: n['similarity'] for n in neighbours}
    support = {}
    if neighbours:
        cursor = store.db.ratings.find({'user_id': {'$in': list(sims)}, 'rating': {'$gte': LIKE}},
                                       {'_id': 0, 'user_id': 1, 'book_id': 1, 'rating': 1})
        for r in cursor:
            if r['book_id'] not in rated:
                support.setdefault(r['book_id'], []).append(r)

    reader_scores = {b: sum(sims[r['user_id']] for r in rs) / (cat.n_pos(b) or 1) ** penalty
                     for b, rs in support.items() if len(rs) >= MIN_SUPPORT}

    short = len(history) < SHORT_HISTORY
    profile = {}
    for b in liked:
        for t, w in cat.tag_vectors[b].items():
            profile[t] = profile.get(t, 0.0) + w
    norm = math.sqrt(sum(w * w for w in profile.values())) or 1.0
    profile = {t: w / norm for t, w in profile.items()}
    tag_scores = cat.tag_similar(profile, exclude=rated)

    def normalise(scores):
        top = max(scores.values(), default=0) or 1.0
        return {b: s / top for b, s in scores.items()}

    if short:
        # Few ratings: few, weakly supported neighbours. Blend in the reader's tag profile.
        r_n, t_n = normalise(reader_scores), normalise(tag_scores)
        final = {b: 0.5 * r_n.get(b, 0) + 0.5 * t_n.get(b, 0) for b in r_n.keys() | t_n.keys()}
        mode = f'short history (< {SHORT_HISTORY} ratings): 50% similar readers + 50% tag profile'
    else:
        final = reader_scores
        mode = 'similar readers (user-based collaborative filtering)'
    ranked = sorted(final, key=lambda b: (-rnd(final[b]), b))[:k]

    shared_books = {n['user_id']: n['shared_liked_books'] for n in neighbours}
    results = []
    for rank, b in enumerate(ranked, 1):
        rs = sorted(support.get(b, []), key=lambda r: (-sims[r['user_id']], r['user_id']))
        best_shared = []
        for t in sorted(cat.tag_vectors[b], key=lambda t: -profile.get(t, 0) * cat.tag_vectors[b][t]):
            if t in profile:
                best_shared.append(cat.tags[t]['name'])
            if len(best_shared) == 3:
                break
        supporters = [{'user_id': r['user_id'], 'rating': r['rating'],
                       'also_liked_your_books': shared_books[r['user_id']][:3]} for r in rs[:3]]
        explanation = []
        if rs:
            explanation.append(f'liked by {len(rs)} of your {len(neighbours)} most similar readers')
        if best_shared:
            explanation.append('shares tags ' + ', '.join(best_shared) + ' with books you liked')
        results.append({'rank': rank, **cat.book_summary(b), 'score': rnd(final[b]),
                        'supporting_readers': len(rs), 'tag_similarity': rnd(tag_scores.get(b, 0)),
                        'in_top100_most_rated': b in cat.top100,
                        'explanation': '; '.join(explanation), 'evidence': supporters})

    pops = [cat.n(r['book_id']) for r in results]
    status = 'ok' if results else 'no_result'
    out = {
        'service': 'F3', 'status': status,
        'reader': {'user_id': user_id, 'ratings_used': len(history), 'liked_books_used': len(liked),
                   'truncated_to': truncate,
                   'history_note': f'TRUNCATED: only the {truncate} earliest ratings are used' if truncate else None},
        'mode': mode, 'neighbours_used': len(neighbours),
        'recommendations': results,
        'popularity': {
            'share_in_top100_most_rated': rnd(sum(r['in_top100_most_rated'] for r in results) / len(results), 3) if results else None,
            'median_ratings_of_recommended': statistics.median(pops) if pops else None,
            'median_ratings_of_top100': statistics.median(cat.n(b) for b in cat.top100),
            'median_ratings_of_catalogue': statistics.median(cat.n(b) for b in cat.books),
        },
        'parameters': {'k': k, 'neighbours': NEIGHBOURS, 'min_support': MIN_SUPPORT,
                       'popularity_penalty': penalty, 'like_threshold': LIKE},
    }
    if basis_note:
        out['note'] = basis_note
    if not results:
        out['message'] = 'not enough connected evidence to recommend anything'
    return out


def popularity_baseline(store, user_id, k=10, truncate=None):
    """Most rated books the reader has not rated (baseline for E3)."""
    cat = store.catalog
    rated = {r['book_id'] for r in store.history(user_id, truncate)}
    return [b for b in cat.by_popularity if b not in rated][:k]

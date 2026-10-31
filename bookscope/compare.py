"""F2 - compare two reader profiles."""
import math

from .store import rnd

MIN_COMMON = 5
FULL_CONFIDENCE = 25  # shared books needed before the similarity is used at full strength


def compare(store, user_a, user_b, truncate_a=None, truncate_b=None, k=10, min_common=MIN_COMMON):
    cat = store.catalog
    ha, hb = store.history(user_a, truncate_a), store.history(user_b, truncate_b)
    for user, hist in ((user_a, ha), (user_b, hb)):
        if not hist:
            return {'service': 'F2', 'status': 'not_found', 'message': f'reader {user} has no stored ratings'}
    ra = {r['book_id']: r['rating'] for r in ha}
    rb = {r['book_id']: r['rating'] for r in hb}
    mean_a, mean_b = sum(ra.values()) / len(ra), sum(rb.values()) / len(rb)
    common = sorted(ra.keys() & rb.keys())

    readers = [
        {'user_id': user_a, 'ratings': len(ra), 'mean_rating': rnd(mean_a, 3),
         'truncated_to': truncate_a},
        {'user_id': user_b, 'ratings': len(rb), 'mean_rating': rnd(mean_b, 3),
         'truncated_to': truncate_b},
    ]
    overlap = {
        'shared_books': len(common),
        'jaccard': rnd(len(common) / len(ra.keys() | rb.keys())),
        'share_of_smaller_history': rnd(len(common) / min(len(ra), len(rb))),
    }

    def row(b):
        return {'book_id': b, 'title': cat.title(b), 'rating_a': ra[b], 'rating_b': rb[b],
                'readers_of_book': cat.n(b), 'weight': rnd(cat.book_weight(b), 4)}

    if len(common) < min_common:
        return {'service': 'F2', 'status': 'insufficient_overlap', 'readers': readers, 'overlap': overlap,
                'message': f'the readers share {len(common)} book(s); at least {min_common} are needed '
                           'for a meaningful comparison',
                'shared_books': [row(b) for b in common]}

    # Ratings are centred on each reader's mean (harsh vs generous raters) and every book is
    # weighted by inverse popularity, so agreeing on a bestseller counts less than on a rare book.
    num = den_a = den_b = 0.0
    for b in common:
        w = cat.book_weight(b)
        da, db = ra[b] - mean_a, rb[b] - mean_b
        num += w * da * db
        den_a += w * da * da
        den_b += w * db * db
    correlation = num / math.sqrt(den_a * den_b) if den_a and den_b else 0.0
    confidence = min(len(common), FULL_CONFIDENCE) / FULL_CONFIDENCE
    strength = correlation * confidence

    agreements, disagreements = [], []
    for b in common:
        da, db = ra[b] - mean_a, rb[b] - mean_b
        w = cat.book_weight(b)
        if da * db > 0 or ra[b] == rb[b]:
            agreements.append((-(w * (1 + min(abs(da), abs(db)))), b))
        if da * db < 0 or abs(ra[b] - rb[b]) >= 2:
            disagreements.append((-(w * abs(ra[b] - rb[b])), b))
    label = ('strong' if strength >= 0.5 else 'moderate' if strength >= 0.25 else
             'weak' if strength > -0.25 else 'opposed')
    return {
        'service': 'F2', 'status': 'ok', 'readers': readers, 'overlap': overlap,
        'similarity': {'weighted_correlation': rnd(correlation), 'confidence': rnd(confidence),
                       'strength': rnd(strength), 'label': label},
        'agreements': [row(b) for _, b in sorted(agreements)[:k]],
        'disagreements': [row(b) for _, b in sorted(disagreements)[:k]],
        'method': 'popularity-weighted Pearson correlation of mean-centred ratings on shared books, '
                  f'scaled by min(shared, {FULL_CONFIDENCE})/{FULL_CONFIDENCE}',
    }

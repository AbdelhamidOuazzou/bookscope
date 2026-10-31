"""F4 - trace a connection between two books."""
import math

from .explore import reader_links
from .store import BookScopeError, rnd

MIN_SHARED = 5
MIN_LIFT = 1.2
HUB_SHARE = 0.01  # the 1% most rated books are hubs: paths through them say little


def trace(store, book_a, book_b, k=5, min_shared=MIN_SHARED):
    cat = store.catalog
    for b in (book_a, book_b):
        if b not in cat.books:
            raise BookScopeError(f'unknown book_id {b}')
    if book_a == book_b:
        raise BookScopeError('the two books are identical')
    likers_a, links_a = reader_links(store, book_a, min_shared)
    likers_b, links_b = reader_links(store, book_b, min_shared)
    hubs = set(cat.by_popularity[:max(1, int(HUB_SHARE * cat.n_books))])

    # 1. Direct: readers who liked both books. Each reader is down-weighted by activity so that
    #    a handful of very active readers cannot create the link alone.
    both = sorted(set(likers_a) & set(likers_b))
    activity = store.activity(both)
    expected = len(likers_a) * len(likers_b) / cat.n_readers if cat.n_readers else 0
    direct_lift = len(both) / expected if expected else 0.0
    direct = {
        'shared_readers': len(both), 'expected_if_independent': rnd(expected, 2), 'lift': rnd(direct_lift, 3),
        'activity_weighted_support': rnd(sum(1 / math.log2(1 + activity.get(u, 2)) for u in both), 3),
        'example_readers': [{'user_id': u, 'ratings': activity.get(u)}
                            for u in sorted(both, key=lambda u: (activity.get(u, 0), u))[:5]],
    }
    direct_ok = len(both) >= min_shared and direct_lift >= MIN_LIFT

    # 2. Two hops through an intermediate book liked with both ends.
    paths = []
    for c in links_a.keys() & links_b.keys():
        la, lb = links_a[c], links_b[c]
        if c in (book_a, book_b) or not (la['enough'] and lb['enough']):
            continue
        score = la['cosine'] * lb['cosine'] * (0.5 if c in hubs else 1.0)
        paths.append((score, c, la, lb))
    paths.sort(key=lambda p: (-rnd(p[0]), p[1]))
    alternatives = [{'via': cat.book_summary(c), 'score': rnd(s), 'hub': c in hubs,
                     'edge_a': {'shared_readers': la['shared_readers'], 'lift': la['lift']},
                     'edge_b': {'shared_readers': lb['shared_readers'], 'lift': lb['lift']}}
                    for s, c, la, lb in paths[:k]]
    informative = [a for a in alternatives if not a['hub'] and a['edge_a']['lift'] >= MIN_LIFT
                   and a['edge_b']['lift'] >= MIN_LIFT]

    # 3. Catalogue attributes.
    authors = sorted(set(cat.books[book_a]['authors']) & set(cat.books[book_b]['authors']))
    tags = cat.shared_tags(book_a, book_b, limit=0)
    specific = [t for t in tags if not t['generic']][:5]

    if direct_ok:
        verdict = 'direct connection: the books share more readers than expected by chance'
    elif informative:
        verdict = 'indirect connection through books liked by readers of both'
    elif authors or specific:
        verdict = 'catalogue connection only (author or specific tags), no convincing reader evidence'
    else:
        reasons = []
        if len(both) < min_shared:
            reasons.append(f'only {len(both)} reader(s) liked both books (minimum {min_shared})')
        elif direct_lift < MIN_LIFT:
            reasons.append(f'shared readers are not above chance (lift {direct_lift:.2f})')
        if not informative:
            reasons.append('no intermediate book outside the most popular hubs links them')
        if tags:
            reasons.append('the only shared tags are generic: ' + ', '.join(t['tag'] for t in tags[:3]))
        verdict = 'no convincing connection: ' + '; '.join(reasons)
    return {
        'service': 'F4', 'status': 'ok' if (direct_ok or informative or authors or specific) else 'no_connection',
        'book_a': {**cat.book_summary(book_a), 'readers_who_liked': len(likers_a)},
        'book_b': {**cat.book_summary(book_b), 'readers_who_liked': len(likers_b)},
        'verdict': verdict, 'direct': direct, 'two_hop_paths': alternatives,
        'catalogue': {'shared_authors': authors, 'shared_specific_tags': specific,
                      'shared_generic_tags': [t['tag'] for t in tags if t['generic']][:5]},
        'parameters': {'min_shared_readers': min_shared, 'min_lift': MIN_LIFT,
                       'hub_books': f'top {HUB_SHARE:.0%} most rated, path score halved'},
    }

"""F5 - discover links between two reading interests (tags)."""
import math

from .store import LIKE, BookScopeError, rnd

# A book belongs to an interest when the tag is among its TOP_RANK most used content tags
# (status tags such as to-read are skipped) and was given at least MIN_TAG_COUNT times.
# Raw counts alone are misleading: 1984 carries "horror" 211 times because it is so popular.
MIN_TAG_COUNT = 20
TOP_RANK = 10
MIN_PER_SIDE = 2     # an attached reader liked >= 2 books of the interest
ATTACHMENT = 2.0     # ... and their share of liked books in it is >= 2x the average share
MIN_LIFT = 1.2
MIN_BRIDGE_LIKERS = 5
HUB_SHARE = 0.01
ACTIVE_QUANTILE = 0.95


def resolve_tag(cat, name):
    matches = cat.find_tags(name)
    if not matches:
        raise BookScopeError(f'no tag matches "{name}"')
    if len(matches) > 1:
        return None, {'status': 'ambiguous', 'query': name,
                      'matches': [{'tag_id': t['_id'], 'name': t['name'], 'books': t['df']} for t in matches[:20]],
                      'message': 'several tags match; give the exact tag name'}
    tag = matches[0]
    if tag['status_like']:
        raise BookScopeError(f'"{tag["name"]}" describes a reading status or ownership, not a reading interest')
    return tag, None


def analyse(groups, liked_total, side1, side2, excluded_books=frozenset(), excluded_readers=frozenset()):
    """How much readers attached to one interest also like the other one.

    A reader is attached to an interest when at least MIN_PER_SIDE of their liked books belong
    to it and their share of liked books in it is at least ATTACHMENT x the average share
    (shares, not raw counts, so very active readers are not attached to everything).
    Directional lift a->b: books of b liked by readers attached to a, divided by what their
    likes *outside a* would contain if b had its average share there. Excluding a from the
    expectation avoids a compositional bias (a horror fan has fewer likes left for romance)."""
    readers = [u for u in liked_total if u not in excluded_readers]
    counts = {}
    for u in readers:
        books = [b for b in groups.get(u, ()) if b not in excluded_books]
        counts[u] = (sum(b in side1 for b in books), sum(b in side2 for b in books), liked_total[u])
    total = sum(c[2] for c in counts.values()) or 1
    base1 = sum(c[0] for c in counts.values()) / total
    base2 = sum(c[1] for c in counts.values()) / total
    att1 = {u for u, (n1, _, n) in counts.items() if n1 >= MIN_PER_SIDE and n1 / n >= ATTACHMENT * base1}
    att2 = {u for u, (_, n2, n) in counts.items() if n2 >= MIN_PER_SIDE and n2 / n >= ATTACHMENT * base2}

    def directional(attached, mine, other):
        q = sum(c[other] for c in counts.values()) / (sum(c[2] - c[mine] for c in counts.values()) or 1)
        observed = sum(counts[u][other] for u in attached)
        expected = sum(counts[u][2] - counts[u][mine] for u in attached) * q
        return observed, expected

    obs_ab, exp_ab = directional(att1, 0, 1)
    obs_ba, exp_ba = directional(att2, 1, 0)
    lift_ab = obs_ab / exp_ab if exp_ab else 0.0
    lift_ba = obs_ba / exp_ba if exp_ba else 0.0
    bridge = {u: groups[u] for u in sorted(att1 & att2)}
    return {'readers_considered': len(readers), 'attached_to_a': len(att1), 'attached_to_b': len(att2),
            'bridge_readers': len(bridge),
            'a_to_b': {'liked_b_books': obs_ab, 'expected': rnd(exp_ab, 1), 'lift': rnd(lift_ab, 3)},
            'b_to_a': {'liked_a_books': obs_ba, 'expected': rnd(exp_ba, 1), 'lift': rnd(lift_ba, 3)},
            'lift': rnd(math.sqrt(lift_ab * lift_ba), 3)}, bridge


def interests(store, tag_a, tag_b, k=5, min_count=MIN_TAG_COUNT):
    cat = store.catalog
    ta, early = resolve_tag(cat, tag_a)
    if early:
        return {'service': 'F5', **early}
    tb, early = resolve_tag(cat, tag_b)
    if early:
        return {'service': 'F5', **early}
    count_a, count_b = {}, {}
    for b, book in cat.books.items():
        content = [e for e in book['tags'] if not cat.tags[e['t']]['status_like']][:TOP_RANK]
        for e in content:
            if e['n'] >= min_count and e['t'] == ta['_id']:
                count_a[b] = e['n']
            elif e['n'] >= min_count and e['t'] == tb['_id']:
                count_b[b] = e['n']
    both = count_a.keys() & count_b.keys()
    side1, side2 = count_a.keys() - both, count_b.keys() - both
    if not count_a or not count_b:
        return {'service': 'F5', 'status': 'no_result',
                'message': f'an interest has no book tagged at least {min_count} times',
                'books_interest_a': len(count_a), 'books_interest_b': len(count_b)}

    groups = {}
    pipeline = [{'$match': {'book_id': {'$in': sorted(count_a.keys() | count_b.keys())}, 'rating': {'$gte': LIKE}}},
                {'$group': {'_id': '$user_id', 'books': {'$push': '$book_id'}}}]
    for d in store.db.ratings.aggregate(pipeline, allowDiskUse=True):
        groups[d['_id']] = sorted(d['books'])
    liked_total = {d['_id']: d['n'] for d in store.db.ratings.aggregate(
        [{'$match': {'rating': {'$gte': LIKE}}}, {'$group': {'_id': '$user_id', 'n': {'$sum': 1}}}], allowDiskUse=True)}

    full, bridge = analyse(groups, liked_total, side1, side2)
    hubs = frozenset(cat.by_popularity[:max(1, int(HUB_SHARE * cat.n_books))])
    activity = sorted(d['n'] for d in store.db.user_stats.find({}, {'n': 1}))
    threshold = activity[int(ACTIVE_QUANTILE * (len(activity) - 1))]
    very_active = frozenset(d['_id'] for d in store.db.user_stats.find({'n': {'$gt': threshold}}, {'_id': 1}))
    discounted, bridge_d = analyse(groups, liked_total, side1, side2, hubs, very_active)

    def connectors(side, counts, bridge_readers):
        """Books liked by bridge readers more often than by readers in general (lift)."""
        liked = {}
        for books in bridge_readers.values():
            for b in books:
                if b in side:
                    liked[b] = liked.get(b, 0) + 1
        if not bridge_readers:
            return []
        lift = {b: (c / len(bridge_readers)) / (cat.n_pos(b) / cat.n_readers)
                for b, c in liked.items() if c >= MIN_BRIDGE_LIKERS and cat.n_pos(b)}
        ranked = sorted(lift, key=lambda b: (-rnd(lift[b] * math.log(liked[b])), b))[:k]
        return [{**cat.book_summary(b), 'bridge_readers_who_liked': liked[b], 'lift': rnd(lift[b], 3),
                 'tag_count': counts.get(b), 'hub': b in hubs} for b in ranked]

    bridging_books = sorted(both, key=lambda b: (-min(count_a[b], count_b[b]), b))
    linked = (full['lift'] or 0) >= MIN_LIFT
    persists = linked and (discounted['lift'] or 0) >= MIN_LIFT
    return {
        'service': 'F5', 'status': 'ok',
        'interest_a': {'tag': ta['name'], 'tag_id': ta['_id'], 'books': len(count_a)},
        'interest_b': {'tag': tb['name'], 'tag_id': tb['_id'], 'books': len(count_b)},
        'books_in_both': [{**cat.book_summary(b), 'tag_count_a': count_a[b], 'tag_count_b': count_b[b],
                           'bridge_readers_who_liked': sum(b in bs for bs in bridge.values())}
                          for b in bridging_books[:k]],
        'books_in_both_total': len(both),
        'reader_groups': full,
        'connecting_books_a': connectors(side1, count_a, bridge),
        'connecting_books_b': connectors(side2, count_b, bridge),
        'discounted': {**discounted, 'excluded_hub_books': len(hubs),
                       'excluded_readers': len(very_active), 'activity_threshold': threshold,
                       'connecting_books_a': connectors(side1 - hubs, count_a, bridge_d)[:3],
                       'connecting_books_b': connectors(side2 - hubs, count_b, bridge_d)[:3]},
        'verdict': ('the link persists without popular books and very active readers' if persists else
                    'the link disappears once popular books and very active readers are discounted' if linked else
                    f'no link beyond chance: readers attached to one interest do not like the other more '
                    f'than expected (lift < {MIN_LIFT})'),
        'parameters': {'min_tag_count': min_count, 'tag_rank': f'tag among the {TOP_RANK} most used content tags',
                       'attachment': f'>= {MIN_PER_SIDE} liked books and >= {ATTACHMENT}x the average share',
                       'min_lift': MIN_LIFT, 'like_threshold': LIKE,
                       'hubs': f'top {HUB_SHARE:.0%} most rated books',
                       'very_active': f'readers above the {ACTIVE_QUANTILE:.0%} activity quantile'},
    }

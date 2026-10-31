"""F1 - explore a book and its connections."""
import math

from .store import BookScopeError, rnd


def resolve_book(store, query=None, book_id=None):
    """Return (book_id, None) or (None, result) when the title is unknown or ambiguous."""
    cat = store.catalog
    if book_id is not None:
        if book_id not in cat.books:
            raise BookScopeError(f'unknown book_id {book_id}')
        return book_id, None
    matches = cat.find_books(query)
    if not matches:
        return None, {'status': 'not_found', 'query': query}
    if len(matches) > 1:
        return None, {'status': 'ambiguous', 'query': query,
                      'message': f'{len(matches)} books match this title; choose one with --id',
                      'matches': [cat.book_summary(b['_id']) for b in matches[:25]],
                      'total_matches': len(matches)}
    return matches[0]['_id'], None


def reader_links(store, book_id, min_shared):
    """Books co-liked with book_id: shared likers, cosine and lift over the stored readers."""
    cat = store.catalog
    likers, co = store.co_liked(book_id)
    links = {}
    for other, shared in co.items():
        n_pos = cat.n_pos(other)
        links[other] = {
            'shared_readers': shared,
            'cosine': rnd(shared / math.sqrt(len(likers) * n_pos)) if n_pos else 0.0,
            'lift': rnd(shared * cat.n_readers / (len(likers) * n_pos)) if n_pos else 0.0,
            'enough': shared >= min_shared,
        }
    return likers, links


def explore(store, query=None, book_id=None, k=10, min_shared=5):
    cat = store.catalog
    book_id, early = resolve_book(store, query, book_id)
    if early:
        return {'service': 'F1', **early}
    book = cat.books[book_id]
    likers, links = reader_links(store, book_id, min_shared)

    by_readers = sorted((b for b, l in links.items() if l['enough']),
                        key=lambda b: (-links[b]['cosine'], b))[:k]
    tag_scores = cat.tag_similar(cat.tag_vectors[book_id], exclude=[book_id])
    by_tags = sorted((b for b, s in tag_scores.items() if s > 0), key=lambda b: (-tag_scores[b], b))[:k]

    def entry(other):
        link = links.get(other, {'shared_readers': 0, 'cosine': 0.0, 'lift': 0.0, 'enough': False})
        tag_score = rnd(tag_scores.get(other, 0.0))
        shared = cat.shared_tags(book_id, other)
        specific = [s for s in shared if not s['generic']]
        if link['enough'] and tag_score > 0:
            basis = 'shared readership and specific tags'
        elif link['enough']:
            basis = 'shared readership'
        elif tag_score > 0:
            basis = 'specific tags only (few shared readers)'
        else:
            basis = 'generic tags only'
        return {**cat.book_summary(other), 'basis': basis,
                'shared_readers': link['shared_readers'], 'reader_cosine': link['cosine'],
                'lift': link['lift'], 'tag_similarity': tag_score,
                'shared_specific_tags': specific[:3],
                'shared_generic_tags': [s['tag'] for s in shared if s['generic']][:3]}

    generic_tags = [cat.tags[e['t']] for e in book['tags'] if cat.tags[e['t']]['generic']]
    generic_ids = {t['_id'] for t in generic_tags}
    generic_only = {b for b, other in cat.books.items()
                    if b != book_id and b not in tag_scores
                    and any(e['t'] in generic_ids for e in other['tags'])}
    return {
        'service': 'F1', 'status': 'ok',
        'book': {**cat.book_summary(book_id), 'goodreads_book_id': book['goodreads_book_id'],
                 'original_title': book.get('original_title'), 'language': book.get('language'),
                 'readers_who_liked': len(likers),
                 'average_rating_in_selection': rnd(cat.stats[book_id]['sum'] / cat.stats[book_id]['n'], 3)
                 if book_id in cat.stats else None,
                 'top_tags': [{'tag': cat.tags[e['t']]['name'], 'count': e['n'],
                               'generic': cat.tags[e['t']]['generic']} for e in book['tags'][:8]]},
        'related_by_readers': [entry(b) for b in by_readers],
        'related_by_tags': [entry(b) for b in by_tags],
        'generic_tag_note': {
            'generic_tags_on_book': [t['name'] for t in generic_tags[:5]],
            'books_linked_only_by_generic_tags': len(generic_only),
            'explanation': 'tags carried by >= 50% of the books or describing reading status '
                           '(e.g. to-read, favorites, owned) link almost every book and are ignored as evidence',
        },
        'parameters': {'k': k, 'min_shared_readers': min_shared, 'like_threshold': 4},
    }

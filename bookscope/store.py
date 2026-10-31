"""Access to the BookScope data server (a standalone mongod or a mongos router).

Data model (database "bookscope"):
  books       {_id: book_id, goodreads_book_id, title, authors[], year, tags: [{t: tag_id, n: count}], ...}
  tags        {_id: tag_id, name, df (books carrying it), total, status_like}
  ratings     {_id: "<user_id>:<book_id>", user_id, book_id, rating, seq}      <- sharded by user_id
  to_read     {_id: "<user_id>:<book_id>", user_id, book_id, seq}              <- sharded by user_id
  user_stats  {_id: user_id, n, sum}                                           <- sharded by _id (= user_id)
  book_stats  {_id: book_id, n, sum, n_pos}           precomputed, maintained by F6
  meta        {_id: "seq" | "load", ...}
  rating_changes  audit of ratings replaced by F6

Catalogue collections (books, tags, book_stats) are small and are read once per process into
memory (Catalog). Reader activity is always queried from the server.
"""
import math
import os
import re
from functools import cached_property

from pymongo import MongoClient

DEFAULT_URI = os.environ.get('BOOKSCOPE_URI', 'mongodb://localhost:27100')
DB_NAME = os.environ.get('BOOKSCOPE_DB', 'bookscope')
LIKE = 4               # a rating >= LIKE is a positive evaluation
GENERIC_DF_SHARE = 0.5  # a tag carried by >= 50% of the books is too common to link two books


def rnd(x, digits=6):
    """Round scores so that both deployments print identical results."""
    return round(float(x), digits)


def normalise_title(text):
    return ' '.join(re.sub(r'[^\w\s]', ' ', text.lower()).split())


class BookScopeError(Exception):
    """Error reported to the user without a (partial) result."""


class Catalog:
    """In-memory view of books, tags and per-book statistics."""

    def __init__(self, db):
        self.books = {d['_id']: d for d in db.books.find()}
        self.tags = {d['_id']: d for d in db.tags.find()}
        self.stats = {d['_id']: d for d in db.book_stats.find()}
        self.n_books = len(self.books)
        self.n_readers = db.user_stats.count_documents({})
        for tag in self.tags.values():
            tag['idf'] = math.log(self.n_books / tag['df'])
            tag['generic'] = tag['status_like'] or tag['df'] >= GENERIC_DF_SHARE * self.n_books

    def title(self, book_id):
        return self.books[book_id]['title']

    def n(self, book_id):
        """Number of ratings of the book in the stored selection."""
        return self.stats.get(book_id, {}).get('n', 0)

    def n_pos(self, book_id):
        return self.stats.get(book_id, {}).get('n_pos', 0)

    def book_weight(self, book_id):
        """Inverse popularity weight: books rated by many readers say little about a reader."""
        return math.log((self.n_readers + 1) / (self.n(book_id) + 1))

    @cached_property
    def by_popularity(self):
        return sorted(self.stats, key=lambda b: (-self.stats[b]['n'], b))

    @cached_property
    def top100(self):
        return set(self.by_popularity[:100])

    @cached_property
    def tag_vectors(self):
        """book_id -> {tag_id: weight} over specific (non-generic) tags, L2-normalised."""
        vectors = {}
        for book_id, book in self.books.items():
            vec = {e['t']: math.log1p(e['n']) * self.tags[e['t']]['idf']
                   for e in book['tags'] if not self.tags[e['t']]['generic']}
            norm = math.sqrt(sum(w * w for w in vec.values())) or 1.0
            vectors[book_id] = {t: w / norm for t, w in vec.items()}
        return vectors

    @cached_property
    def tag_index(self):
        """tag_id -> [(book_id, weight)] for specific tags."""
        index = {}
        for book_id, vec in self.tag_vectors.items():
            for t, w in vec.items():
                index.setdefault(t, []).append((book_id, w))
        return index

    def tag_similar(self, profile, exclude=()):
        """Cosine between a tag profile {tag: weight} and every book sharing a specific tag."""
        scores = {}
        for t, w in profile.items():
            for book_id, wb in self.tag_index.get(t, ()):
                scores[book_id] = scores.get(book_id, 0.0) + w * wb
        for b in exclude:
            scores.pop(b, None)
        return scores

    def shared_tags(self, a, b, limit=5):
        """Tags carried by both books, specific tags first, with the counts on each book."""
        ta = {e['t']: e['n'] for e in self.books[a]['tags']}
        tb = {e['t']: e['n'] for e in self.books[b]['tags']}
        shared = [{'tag': self.tags[t]['name'], 'tag_id': t, 'count_a': ta[t], 'count_b': tb[t],
                   'books_with_tag': self.tags[t]['df'], 'generic': self.tags[t]['generic']}
                  for t in ta.keys() & tb.keys()]
        shared.sort(key=lambda s: (s['generic'], -min(s['count_a'], s['count_b']) * self.tags[s['tag_id']]['idf'], s['tag_id']))
        return shared[:limit] if limit else shared

    def find_books(self, query):
        """Every book whose title or original title contains the query (exact matches first).

        Several matches are returned as they are: the caller must show the ambiguity."""
        q = normalise_title(query)
        found = []
        for b in self.books.values():
            names = {normalise_title(b['title']), normalise_title(b.get('original_title') or '')}
            if q in names:
                found.append((0, -self.n(b['_id']), b['_id'], b))
            elif any(q and q in name for name in names):
                found.append((1, -self.n(b['_id']), b['_id'], b))
        return [b for *_, b in sorted(found, key=lambda x: x[:3])]

    def find_tags(self, query):
        q = query.strip().lower()
        exact = [t for t in self.tags.values() if t['name'] == q]
        if exact:
            return exact
        return sorted((t for t in self.tags.values() if q in t['name']), key=lambda t: (-t['df'], t['_id']))

    def book_summary(self, book_id):
        b = self.books[book_id]
        return {'book_id': book_id, 'title': b['title'], 'authors': b['authors'], 'year': b['year'],
                'ratings_in_selection': self.n(book_id)}


class Store:
    def __init__(self, uri=DEFAULT_URI, db_name=DB_NAME):
        self.uri = uri
        self.client = MongoClient(uri, serverSelectionTimeoutMS=5000, connectTimeoutMS=5000,
                                  socketTimeoutMS=120000)
        self.db = self.client[db_name]

    @cached_property
    def catalog(self):
        if self.db.books.estimated_document_count() == 0:
            raise BookScopeError(f'no data loaded at {self.uri}; run scripts/load_data.py first')
        return Catalog(self.db)

    def refresh(self):
        self.__dict__.pop('catalog', None)

    def history(self, user_id, truncate=None):
        """A reader's ratings in time order, optionally truncated to the k earliest."""
        cursor = self.db.ratings.find({'user_id': user_id}, {'_id': 0, 'book_id': 1, 'rating': 1, 'seq': 1}).sort('seq', 1)
        if truncate:
            cursor = cursor.limit(truncate)
        return list(cursor)

    def likers(self, book_id):
        return [d['user_id'] for d in self.db.ratings.find({'book_id': book_id, 'rating': {'$gte': LIKE}},
                                                           {'_id': 0, 'user_id': 1})]

    def co_liked(self, book_id, likers=None):
        """For every other book, how many readers who liked `book_id` also liked it."""
        likers = self.likers(book_id) if likers is None else likers
        if not likers:
            return likers, {}
        pipeline = [{'$match': {'user_id': {'$in': likers}, 'rating': {'$gte': LIKE}}},
                    {'$group': {'_id': '$book_id', 'co': {'$sum': 1}}}]
        co = {d['_id']: d['co'] for d in self.db.ratings.aggregate(pipeline, allowDiskUse=True)}
        co.pop(book_id, None)
        return likers, co

    def activity(self, user_ids):
        return {d['_id']: d['n'] for d in self.db.user_stats.find({'_id': {'$in': list(user_ids)}}, {'n': 1})}

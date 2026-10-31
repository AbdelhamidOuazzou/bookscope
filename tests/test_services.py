"""Integration tests on a small database built from real prepared records.

Needs a running mongod (default mongodb://localhost:27100, override with $BOOKSCOPE_TEST_URI)
and data/prepared/ from scripts/prepare_data.py. Run: python -m unittest discover tests
"""
import json
import os
import tempfile
import unittest
from itertools import islice
from pathlib import Path

from pymongo import MongoClient
from pymongo.errors import ConnectionFailure

from bookscope.compare import compare
from bookscope.explore import explore
from bookscope.store import Store, normalise_title
from bookscope.update import update

ROOT = Path(__file__).resolve().parent.parent
PREPARED = ROOT / 'data' / 'prepared'
URI = os.environ.get('BOOKSCOPE_TEST_URI', 'mongodb://localhost:27100')
DB = 'bookscope_test'


def jsonl(name, limit=None):
    with (PREPARED / name).open(encoding='utf-8') as f:
        return [json.loads(line) for line in islice(f, limit)]


class ServicesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (PREPARED / 'ratings.jsonl').exists():
            raise unittest.SkipTest('run scripts/prepare_data.py first')
        client = MongoClient(URI, serverSelectionTimeoutMS=1500)
        try:
            client.admin.command('ping')
        except ConnectionFailure:
            raise unittest.SkipTest(f'no mongod at {URI}')
        client.drop_database(DB)
        db = client[DB]
        db.books.insert_many(jsonl('books.jsonl'))
        db.tags.insert_many(jsonl('tags.jsonl'))
        ratings = jsonl('ratings.jsonl', 20000)
        for r in ratings:
            r['_id'] = f"{r['user_id']}:{r['book_id']}"
        db.ratings.insert_many(ratings)
        db.ratings.aggregate([{'$group': {'_id': '$book_id', 'n': {'$sum': 1}, 'sum': {'$sum': '$rating'},
                                          'n_pos': {'$sum': {'$cond': [{'$gte': ['$rating', 4]}, 1, 0]}}}},
                              {'$merge': {'into': 'book_stats'}}])
        db.ratings.aggregate([{'$group': {'_id': '$user_id', 'n': {'$sum': 1}, 'sum': {'$sum': '$rating'}}},
                              {'$merge': {'into': 'user_stats'}}])
        db.meta.insert_one({'_id': 'seq', 'value': max(r['seq'] for r in ratings)})
        cls.client, cls.store = client, Store(URI, DB)
        cls.user = ratings[0]['user_id']
        cls.rated = ratings[0]['book_id']

    @classmethod
    def tearDownClass(cls):
        cls.client.drop_database(DB)

    def write_batch(self, rows):
        f = tempfile.NamedTemporaryFile('w', suffix='.jsonl', delete=False, encoding='utf-8')
        f.write('\n'.join(json.dumps(r) for r in rows))
        f.close()
        self.addCleanup(os.unlink, f.name)
        return f.name

    def test_update_is_idempotent_and_latest_score_wins(self):
        old = self.store.db.ratings.find_one({'_id': f'{self.user}:{self.rated}'})['rating']
        new = 1 if old != 1 else 2
        batch = self.write_batch([{'user_id': self.user, 'book_id': self.rated, 'rating': new},
                                  {'user_id': self.user, 'book_id': 9999, 'rating': 4}])
        first = update(self.store, batch)
        self.assertEqual((first['inserted'], first['replaced_different_score']), (1, 1))
        n_before = self.store.db.ratings.count_documents({})
        second = update(self.store, batch)
        self.assertEqual((second['inserted'], second['replaced_different_score']), (0, 0))
        self.assertEqual(second['unchanged_already_stored'], 2)
        self.assertEqual(self.store.db.ratings.count_documents({}), n_before)
        self.assertEqual(self.store.db.rating_changes.count_documents({'old_rating': old, 'new_rating': new}), 1)

    def test_update_rejects_invalid_rows(self):
        batch = self.write_batch([{'user_id': self.user, 'book_id': 123456, 'rating': 4},
                                  {'user_id': self.user, 'book_id': 1, 'rating': 9}])
        result = update(self.store, batch)
        self.assertEqual(result['rejected_total'], 2)
        self.assertEqual(result['inserted'], 0)

    def test_ambiguous_title_is_shown(self):
        result = explore(self.store, query="'Salem's Lot")
        self.assertEqual(result['status'], 'ambiguous')
        self.assertIn(349, [m['book_id'] for m in result['matches']])

    def test_compare_reports_insufficient_overlap(self):
        result = compare(self.store, self.user, self.user, truncate_a=2, truncate_b=2)
        self.assertEqual(result['status'], 'insufficient_overlap')

    def test_normalise_title(self):
        self.assertEqual(normalise_title("  'Salem's   LOT "), 'salem s lot')


if __name__ == '__main__':
    unittest.main()

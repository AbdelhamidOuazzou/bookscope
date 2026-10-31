#!/usr/bin/env python3
"""Independent verification of F2 and F4 results from the original CSV files.

This script does not import BookScope and never reads the database to compute its values: it
re-implements the selection rule, the evaluation hold-out and the two metrics on the raw
ratings.csv. It assumes the stored state is "prepared data + update batch imported once"
(the update batch only gives back ratings withheld from the same CSV).

  python scripts/verify_csv.py --f2 7 3022 --f4 349 7
  python scripts/verify_csv.py --f2 7 3022 --f4 349 7 --check mongodb://localhost:27100

With --check, the BookScope service is run on the given deployment afterwards and the two
sets of numbers are compared.
"""
import argparse
import csv
import hashlib
import json
import math
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

LIKE = 4


def in_eval_set(user_id, share=20):
    return int(hashlib.sha1(f'bookscope:{user_id}'.encode()).hexdigest(), 16) % 100 < share


def stored_ratings(raw, modulo, remainder, eval_last):
    history = defaultdict(list)
    with (raw / 'ratings.csv').open(encoding='utf-8') as f:
        reader = csv.reader(f)
        next(reader)
        for user, book, rating in reader:
            u = int(user)
            if u % modulo == remainder:
                history[u].append((int(book), int(rating)))
    for u, rows in history.items():
        if in_eval_set(u):
            del rows[len(rows) - eval_last:]
    return history


def f2(history, a, b):
    n_readers = len(history)
    n_book = Counter(book for rows in history.values() for book, _ in rows)
    ra, rb = dict(history[a]), dict(history[b])
    mean_a, mean_b = sum(ra.values()) / len(ra), sum(rb.values()) / len(rb)
    common = sorted(ra.keys() & rb.keys())
    num = den_a = den_b = 0.0
    for book in common:
        w = math.log((n_readers + 1) / (n_book[book] + 1))
        da, db = ra[book] - mean_a, rb[book] - mean_b
        num += w * da * db
        den_a += w * da * da
        den_b += w * db * db
    corr = num / math.sqrt(den_a * den_b) if den_a and den_b else 0.0
    return {'shared_books': len(common), 'weighted_correlation': round(corr, 6),
            'strength': round(corr * min(len(common), 25) / 25, 6)}


def f4(history, a, b):
    likers_a = {u for u, rows in history.items() if any(bk == a and r >= LIKE for bk, r in rows)}
    likers_b = {u for u, rows in history.items() if any(bk == b and r >= LIKE for bk, r in rows)}
    both = likers_a & likers_b
    expected = len(likers_a) * len(likers_b) / len(history)
    return {'readers_who_liked_a': len(likers_a), 'readers_who_liked_b': len(likers_b),
            'shared_readers': len(both), 'expected_if_independent': round(expected, 2),
            'lift': round(len(both) / expected, 3)}


def service(uri, *args):
    out = subprocess.run([sys.executable, '-m', 'bookscope', '--uri', uri, '--json', *args],
                         capture_output=True, text=True, encoding='utf-8',
                         cwd=Path(__file__).resolve().parent.parent)
    return json.loads(out.stdout)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--raw', type=Path, default=Path('data/raw'))
    ap.add_argument('--modulo', type=int, default=5)
    ap.add_argument('--remainder', type=int, default=2)
    ap.add_argument('--eval-last', type=int, default=3)
    ap.add_argument('--f2', type=int, nargs=2, metavar=('USER_A', 'USER_B'), required=True)
    ap.add_argument('--f4', type=int, nargs=2, metavar=('BOOK_A', 'BOOK_B'), required=True)
    ap.add_argument('--check', metavar='URI', help='compare with the BookScope service on this deployment')
    args = ap.parse_args()

    history = stored_ratings(args.raw, args.modulo, args.remainder, args.eval_last)
    v2, v4 = f2(history, *args.f2), f4(history, *args.f4)
    print('F2 from CSV:', json.dumps(v2))
    print('F4 from CSV:', json.dumps(v4))
    if not args.check:
        return 0

    s2 = service(args.check, 'compare', *map(str, args.f2))
    s4 = service(args.check, 'trace', *map(str, args.f4))
    got2 = {'shared_books': s2['overlap']['shared_books'],
            'weighted_correlation': s2.get('similarity', {}).get('weighted_correlation'),
            'strength': s2.get('similarity', {}).get('strength')}
    got4 = {'readers_who_liked_a': s4['book_a']['readers_who_liked'],
            'readers_who_liked_b': s4['book_b']['readers_who_liked'],
            'shared_readers': s4['direct']['shared_readers'],
            'expected_if_independent': s4['direct']['expected_if_independent'],
            'lift': s4['direct']['lift']}
    ok = True
    for name, mine, theirs in (('F2', v2, got2), ('F4', v4, got4)):
        same = mine == theirs
        ok &= same
        print(f'{name}: {"MATCH" if same else "MISMATCH"}' + ('' if same else f'\n  csv:     {mine}\n  service: {theirs}'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())

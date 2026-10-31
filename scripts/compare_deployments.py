#!/usr/bin/env python3
"""Run the same requests on two deployments and check that the results are identical.

Results are compared as canonical JSON (sorted keys, timing fields removed). Lists keep their
ranked order: every service breaks ties deterministically (score, then identifier), so equal
records must give the same order. The request set mixes readers and books stored on different
shards of the sharded deployment.

  python scripts/compare_deployments.py --a mongodb://localhost:27100 --b mongodb://localhost:27200
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from bookscope.compare import compare  # noqa: E402
from bookscope.explore import explore  # noqa: E402
from bookscope.interests import interests  # noqa: E402
from bookscope.recommend import recommend  # noqa: E402
from bookscope.store import Store  # noqa: E402
from bookscope.trace import trace  # noqa: E402

# Readers below 25952 are on shard0 and the others on shard1 with the default range placement.
REQUESTS = [
    ('F1 explore 7', lambda s: explore(s, book_id=7)),
    ('F1 explore 349', lambda s: explore(s, book_id=349)),
    ('F1 ambiguous title', lambda s: explore(s, query='the hobbit')),
    ('F2 same shard 7/3022', lambda s: compare(s, 7, 3022)),
    ('F2 cross shard 7/48077', lambda s: compare(s, 7, 48077)),
    ('F2 cross shard 2552/39052', lambda s: compare(s, 2552, 39052)),
    ('F2 truncated 3022(5)/7', lambda s: compare(s, 3022, 7, truncate_a=5)),
    ('F3 shard0 reader 7', lambda s: recommend(s, 7)),
    ('F3 shard1 reader 39052', lambda s: recommend(s, 39052)),
    ('F3 truncated 7 (5)', lambda s: recommend(s, 7, truncate=5)),
    ('F4 349 -> 7', lambda s: trace(s, 349, 7)),
    ('F4 9139 -> 2491', lambda s: trace(s, 9139, 2491)),
    ('F5 vampires/romance', lambda s: interests(s, 'vampires', 'romance')),
    ('F5 fantasy/science-fiction', lambda s: interests(s, 'fantasy', 'science-fiction')),
]


def canonical(result):
    result = dict(result)
    result.pop('elapsed_s', None)
    return json.dumps(result, sort_keys=True, ensure_ascii=False, default=str)


def first_difference(a, b):
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return i, a[max(0, i - 60):i + 60], b[max(0, i - 60):i + 60]
    return min(len(a), len(b)), a[-60:], b[-60:]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--a', default='mongodb://localhost:27100')
    ap.add_argument('--b', default='mongodb://localhost:27200')
    args = ap.parse_args()
    store_a, store_b = Store(args.a), Store(args.b)
    failures = 0
    for name, request in REQUESTS:
        ra, rb = canonical(request(store_a)), canonical(request(store_b))
        if ra == rb:
            print(f'IDENTICAL  {name}')
        else:
            failures += 1
            pos, xa, xb = first_difference(ra, rb)
            print(f'DIFFERENT  {name} (first difference at character {pos})\n  a: {xa}\n  b: {xb}')
    print(f'{len(REQUESTS) - failures}/{len(REQUESTS)} requests identical')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())

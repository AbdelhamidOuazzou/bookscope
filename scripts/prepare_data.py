#!/usr/bin/env python3
"""Apply the selection rule to Goodbooks-10k and write the prepared JSON Lines files.

Steps (all deterministic, so the preparation is reproducible):
  1. keep the readers selected by the rule (default: user_id % 5 == 2);
  2. before anything is loaded, set aside two disjoint sets of real ratings:
       - eval_set.jsonl     : the 3 most recent ratings of ~20% of the selected readers (E3)
       - update_batch.jsonl : the 2 most recent ratings of ~5% of other selected readers (F6)
     "Most recent" uses the row order of ratings.csv, which the documentation says is time order;
  3. write books (with their tags), tags, ratings, to_read and a report (sizes, distributions,
     data-quality issues with concrete identifiers).
"""
import argparse
import csv
import hashlib
import json
import statistics
from collections import Counter, defaultdict
from pathlib import Path

# Tag names that describe a reading status, format or ownership rather than the book's content.
STATUS_TAG_PREFIXES = ('to-read', 'currently-reading', 'read-in-', 'read-20', 'read-19', 'owned',
                       'own-', 'i-own', 'books-i-own', 'my-books', 'my-library', 'library', 'kindle',
                       'ebook', 'e-book', 'audiobook', 'audio-book', 'wish', 'tbr', 'dnf', 'default',
                       'favorite', 'favourite', 'fave', 'to-buy', 'borrowed', 're-read', 'read-more-than')
STATUS_TAG_NAMES = {'read', 're-read', 'reread', 'abandoned', 'did-not-finish', 'have', 'books',
                    'book-club', 'bookclub', 'on-hold', 'unfinished', 'own', 'my-favorites'}


def bucket(user_id):
    """Stable pseudo-random bucket in [0, 100), independent of the selection modulo."""
    return int(hashlib.sha1(f'bookscope:{user_id}'.encode()).hexdigest(), 16) % 100


def is_status_tag(name):
    return name in STATUS_TAG_NAMES or name.startswith(STATUS_TAG_PREFIXES)


def quantiles(values):
    values = sorted(values)
    if not values:
        return {}
    pick = lambda q: values[min(len(values) - 1, int(q * len(values)))]
    return {'min': values[0], 'p25': pick(0.25), 'median': statistics.median(values),
            'p75': pick(0.75), 'p95': pick(0.95), 'p99': pick(0.99), 'max': values[-1],
            'mean': round(statistics.fmean(values), 2)}


def write_jsonl(path, docs):
    n = 0
    with path.open('w', encoding='utf-8', newline='\n') as f:
        for doc in docs:
            f.write(json.dumps(doc, ensure_ascii=False, separators=(',', ':')) + '\n')
            n += 1
    return n


def to_int(value):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--raw', type=Path, default=Path('data/raw'))
    ap.add_argument('--output', type=Path, default=Path('data/prepared'))
    ap.add_argument('--modulo', type=int, default=5, help='selection rule: user_id %% modulo == remainder')
    ap.add_argument('--remainder', type=int, default=2)
    ap.add_argument('--all-readers', action='store_true', help='keep every reader (largest E1 subset)')
    ap.add_argument('--eval-share', type=int, default=20, help='%% of selected readers in the evaluation set')
    ap.add_argument('--eval-last', type=int, default=3, help='most recent ratings held out per evaluation reader')
    ap.add_argument('--update-share', type=int, default=5, help='%% of selected readers in the update batch')
    ap.add_argument('--update-last', type=int, default=2)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def selected(user_id):
        return args.all_readers or user_id % args.modulo == args.remainder

    issues = {}

    def issue(key, description, examples, count):
        issues[key] = {'description': description, 'count': count, 'examples': examples[:5]}

    # ---- catalogue -------------------------------------------------------------------
    books, by_goodreads = {}, {}
    missing = defaultdict(list)
    with (args.raw / 'books.csv').open(encoding='utf-8') as f:
        for row in csv.DictReader(f):
            book_id = int(row['book_id'])
            doc = {
                '_id': book_id,
                'goodreads_book_id': int(row['goodreads_book_id']),
                'work_id': int(row['work_id']),
                'title': row['title'],
                'original_title': row['original_title'] or None,
                'authors': [a.strip() for a in row['authors'].split(',') if a.strip()],
                'year': to_int(row['original_publication_year']),
                'language': row['language_code'] or None,
                'isbn': row['isbn'] or None,
                'gr_ratings_count': int(row['ratings_count']),
                'gr_average_rating': float(row['average_rating']),
                'tags': [],
            }
            for field in ('original_title', 'year', 'language', 'isbn'):
                if doc[field] is None:
                    missing[field].append(book_id)
            books[book_id] = doc
            by_goodreads[doc['goodreads_book_id']] = book_id
    for field, ids in missing.items():
        issue(f'missing_{field}', f'books.csv rows with an empty {field}', ids, len(ids))

    titles = defaultdict(list)
    for doc in books.values():
        titles[doc['title'].strip().lower()].append(doc['_id'])
    dup_titles = {t: ids for t, ids in titles.items() if len(ids) > 1}
    issue('ambiguous_titles', 'titles shared by several book_id values (title is not an identifier)',
          [{'title': t, 'book_ids': ids} for t, ids in sorted(dup_titles.items())], len(dup_titles))

    tag_names = {}
    with (args.raw / 'tags.csv').open(encoding='utf-8') as f:
        for row in csv.DictReader(f):
            tag_names[int(row['tag_id'])] = row['tag_name']

    seen_pairs, duplicates, unresolved, non_positive, unknown_tags = {}, [], [], [], []
    with (args.raw / 'book_tags.csv').open(encoding='utf-8') as f:
        for line_no, row in enumerate(csv.DictReader(f), start=2):
            gr_id, tag_id, count = int(row['goodreads_book_id']), int(row['tag_id']), int(row['count'])
            book_id = by_goodreads.get(gr_id)
            if book_id is None:
                unresolved.append({'line': line_no, 'goodreads_book_id': gr_id})
                continue
            if tag_id not in tag_names:
                unknown_tags.append({'line': line_no, 'tag_id': tag_id})
                continue
            if count <= 0:
                non_positive.append({'line': line_no, 'book_id': book_id, 'tag_id': tag_id, 'count': count})
                continue
            key = (book_id, tag_id)
            if key in seen_pairs:
                duplicates.append({'line': line_no, 'book_id': book_id, 'goodreads_book_id': gr_id,
                                   'tag_id': tag_id, 'tag': tag_names[tag_id], 'count': count,
                                   'first_count': seen_pairs[key]['n']})
                # Keep the larger count: duplicated rows describe the same (book, tag) aggregate.
                seen_pairs[key]['n'] = max(seen_pairs[key]['n'], count)
                continue
            entry = {'t': tag_id, 'n': count}
            seen_pairs[key] = entry
            books[book_id]['tags'].append(entry)
    issue('book_tags_duplicates', 'book_tags.csv rows repeating a (goodreads_book_id, tag_id) pair', duplicates, len(duplicates))
    issue('book_tags_non_positive', 'book_tags.csv rows with a count <= 0', non_positive, len(non_positive))
    issue('book_tags_unresolved', 'book_tags.csv rows whose goodreads_book_id is not in books.csv', unresolved, len(unresolved))
    issue('book_tags_unknown_tag', 'book_tags.csv rows whose tag_id is not in tags.csv', unknown_tags, len(unknown_tags))

    tag_df, tag_total = Counter(), Counter()
    for doc in books.values():
        doc['tags'].sort(key=lambda e: (-e['n'], e['t']))
        for e in doc['tags']:
            tag_df[e['t']] += 1
            tag_total[e['t']] += e['n']
    tags = [{'_id': t, 'name': tag_names[t], 'df': tag_df[t], 'total': tag_total[t],
             'status_like': is_status_tag(tag_names[t])} for t in sorted(tag_df)]
    status = sorted((t for t in tags if t['status_like']), key=lambda t: -t['df'])
    issue('status_tags', 'tags describing reading status/ownership rather than content (by number of books)',
          [{'tag_id': t['_id'], 'name': t['name'], 'books': t['df']} for t in status], len(status))

    # ---- reader activity -----------------------------------------------------------------
    history = defaultdict(list)  # user_id -> [(seq, book_id, rating)]
    pairs, dup_ratings, unknown_books = set(), [], []
    with (args.raw / 'ratings.csv').open(encoding='utf-8') as f:
        reader = csv.reader(f)
        next(reader)
        for seq, (user, book, rating) in enumerate(reader, start=1):
            user_id = int(user)
            if not selected(user_id):
                continue
            book_id = int(book)
            if book_id not in books:
                unknown_books.append({'row': seq, 'user_id': user_id, 'book_id': book_id})
                continue
            if (user_id, book_id) in pairs:
                dup_ratings.append({'row': seq, 'user_id': user_id, 'book_id': book_id})
                continue
            pairs.add((user_id, book_id))
            history[user_id].append((seq, book_id, int(rating)))
    issue('ratings_duplicate_pairs', 'ratings.csv rows repeating a (user_id, book_id) pair (selected readers)', dup_ratings, len(dup_ratings))
    issue('ratings_unknown_book', 'ratings.csv rows whose book_id is not in books.csv (selected readers)', unknown_books, len(unknown_books))

    ratings, eval_set, update_batch = [], [], []
    for user_id in sorted(history):
        rows = history[user_id]  # already in time (row) order
        b = bucket(user_id)
        if b < args.eval_share:
            cut, target = args.eval_last, eval_set
        elif b < args.eval_share + args.update_share:
            cut, target = args.update_last, update_batch
        else:
            cut, target = 0, None
        kept = rows[:len(rows) - cut] if cut else rows
        for seq, book_id, rating in kept:
            ratings.append({'user_id': user_id, 'book_id': book_id, 'rating': rating, 'seq': seq})
        if target is not None:
            for seq, book_id, rating in rows[len(rows) - cut:]:
                target.append({'user_id': user_id, 'book_id': book_id, 'rating': rating, 'seq': seq})

    to_read, to_read_rated, to_read_dup, seen_tr = [], [], [], set()
    with (args.raw / 'to_read.csv').open(encoding='utf-8') as f:
        reader = csv.reader(f)
        next(reader)
        for seq, (user, book) in enumerate(reader, start=1):
            user_id, book_id = int(user), int(book)
            if not selected(user_id) or book_id not in books:
                continue
            if (user_id, book_id) in seen_tr:
                to_read_dup.append({'row': seq, 'user_id': user_id, 'book_id': book_id})
                continue
            seen_tr.add((user_id, book_id))
            if (user_id, book_id) in pairs:
                to_read_rated.append({'row': seq, 'user_id': user_id, 'book_id': book_id})
            to_read.append({'user_id': user_id, 'book_id': book_id, 'seq': seq})
    issue('to_read_also_rated', 'to_read.csv entries for a book the same reader also rated', to_read_rated, len(to_read_rated))
    issue('to_read_duplicates', 'to_read.csv rows repeating a (user_id, book_id) pair', to_read_dup, len(to_read_dup))

    per_reader = Counter(r['user_id'] for r in ratings)
    per_book = Counter(r['book_id'] for r in ratings)
    unrated = [b for b in sorted(books) if b not in per_book]
    issue('books_unrated_in_selection', 'catalogue books with no rating among the selected readers', unrated, len(unrated))

    report = {
        'selection_rule': 'all readers' if args.all_readers else f'user_id % {args.modulo} == {args.remainder}',
        'holdout': {
            'eval_set': f'{args.eval_last} most recent ratings of readers with bucket < {args.eval_share}',
            'update_batch': f'{args.update_last} most recent ratings of readers with {args.eval_share} <= bucket < {args.eval_share + args.update_share}',
            'bucket': "int(sha1('bookscope:<user_id>'), 16) % 100",
        },
        'counts': {
            'readers': len(per_reader),
            'books_in_catalogue': len(books),
            'books_rated': len(per_book),
            'ratings': len(ratings),
            'eval_set': len(eval_set),
            'update_batch': len(update_batch),
            'to_read': len(to_read),
            'tags': len(tags),
            'book_tag_links': sum(len(b['tags']) for b in books.values()),
        },
        'ratings_per_reader': quantiles(list(per_reader.values())),
        'ratings_per_book': quantiles(list(per_book.values())),
        'rating_values': dict(sorted(Counter(r['rating'] for r in ratings).items())),
        'issues': issues,
    }

    write_jsonl(args.output / 'books.jsonl', (books[b] for b in sorted(books)))
    write_jsonl(args.output / 'tags.jsonl', tags)
    write_jsonl(args.output / 'ratings.jsonl', ratings)
    write_jsonl(args.output / 'to_read.jsonl', to_read)
    write_jsonl(args.output / 'eval_set.jsonl', eval_set)
    write_jsonl(args.output / 'update_batch.jsonl', update_batch)
    (args.output / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')

    print(f"Selection: {report['selection_rule']}")
    for key, value in report['counts'].items():
        print(f'  {key:<20} {value:>10,}')
    print('Data issues:')
    for key, value in issues.items():
        print(f"  {key:<28} {value['count']:>8,}")
    print(f'Prepared files in {args.output.resolve()}')


if __name__ == '__main__':
    main()

"""Command-line interface: python -m bookscope <service> ...

The deployment is chosen with --uri or $BOOKSCOPE_URI:
  mongodb://localhost:27100   local server (Part I)
  mongodb://localhost:27200   mongos of the sharded deployment (Part II)
"""
import argparse
import json
import sys
import time

from pymongo.errors import PyMongoError

from .compare import compare
from .explore import explore
from .interests import interests
from .recommend import recommend
from .store import DEFAULT_URI, BookScopeError, Store
from .trace import trace
from .update import records, update


def ints(text):
    return [int(x) for x in text.split(',') if x.strip()]


def build_parser():
    ap = argparse.ArgumentParser(prog='bookscope', description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--uri', default=DEFAULT_URI)
    ap.add_argument('--json', action='store_true', help='print the raw JSON result')
    sub = ap.add_subparsers(dest='command', required=True)

    p = sub.add_parser('explore', help='F1: a book and its connections')
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument('--title')
    g.add_argument('--id', type=int)
    p.add_argument('-k', type=int, default=10)
    p.add_argument('--min-shared', type=int, default=5)

    p = sub.add_parser('compare', help='F2: compare two readers')
    p.add_argument('user_a', type=int)
    p.add_argument('user_b', type=int)
    p.add_argument('--truncate-a', type=int)
    p.add_argument('--truncate-b', type=int)
    p.add_argument('-k', type=int, default=10)

    p = sub.add_parser('recommend', help='F3: recommend books to a reader')
    p.add_argument('user', type=int)
    p.add_argument('-k', type=int, default=10)
    p.add_argument('--truncate', type=int, help='use only the k earliest ratings of the reader')

    p = sub.add_parser('trace', help='F4: connection between two books')
    p.add_argument('book_a', type=int)
    p.add_argument('book_b', type=int)
    p.add_argument('-k', type=int, default=5)

    p = sub.add_parser('interests', help='F5: links between two tags')
    p.add_argument('tag_a')
    p.add_argument('tag_b')
    p.add_argument('-k', type=int, default=5)
    p.add_argument('--min-count', type=int, default=20)

    p = sub.add_parser('update', help='F6: import a batch of ratings (jsonl or csv)')
    p.add_argument('path')

    p = sub.add_parser('records', help='list the stored records behind a result')
    p.add_argument('--users', type=ints, default=[])
    p.add_argument('--books', type=ints, default=[])
    p.add_argument('--tags', action='store_true')

    sub.add_parser('info', help='deployment, selection and load information')
    return ap


def run(store, args):
    if args.command == 'explore':
        return explore(store, query=args.title, book_id=args.id, k=args.k, min_shared=args.min_shared)
    if args.command == 'compare':
        return compare(store, args.user_a, args.user_b, args.truncate_a, args.truncate_b, k=args.k)
    if args.command == 'recommend':
        return recommend(store, args.user, k=args.k, truncate=args.truncate)
    if args.command == 'trace':
        return trace(store, args.book_a, args.book_b, k=args.k)
    if args.command == 'interests':
        return interests(store, args.tag_a, args.tag_b, k=args.k, min_count=args.min_count)
    if args.command == 'update':
        return update(store, args.path)
    if args.command == 'records':
        return records(store, args.users, args.books, args.tags)
    if args.command == 'info':
        hello = store.client.admin.command('hello')
        load = store.db.meta.find_one({'_id': 'load'}) or {}
        load.pop('_id', None)
        return {'service': 'info', 'status': 'ok', 'uri': store.uri,
                'deployment': 'sharded (mongos)' if hello.get('msg') == 'isdbgrid' else 'single server', **load}
    raise ValueError(args.command)


def flat(value):
    """Short one-line form for a dict/list made only of scalars, else None."""
    if isinstance(value, dict) and all(not isinstance(v, (dict, list)) for v in value.values()):
        return ', '.join(f'{k}={v}' for k, v in value.items() if v is not None)
    if isinstance(value, list) and all(not isinstance(v, (dict, list)) for v in value):
        return ', '.join(str(v) for v in value)
    return None


def render(value, indent=0):
    pad = '  ' * indent
    if isinstance(value, dict):
        for key, v in value.items():
            if v is None or v == [] or v == {}:
                continue
            if isinstance(v, (dict, list)) and flat(v) is None:
                print(f'{pad}{key}:')
                render(v, indent + 1)
            else:
                print(f'{pad}{key}: {flat(v) if isinstance(v, (dict, list)) else v}')
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, dict) and flat(item) is not None:
                print(f'{pad}- {flat(item)}')
            elif isinstance(item, dict):
                first = True
                for key, v in item.items():
                    if v is None or v == [] or v == {}:
                        continue
                    prefix = f'{pad}- ' if first else f'{pad}  '
                    first = False
                    if isinstance(v, (dict, list)) and flat(v) is None:
                        print(f'{prefix}{key}:')
                        render(v, indent + 2)
                    else:
                        print(f'{prefix}{key}: {flat(v) if isinstance(v, (dict, list)) else v}')
            else:
                print(f'{pad}- {item}')
    else:
        print(f'{pad}{value}')


def main(argv=None):
    args = build_parser().parse_args(argv)
    store = Store(args.uri)
    start = time.perf_counter()
    try:
        result = run(store, args)
    except BookScopeError as exc:
        result = {'service': args.command, 'status': 'error', 'message': str(exc)}
    except PyMongoError as exc:
        # A missing shard makes mongos fail the whole request: no partial answer is returned.
        result = {'service': args.command, 'status': 'unavailable',
                  'message': 'a data-serving instance needed for this request is unreachable; '
                             'no result is returned because it would be incomplete',
                  'error': f'{type(exc).__name__}: {str(exc)[:300]}'}
    result['elapsed_s'] = round(time.perf_counter() - start, 3)
    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False, default=str))
    else:
        render(result)
    return 0 if result['status'] in ('ok', 'ok_with_rejections') else 1


if __name__ == '__main__':
    sys.exit(main())

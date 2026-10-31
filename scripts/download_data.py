#!/usr/bin/env python3
"""Download the Goodbooks-10k CSV files into data/raw/.

Source: https://github.com/zygmuntz/goodbooks-10k (files are not versioned in this repo).
"""
import argparse
import hashlib
from pathlib import Path
from urllib.request import urlretrieve

BASE_URL = 'https://raw.githubusercontent.com/zygmuntz/goodbooks-10k/master/'
FILES = ['books.csv', 'ratings.csv', 'to_read.csv', 'book_tags.csv', 'tags.csv']


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output', type=Path, default=Path('data/raw'))
    ap.add_argument('--force', action='store_true', help='download again even if the file exists')
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        target = args.output / name
        if target.exists() and not args.force:
            print(f'{name}: already present')
        else:
            print(f'{name}: downloading ...')
            urlretrieve(BASE_URL + name, target)
        print(f'  {target.stat().st_size:>12,} bytes  sha256={sha256(target)[:16]}')


if __name__ == '__main__':
    main()

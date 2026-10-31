#!/usr/bin/env python3
"""Start and stop the MongoDB deployments used by BookScope, all on this workstation.

  local    one mongod (port 27100)                      -> mongodb://localhost:27100
  sharded  1 config server (27201), N shard servers     -> mongodb://localhost:27200 (mongos)
           (27211, 27212, ...), 1 mongos router (27200)

Each mongod has its own dbpath under ~/.bookscope/db/ (or $BOOKSCOPE_DB_ROOT) and a capped WiredTiger cache, so several
instances can share one machine. They still share CPU, memory and disk: this is not a cluster
of independent machines.

Examples:
  python scripts/cluster.py start local
  python scripts/cluster.py start sharded --shards 2
  python scripts/cluster.py stop-shard 1          # simulate losing one data-serving instance
  python scripts/cluster.py start-shard 1
  python scripts/cluster.py status
  python scripts/cluster.py stop all
"""
import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from pymongo import MongoClient
from pymongo.errors import AutoReconnect, ConnectionFailure, OperationFailure

# Kept outside the project by default: WiredTiger file names are long and Windows limits
# paths to 260 characters.
DB_ROOT = Path(os.environ.get('BOOKSCOPE_DB_ROOT', Path.home() / '.bookscope' / 'db'))
LOCAL_PORT = 27100
MONGOS_PORT = 27200
CONFIG_PORT = 27201
SHARD_BASE_PORT = 27211
CACHE_GB = '0.5'


def binary(name):
    path = shutil.which(name)
    if not path:
        sys.exit(f'{name} not found on PATH')
    return path


def client(port, direct=True):
    return MongoClient(f'mongodb://localhost:{port}/', directConnection=direct,
                       serverSelectionTimeoutMS=2000)


def is_up(port):
    try:
        client(port).admin.command('ping')
        return True
    except ConnectionFailure:
        return False


def spawn(name, args, port):
    if is_up(port):
        print(f'{name}: already running on {port}')
        return
    log = DB_ROOT / f'{name}.log'
    DB_ROOT.mkdir(parents=True, exist_ok=True)
    flags = 0
    if os.name == 'nt':
        flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
    subprocess.Popen(args + ['--port', str(port), '--bind_ip', '127.0.0.1', '--logpath', str(log), '--logappend'],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags,
                     start_new_session=(os.name != 'nt'))
    for _ in range(60):
        if is_up(port):
            print(f'{name}: started on {port}')
            return
        time.sleep(0.5)
    sys.exit(f'{name}: did not start, see {log}')


def mongod(name, port, extra=()):
    dbpath = DB_ROOT / name
    dbpath.mkdir(parents=True, exist_ok=True)
    spawn(name, [binary('mongod'), '--dbpath', str(dbpath), '--wiredTigerCacheSizeGB', CACHE_GB, *extra], port)


def init_replset(name, port, configsvr=False):
    admin = client(port).admin
    try:
        admin.command('replSetGetStatus')
    except OperationFailure:
        config = {'_id': name, 'members': [{'_id': 0, 'host': f'localhost:{port}'}]}
        if configsvr:
            config['configsvr'] = True
        admin.command('replSetInitiate', config)
    for _ in range(60):
        if admin.command('hello').get('isWritablePrimary'):
            return
        time.sleep(0.5)
    sys.exit(f'{name}: no primary elected')


def shard_name(i):
    return f'shard{i}'


def start_local():
    mongod('local', LOCAL_PORT)
    print(f'URI: mongodb://localhost:{LOCAL_PORT}')


def start_sharded(n_shards):
    mongod('config', CONFIG_PORT, ['--configsvr', '--replSet', 'cfg'])
    init_replset('cfg', CONFIG_PORT, configsvr=True)
    for i in range(n_shards):
        name = shard_name(i)
        mongod(name, SHARD_BASE_PORT + i, ['--shardsvr', '--replSet', name])
        init_replset(name, SHARD_BASE_PORT + i)
    spawn('mongos', [binary('mongos'), '--configdb', f'cfg/localhost:{CONFIG_PORT}'], MONGOS_PORT)
    admin = client(MONGOS_PORT).admin
    known = {s['_id'] for s in admin.command('listShards')['shards']}
    for i in range(n_shards):
        if shard_name(i) not in known:
            admin.command('addShard', f'{shard_name(i)}/localhost:{SHARD_BASE_PORT + i}', name=shard_name(i))
            print(f'{shard_name(i)}: added to the cluster')
    print(f'URI: mongodb://localhost:{MONGOS_PORT}')


def shutdown(name, port):
    if not is_up(port):
        return
    try:
        client(port).admin.command('shutdown', force=True)
    except (AutoReconnect, ConnectionFailure):
        pass
    for _ in range(40):
        if not is_up(port):
            print(f'{name}: stopped')
            return
        time.sleep(0.5)
    print(f'{name}: still running on {port}')


def running_shards():
    return [i for i in range(8) if is_up(SHARD_BASE_PORT + i) or (DB_ROOT / shard_name(i)).exists()]


def status():
    rows = [('local', LOCAL_PORT), ('mongos', MONGOS_PORT), ('config', CONFIG_PORT)]
    rows += [(shard_name(i), SHARD_BASE_PORT + i) for i in running_shards()]
    for name, port in rows:
        print(f'{name:<8} {port}  {"up" if is_up(port) else "down"}')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('action', choices=['start', 'stop', 'status', 'stop-shard', 'start-shard', 'reset'])
    ap.add_argument('target', nargs='?', default='all')
    ap.add_argument('--shards', type=int, default=2)
    args = ap.parse_args()

    if args.action == 'status':
        status()
    elif args.action == 'start':
        if args.target == 'local':
            start_local()
        elif args.target == 'sharded':
            start_sharded(args.shards)
        else:
            sys.exit('start local | start sharded')
    elif args.action == 'stop':
        if args.target in ('sharded', 'all'):
            shutdown('mongos', MONGOS_PORT)
            for i in running_shards():
                shutdown(shard_name(i), SHARD_BASE_PORT + i)
            shutdown('config', CONFIG_PORT)
        if args.target in ('local', 'all'):
            shutdown('local', LOCAL_PORT)
    elif args.action == 'stop-shard':
        i = int(args.target)
        shutdown(shard_name(i), SHARD_BASE_PORT + i)
    elif args.action == 'start-shard':
        i = int(args.target)
        mongod(shard_name(i), SHARD_BASE_PORT + i, ['--shardsvr', '--replSet', shard_name(i)])
    elif args.action == 'reset':
        # Removes every dbpath: use it to switch the sharded deployment to another configuration.
        if any(is_up(p) for p in (LOCAL_PORT, MONGOS_PORT, CONFIG_PORT)):
            sys.exit('stop the deployments first')
        shutil.rmtree(DB_ROOT, ignore_errors=True)
        print(f'removed {DB_ROOT}')


if __name__ == '__main__':
    main()

#!/bin/bash
# One-shot: starts the three replica sets and registers both shards with mongos.
set -e
wait_for() { until mongosh --quiet --host "$1" --eval 'db.adminCommand({ping: 1}).ok' >/dev/null 2>&1; do sleep 1; done; }
initiate() { # host:port, replica set name, extra option
  wait_for "$1"
  mongosh --quiet --host "$1" --eval "
    try { rs.status(); print('$2 already initiated'); }
    catch (e) { rs.initiate({_id: '$2', $3 members: [{_id: 0, host: '$1'}]}); print('$2 initiated'); }
    while (!db.hello().isWritablePrimary) { sleep(500); }"
}
initiate cfg:27019 cfg "configsvr: true,"
initiate shard0:27018 shard0 ""
initiate shard1:27018 shard1 ""
wait_for mongos:27017
mongosh --quiet --host mongos:27017 --eval "
  const have = db.adminCommand({listShards: 1}).shards.map(s => s._id);
  for (const s of ['shard0', 'shard1']) {
    if (!have.includes(s)) { db.adminCommand({addShard: s + '/' + s + ':27018', name: s}); }
  }
  printjson(db.adminCommand({listShards: 1}).shards.map(s => s._id + ' ' + s.host));"
echo "cluster ready: mongodb://localhost:27300"

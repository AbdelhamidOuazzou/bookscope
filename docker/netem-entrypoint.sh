#!/bin/bash
# Adds DELAY_MS milliseconds to every packet leaving this container, then starts MongoDB.
# Two containers each adding DELAY_MS give a round trip of about 2 x DELAY_MS, like two machines on a LAN.
if [ -n "$DELAY_MS" ] && [ "$DELAY_MS" != "0" ]; then
  tc qdisc add dev eth0 root netem delay "${DELAY_MS}ms" || echo "netem not available: no delay added"
fi
exec docker-entrypoint.sh "$@"

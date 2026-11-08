# MongoDB image with the tc tool, to add a network delay between containers (netem).
ARG MONGO_VERSION=9.0
FROM mongo:${MONGO_VERSION}
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 && rm -rf /var/lib/apt/lists/*
COPY netem-entrypoint.sh /usr/local/bin/netem-entrypoint.sh
RUN chmod +x /usr/local/bin/netem-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/netem-entrypoint.sh"]

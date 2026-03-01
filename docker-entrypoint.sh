#!/bin/sh
# docker-entrypoint.sh
# Sets ECS-specific sharding environment variables before starting the JVM.
# In non-ECS environments (local docker run) these default to hostname/localhost.

# AMBONMUD_SHARDING_ENGINEID: unique per task — use container hostname (ECS task short ID)
if [ -z "$AMBONMUD_SHARDING_ENGINEID" ]; then
  export AMBONMUD_SHARDING_ENGINEID="$(hostname)"
fi

# AMBONMUD_SHARDING_ADVERTISEHOST: this engine's reachable IP for gRPC handoff routing.
# In ECS with Cloud Map, Gateway connects via DNS; AdvertiseHost is used by the
# inter-engine routing layer. Default to the container's primary private IP.
if [ -z "$AMBONMUD_SHARDING_ADVERTISEHOST" ]; then
  PRIVATE_IP="$(hostname -I 2>/dev/null | cut -d' ' -f1)"
  export AMBONMUD_SHARDING_ADVERTISEHOST="${PRIVATE_IP:-localhost}"
fi

exec java -Djava.net.preferIPv4Stack=true -jar /app/app.jar "$@"

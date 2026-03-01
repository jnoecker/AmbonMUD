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
  if [ -z "$PRIVATE_IP" ]; then
    echo "WARNING: Could not detect private IP; AMBONMUD_SHARDING_ADVERTISEHOST defaulting to localhost. Inter-engine routing will fail in split topology." >&2
    PRIVATE_IP="localhost"
  fi
  export AMBONMUD_SHARDING_ADVERTISEHOST="$PRIVATE_IP"
fi

exec java -Djava.net.preferIPv4Stack=true -jar /app/app.jar "$@"

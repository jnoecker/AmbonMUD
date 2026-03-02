# Stage 1: build the web client
FROM oven/bun:latest AS frontend
WORKDIR /build

# Install dependencies first for layer caching
COPY web-v3/package.json web-v3/bun.lock web-v3/
RUN cd web-v3 && bun install --frozen-lockfile

# Copy source and build.
# Vite config writes output to ../src/main/resources/web-v3 (relative to web-v3/),
# which resolves to /build/src/main/resources/web-v3 in this stage.
COPY web-v3/ web-v3/
RUN cd web-v3 && bun run build

# Stage 2: build the fat JAR
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY src/main/proto/ src/main/proto/

# Resolve dependencies (cached layer if build files unchanged)
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon -q

# Copy source, inject the built frontend, then build the fat JAR
COPY src/ src/
COPY --from=frontend /build/src/main/resources/web-v3/ src/main/resources/web-v3/
RUN ./gradlew shadowJar --no-daemon -x test

# Stage 3: minimal JRE runtime
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user for security — pin UID/GID 1001 so host volume mounts can be
# chowned to a known ID without needing to inspect the running container.
RUN groupadd -r -g 1001 ambonmud && useradd -r -u 1001 -g ambonmud ambonmud

COPY --from=builder /build/build/libs/*-all.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# World mutations persistent storage mount point
RUN mkdir -p /app/data \
    && chmod +x /app/docker-entrypoint.sh \
    && chown -R ambonmud:ambonmud /app

USER ambonmud

# Telnet, WebSocket/HTTP, Metrics/Admin, gRPC
EXPOSE 4000 8080 9090 9091

ENTRYPOINT ["/app/docker-entrypoint.sh"]

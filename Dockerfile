# Stage 1: build the fat JAR
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY src/main/proto/ src/main/proto/

# Resolve dependencies (cached layer if build files unchanged)
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build
COPY src/ src/
RUN ./gradlew shadowJar --no-daemon -x test

# Stage 2: minimal JRE runtime
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user for security
RUN groupadd -r ambonmud && useradd -r -g ambonmud ambonmud

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

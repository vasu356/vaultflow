# ── Build stage ────────────────────────────────────────────────────────────
# maven:3.9.6-eclipse-temurin-21-alpine has BOTH Maven 3.9 AND JDK 21.
# Your local Java 25 is NOT used here — compilation always uses JDK 21.
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# 1. Copy the root pom.xml
COPY pom.xml .

# 2. Copy ALL service pom.xml files so the Maven reactor can resolve
#    the multi-module project without missing-module errors.
#    Source code for other services is NOT copied — only their pom stubs.
COPY common/pom.xml                 common/pom.xml
COPY auth-service/pom.xml           auth-service/pom.xml
COPY upload-service/pom.xml         upload-service/pom.xml
COPY download-service/pom.xml       download-service/pom.xml
COPY processing-service/pom.xml     processing-service/pom.xml
COPY metadata-service/pom.xml       metadata-service/pom.xml
COPY notification-service/pom.xml   notification-service/pom.xml
COPY admin-service/pom.xml          admin-service/pom.xml

# 3. Download dependencies for common + this service only.
#    Cached layer — only re-runs when any pom.xml changes.
RUN mvn dependency:go-offline       -pl common,metadata-service -am       --no-transfer-progress -q 2>&1 || true

# 4. Copy source code for common + this service only
COPY common/src       common/src
COPY metadata-service/src  metadata-service/src

# 5. Build
RUN mvn package       -pl common,metadata-service -am       -DskipTests       --no-transfer-progress

# ── Runtime stage ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S vaultflow && adduser -S -G vaultflow -u 1001 vaultflow

WORKDIR /app
COPY --from=builder /build/metadata-service/target/metadata-service-*.jar app.jar
RUN chown vaultflow:vaultflow app.jar
USER vaultflow
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8084
HEALTHCHECK --interval=20s --timeout=10s --start-period=120s --retries=6   CMD wget -q -O- http://localhost:8084/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy POMs first (layer cache: changes less frequently than source)
COPY pom.xml                            /build/pom.xml
COPY shared/common-dto/pom.xml          /build/shared/common-dto/pom.xml
COPY shared/common-events/pom.xml       /build/shared/common-events/pom.xml
COPY shared/common-security/pom.xml     /build/shared/common-security/pom.xml
COPY services/api-gateway/pom.xml       /build/services/api-gateway/pom.xml

# Download dependencies — cached as long as no pom.xml changes
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline \
    -pl services/api-gateway -am

# Copy source (cache invalidated only when source changes)
COPY shared/                            /build/shared/
COPY services/api-gateway/src           /build/services/api-gateway/src

# Build — tests run in CI before this image is built
RUN mvn --batch-mode --no-transfer-progress package -DskipTests \
    -pl services/api-gateway -am

# Extract Spring Boot layered JAR for optimal layer reuse
WORKDIR /build/services/api-gateway/target
RUN java -Djarmode=layertools -jar api-gateway-*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as a non-root user
RUN addgroup -S parkride && adduser -S gateway -G parkride

WORKDIR /app

# Copy extracted Spring Boot layers (dependencies first — changes least often)
COPY --from=builder /build/services/api-gateway/target/dependencies/          ./
COPY --from=builder /build/services/api-gateway/target/spring-boot-loader/    ./
COPY --from=builder /build/services/api-gateway/target/snapshot-dependencies/ ./
COPY --from=builder /build/services/api-gateway/target/application/           ./

USER gateway

# API Gateway listens on 8080 (public edge port)
EXPOSE 8080

# JVM tuning for containerised environments
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]

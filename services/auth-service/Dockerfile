# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Uses the full JDK to compile and run Maven. The resulting JAR is then
# extracted into layers for efficient Docker layer caching.
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy the parent POM and shared module POMs first (layer cache optimisation).
# These change less frequently than source code, so Docker can cache this layer.
COPY pom.xml                          /build/pom.xml
COPY shared/common-dto/pom.xml        /build/shared/common-dto/pom.xml
COPY shared/common-events/pom.xml     /build/shared/common-events/pom.xml
COPY shared/common-security/pom.xml   /build/shared/common-security/pom.xml
COPY services/auth-service/pom.xml    /build/services/auth-service/pom.xml

# Download dependencies — this layer is cached as long as no pom.xml changes.
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline -pl services/auth-service -am

# Copy source code (invalidates cache only when source changes)
COPY shared/      /build/shared/
COPY services/auth-service/src /build/services/auth-service/src

# Build and skip tests — tests run in CI before this image is built.
RUN mvn --batch-mode --no-transfer-progress package -DskipTests \
    -pl services/auth-service -am

# Extract Spring Boot layered JAR for optimal layer reuse
WORKDIR /build/services/auth-service/target
RUN java -Djarmode=layertools -jar auth-service-*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Minimal JRE image — no build tools, no JDK. ~180MB vs ~500MB single-stage.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as a non-root user
RUN addgroup -S parkride && adduser -S authsvc -G parkride

WORKDIR /app

# Copy extracted Spring Boot layers in order of change frequency
# (dependencies layer changes least, application layer changes most)
COPY --from=builder /build/services/auth-service/target/dependencies/          ./
COPY --from=builder /build/services/auth-service/target/spring-boot-loader/    ./
COPY --from=builder /build/services/auth-service/target/snapshot-dependencies/ ./
COPY --from=builder /build/services/auth-service/target/application/           ./

USER authsvc

# Kubernetes health probes point to these
EXPOSE 8081

# JVM tuning for containerised environments:
# -XX:+UseContainerSupport — respects cgroup memory/CPU limits (not host limits)
# -XX:MaxRAMPercentage=75  — use 75% of container memory for the JVM heap
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]

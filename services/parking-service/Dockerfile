# ── Stage 1: Build ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POMs first (layer-cached until pom changes)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY shared/common-dto/pom.xml       shared/common-dto/pom.xml
COPY shared/common-events/pom.xml    shared/common-events/pom.xml
COPY shared/common-security/pom.xml  shared/common-security/pom.xml
COPY services/parking-service/pom.xml services/parking-service/pom.xml

RUN ./mvnw dependency:go-offline -pl services/parking-service -am -q

# Copy source
COPY shared/common-dto/src       shared/common-dto/src
COPY shared/common-events/src    shared/common-events/src
COPY shared/common-security/src  shared/common-security/src
COPY services/parking-service/src services/parking-service/src

RUN ./mvnw package -DskipTests -pl services/parking-service -am -q

# Extract layered JAR for optimal Docker layer caching
RUN java -Djarmode=layertools \
    -jar services/parking-service/target/parking-service-*.jar extract \
    --destination /build/layers

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="Park & Ride Team"
LABEL service="parking-service"
LABEL version="1.0.0"

# Non-root user for security
RUN addgroup -S parkride && adduser -S parking -G parkride

WORKDIR /app

# Copy extracted layers in cache-friendly order
COPY --from=builder --chown=parking:parkride /build/layers/dependencies/          ./
COPY --from=builder --chown=parking:parkride /build/layers/spring-boot-loader/    ./
COPY --from=builder --chown=parking:parkride /build/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=parking:parkride /build/layers/application/           ./

USER parking

EXPOSE 8082

# Actuator health for Docker/Kubernetes readiness probes
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]

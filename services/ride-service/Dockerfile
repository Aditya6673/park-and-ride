# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY pom.xml                            /build/pom.xml
COPY shared/common-dto/pom.xml          /build/shared/common-dto/pom.xml
COPY shared/common-events/pom.xml       /build/shared/common-events/pom.xml
COPY shared/common-security/pom.xml     /build/shared/common-security/pom.xml
COPY services/ride-service/pom.xml      /build/services/ride-service/pom.xml

RUN mvn --batch-mode --no-transfer-progress dependency:go-offline \
    -pl services/ride-service -am

COPY shared/                            /build/shared/
COPY services/ride-service/src          /build/services/ride-service/src

RUN mvn --batch-mode --no-transfer-progress package -DskipTests \
    -pl services/ride-service -am

WORKDIR /build/services/ride-service/target
RUN java -Djarmode=layertools -jar ride-service-*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S parkride && adduser -S ridesvc -G parkride

WORKDIR /app

COPY --from=builder /build/services/ride-service/target/dependencies/          ./
COPY --from=builder /build/services/ride-service/target/spring-boot-loader/    ./
COPY --from=builder /build/services/ride-service/target/snapshot-dependencies/ ./
COPY --from=builder /build/services/ride-service/target/application/           ./

USER ridesvc

EXPOSE 8086

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]

# Multi-stage: build with the JDK and Maven, ship only a JRE and one jar.
#
# The runtime image carries no compiler, no build tools and no source. That matters more than
# image size here: this container is pointed at a production database, so the less it contains,
# the less there is to go wrong or be exploited.

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies first, in their own layer. They change far less often than the source, so editing
# a check does not re-download the world.
COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose. The integration tests need a live PostgreSQL, which a
# docker build does not have; they run in CI and in scripts/test.sh, where one exists.
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

# ---------------------------------------------------------------- runtime
# jammy rather than alpine: Temurin publishes no arm64 Alpine JRE for 17, so an
# alpine base builds on x86_64 and fails outright on an Apple Silicon machine.
FROM eclipse-temurin:25-jre-jammy

LABEL org.opencontainers.image.title="Production Triage Toolkit" \
      org.opencontainers.image.description="Read-only SQL diagnostics for PostgreSQL, ranked by severity and linked to runbooks." \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT"

# Never run as root. The tool needs nothing but a network socket.
RUN groupadd --system triage && useradd --system --gid triage --create-home triage

WORKDIR /app
COPY --from=build /build/target/triage.jar /app/triage.jar

# The runbooks travel with the image so that a finding's runbook path resolves to a file that is
# actually present, rather than to a README somebody has to go and find.
COPY runbooks /app/runbooks

USER triage

# The password is read from the environment, never from an argument. Pass it at run time:
#   docker run --rm -e PGPASSWORD="$PGPASSWORD" triage --host db.internal --database bookings
ENV PGHOST=localhost \
    PGPORT=5432 \
    PGDATABASE=postgres \
    PGUSER=postgres

ENTRYPOINT ["java", "-jar", "/app/triage.jar"]
CMD ["--help"]

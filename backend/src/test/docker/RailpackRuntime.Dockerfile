# Production Railpack runtime, plus the deploy apt packages from railpack.json.
# Railpack's Java provider then contributes only /app/target.
ARG RUNTIME_IMAGE=ghcr.io/railwayapp/railpack-runtime:debian-trixie
FROM ${RUNTIME_IMAGE}

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl coreutils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY railway-start.sh /app/target/railway-start.sh
COPY restaurant-api-0.0.1-SNAPSHOT.jar /app/target/restaurant-api-0.0.1-SNAPSHOT.jar
COPY verify-runtime.sh /verify-runtime.sh
RUN chmod 755 /verify-runtime.sh

ENTRYPOINT ["/verify-runtime.sh"]

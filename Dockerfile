FROM gradle:9.7.1-jdk25-alpine@sha256:279f6cae59d5a877d1767d8f12a3a74c569c46d70121e7fc465e6f8b3862e0c5 AS build

WORKDIR /home/gradle/project

COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon

COPY . .
RUN --mount=type=secret,id=sentry_token,env=SENTRY_AUTH_TOKEN ./gradlew clean build --no-daemon -x test
RUN find build/libs -name "worker-*.jar" \
      ! -name "*-javadoc.jar" \
      ! -name "*-sources.jar" \
      ! -name "*-plain.jar" \
      -exec mv {} /app.jar \;


FROM eclipse-temurin:25-jre-noble@sha256:b573af9e331196fbc42e246da4df24df9b6c556c73e7efddfde0511f1c9508c5 AS service
LABEL authors="anisekai"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system worker \
    && useradd --system --gid worker --home-dir /app worker \
    && mkdir -p /app /tmp/anisekai-worker \
    && chown -R worker:worker /app /tmp/anisekai-worker

WORKDIR /app
COPY --from=build --chown=worker:worker /app.jar ./app.jar

USER worker

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

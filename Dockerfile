FROM gradle:9.7.1-jdk25-alpine AS build

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


FROM debian:bookworm-slim AS ffmpeg
WORKDIR /tmp
RUN apt-get update && apt-get install -y curl xz-utils && \
    curl -sL https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz -o ffmpeg.tar.xz && \
    tar -xf ffmpeg.tar.xz && \
    mv ffmpeg-*-amd64-static /ffmpeg && \
    rm -rf /var/lib/apt/lists/* /tmp/*


FROM openjdk:25-bookworm AS service
LABEL authors="anisekai"

WORKDIR /app

COPY --from=ffmpeg /ffmpeg /opt/ffmpeg
COPY --from=ffmpeg /ffmpeg/ffmpeg /usr/bin/ffmpeg
COPY --from=ffmpeg /ffmpeg/ffprobe /usr/bin/ffprobe

COPY --from=build /app.jar .

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
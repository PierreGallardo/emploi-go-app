FROM eclipse-temurin:25.0.4.1_1-jdk-resolute@sha256:3eb6c642ea5e6a5dbfa667971a07f30acd1ee0597bb7f6bbec21015251c5903d AS build
# Without unzip, the Maven wrapper downloads the tar.gz distribution and refuses its checksum, set for the zip.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY --link mvnw pom.xml ./
COPY --link .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY --link src/main src/main
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.4.1_1-jre-alpine-3.24@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
RUN apk add --no-cache libwebp-tools \
    && cwebp -version \
    && addgroup -S -g 10001 emploigo \
    && adduser -S -D -H -u 10001 -G emploigo -h /nonexistent -s /sbin/nologin emploigo \
    && install -d -m 0755 /var/lib/emploi-go/establishment-images
WORKDIR /app
COPY --link --from=build /build/extracted/dependencies/ ./
COPY --link --from=build /build/extracted/spring-boot-loader/ ./
COPY --link --from=build /build/extracted/snapshot-dependencies/ ./
COPY --link --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]

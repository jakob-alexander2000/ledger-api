FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system ledger && useradd --system --gid ledger --uid 10001 --home-dir /app ledger \
    && chown -R ledger:ledger /app
USER ledger
COPY --from=build --chown=ledger:ledger /src/build/libs/ledger-api.jar /app/ledger-api.jar
EXPOSE 8080
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS=-Duser.timezone=UTC
ENTRYPOINT ["java", "-jar", "/app/ledger-api.jar"]

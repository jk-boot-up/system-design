# syntax=docker/dockerfile:1.7
# Build: docker build -t e-commerce/cart-service .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies -q > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/cart-service-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre AS cds
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# Training run for the class-data-sharing archive: faster start-up for autoscaled pods.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
    -Dspring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration \
    -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:1/none  -Dspring.main.web-application-type=none \
    org.springframework.boot.loader.launch.JarLauncher || true

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=cds /app ./
USER 10001
EXPOSE 8083
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $( [ -f app.jsa ] && echo -XX:SharedArchiveFile=app.jsa ) org.springframework.boot.loader.launch.JarLauncher"]

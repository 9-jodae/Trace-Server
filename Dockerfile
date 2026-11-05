FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
ARG GIT_COMMIT=unknown
ENV GIT_COMMIT=${GIT_COMMIT}
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=4 \
  CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# syntax=docker/dockerfile:1

# ---- Build stage: compile the Spring Boot jar with Maven (wrapper) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src ./src
# --mount caches Maven's repository across builds (BuildKit only)
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

# ---- Runtime stage: minimal JRE on Alpine, non-root user ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

ARG JAR_FILE=target/*.jar
COPY --from=build --chown=app:app /app/${JAR_FILE} app.jar

# JVM options can be tuned per deployment, e.g. -Xms256m -Xmx256m
ENV JAVA_OPTS=""

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8000/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Minimal runtime image for load tests. The JAR is built locally with
# `mvn -DskipTests package` before `docker compose up` (ADR-0015 base image).
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY ../target/betting-service-0.1.0-SNAPSHOT.jar /app/betting-service.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/betting-service.jar"]

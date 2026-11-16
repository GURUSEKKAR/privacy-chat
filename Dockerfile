FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend/target/backend-0.1.0-SNAPSHOT-exec.jar /app/backend.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]

FROM node:24-bookworm-slim AS web-build
WORKDIR /src/web-client
COPY web-client/package.json web-client/package-lock.json ./
RUN npm ci --ignore-scripts
COPY web-client/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS java-build
WORKDIR /src
COPY . .
COPY --from=web-build /src/web-client/dist /src/web-client/dist
RUN mvn --batch-mode -pl backend -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=java-build /src/backend/target/backend-0.1.0-SNAPSHOT-exec.jar /app/backend.jar
USER 10001:10001
EXPOSE 10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55", "-XX:InitialRAMPercentage=20", "-XX:+UseSerialGC", "-jar", "/app/backend.jar"]

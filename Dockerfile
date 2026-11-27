FROM node:24-bookworm-slim AS ui
WORKDIR /build/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS java
WORKDIR /build/server
COPY server/pom.xml .
COPY server/src ./src
COPY --from=ui /build/client/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=java /build/server/target/cab-booking-1.0.0.jar ./app.jar
USER 10001
EXPOSE 10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55", "-XX:ActiveProcessorCount=2", "-jar", "/app/app.jar"]

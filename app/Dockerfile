FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --create-home app
COPY --from=build /build/target/garage-1.0-SNAPSHOT.jar app.jar

ENV PORT=8080
EXPOSE 8080
USER app

ENTRYPOINT ["java", "-jar", "app.jar"]

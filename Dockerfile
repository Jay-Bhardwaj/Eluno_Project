# Build the Spring Boot app, then run it alongside the supplied private mock API.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/eluno-pos-1.0.0.jar /app/eluno-pos.jar
COPY starter-kit /app/starter-kit
COPY docker-start.sh /app/docker-start.sh
RUN chmod +x /app/docker-start.sh
ENV ELUNO_MOCK_URL=http://127.0.0.1:8787
ENV PORT=8080
EXPOSE 8080
CMD ["/app/docker-start.sh"]

# Backend image for Cloud Run (or any container runtime).
# Build:  docker build -t arrow-chase-api backend
# Run:    docker run -p 8080:8080 arrow-chase-api

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Resolve dependencies in their own layer so code changes don't re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src/ src/
# Tests run in CI; the image build only packages.
RUN mvn -B -q -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/app.jar app.jar
USER app
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Backend image for Cloud Run (or any container runtime).
# Build:  docker build -t arrow-chase-api backend
# Run:    docker run -p 8080:8080 arrow-chase-api

# There is no official Maven image for JDK 27, so the build uses the repo's Maven wrapper, which also pins the
# same Maven version as local builds. `sh ./mvnw` so the build doesn't depend on the file's executable bit.
FROM eclipse-temurin:27-jdk AS build
WORKDIR /src
# Resolve dependencies in their own layer so code changes don't re-download them.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN sh ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests run in CI; the image build only packages.
RUN sh ./mvnw -B -q -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:27-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/app.jar app.jar
USER app
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

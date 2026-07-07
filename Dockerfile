# syntax=docker/dockerfile:1.7
#
# Multi-stage build for backend-cheapquest. The build stage
# uses a JDK image to run Maven; the runtime stage uses a
# slim JRE image and only carries the shaded JAR. The final
# image is ~200 MB on top of eclipse-temurin:17-jre (which
# is itself ~230 MB).
#
# Build:
#   docker build -t backend-cheapquest:latest .
# Run locally:
#   docker run --rm -p 8080:8080 \
#     -e RAWG_API_KEY=... -e DEEPL_API_KEY=... \
#     -e FIREBASE_PROJECT_ID=cheapquest-database \
#     -v $PWD/firebase-credentials.json:/var/secrets/firebase/credentials.json:ro \
#     backend-cheapquest:latest

# ---------- Build stage ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Cache the dependency graph first. As long as pom.xml is
# unchanged, this layer is reused and the build skips
# downloading the world on every code change.
COPY pom.xml ./
COPY .mvn .mvn 2>/dev/null || true
COPY mvnw mvnw 2>/dev/null || true
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'if [ -x ./mvnw ]; then ./mvnw -B -ntp -DskipTests dependency:go-offline; else mvn -B -ntp -DskipTests dependency:go-offline; fi'

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'if [ -x ./mvnw ]; then ./mvnw -B -ntp -DskipTests package; else mvn -B -ntp -DskipTests package; fi'

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Non-root user so the runtime does not need root inside
# the container. UID 65532 is the standard "nonroot" UID in
# distroless and in eclipse-temurin:17-jre (since 17.0.6).
RUN groupadd --system --gid 65532 cheapquest \
 && useradd  --system --uid 65532 --gid cheapquest --no-create-home --shell /sbin/nologin cheapquest

COPY --from=build /src/target/backend-cheapquest.jar /app/app.jar

# Cloud Run will inject PORT; the JVM listens on it. We do
# not hardcode 8080 so the image is portable across
# platforms that pick a different port.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xss256k"

USER 65532
EXPOSE 8080

# The serve mode boots the HttpServer on $PORT (read by
# AppProperties.effectivePort). The single entrypoint is
# enough because the only mode that holds the container
# alive is serve; the other modes (bootstrap, hydrate,
# refresh, sections, etc.) run, write, and exit.
ENTRYPOINT ["java", "-Dapp.mode=serve", "-jar", "/app/app.jar"]

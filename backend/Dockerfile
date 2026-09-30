# ── 빌드: Gradle + JDK 25 ────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon

# ── 런타임: JRE 25 ───────────────────────────────────────────────
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

COPY --from=build /workspace/build/libs/scoophub-0.1.0.jar app.jar

EXPOSE 20010
CMD ["java", "-jar", "app.jar"]

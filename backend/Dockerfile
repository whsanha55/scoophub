# ── 빌드: Gradle + JDK 25 ────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon

# ── 런타임: JRE 25 + chromium + Xvfb ─────────────────────────────────
# Playwright 1.55 는 ubuntu 26.04 미지원 → noble(24.04) 고정
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

COPY --from=build /workspace/build/libs/scoophub-0.1.0.jar app.jar

# KAL 크롤: Akamai가 headless를 차단 → 번들 chromium + xvfb로 headful 구동
# (Google Chrome은 linux-arm64 빌드가 없어 chromium 사용)
# bootJar 는 의존성이 BOOT-INF/lib 에 있어 -cp 만으론 CLI 클래스를 못 찾음 → PropertiesLauncher 경유
RUN apt-get update && apt-get install -y --no-install-recommends xvfb xauth \
 && java -cp app.jar -Dloader.main=com.microsoft.playwright.CLI org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps chromium \
 && rm -rf /var/lib/apt/lists/*

# xvfb-run은 PID1에서 Xvfb의 SIGUSR1 readiness 신호를 놓치는 레이스로 행이 걸림.
# → Xvfb를 직접 백그라운드로 띄우고 java를 exec (결정적, 핸드셰이크 없음)
ENV DISPLAY=:99
EXPOSE 20010
CMD ["sh", "-c", "Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp & exec java -jar app.jar"]

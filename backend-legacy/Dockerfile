FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

COPY pyproject.toml .
RUN uv pip install --system --no-cache -r pyproject.toml

# KAL 크롤: Akamai가 headless를 차단 → 번들 chromium + xvfb로 headful 구동
# (Google Chrome은 linux-arm64 빌드가 없어 chromium 사용)
RUN playwright install --with-deps chromium \
 && apt-get update && apt-get install -y --no-install-recommends xvfb xauth \
 && rm -rf /var/lib/apt/lists/*

COPY app/ app/
COPY config/ config/

# xvfb-run은 PID1에서 Xvfb의 SIGUSR1 readiness 신호를 놓치는 레이스로 행이 걸림.
# → Xvfb를 직접 백그라운드로 띄우고 uvicorn을 exec (결정적, 핸드셰이크 없음)
ENV DISPLAY=:99
CMD ["sh", "-c", "Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp & exec uvicorn app.main:create_app --factory --host 0.0.0.0 --port 20010"]

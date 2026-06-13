FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

COPY pyproject.toml .
RUN uv pip install --system --no-cache -r pyproject.toml

# KAL 크롤: Akamai가 headless를 차단 → 번들 chromium + xvfb로 headful 구동
# (Google Chrome은 linux-arm64 빌드가 없어 chromium 사용)
RUN playwright install --with-deps chromium \
 && apt-get update && apt-get install -y --no-install-recommends xvfb \
 && rm -rf /var/lib/apt/lists/*

COPY app/ app/
COPY config/ config/

# xvfb-run으로 가상 디스플레이 위에서 headful Chrome 실행
CMD ["xvfb-run", "-a", "--server-args=-screen 0 1280x1024x24", \
     "uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "20010"]

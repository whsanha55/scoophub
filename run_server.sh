#!/usr/bin/env bash
set -euo pipefail

# Load .env.local if exists
if [ -f .env.local ]; then
  set -a; source .env.local; set +a
fi

uv run uvicorn app.main:create_app --factory --host 127.0.0.1 --port "${PORT:-20010}" --reload

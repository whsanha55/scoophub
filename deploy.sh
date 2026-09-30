#!/usr/bin/env bash
# 모노레포 진입점. backend → frontend 순으로 하위 디렉터리의 deploy.sh 에 위임.
set -uo pipefail
cd "$(dirname "$0")"
bash backend/deploy.sh && bash frontend/deploy.sh

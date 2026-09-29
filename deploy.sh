#!/usr/bin/env bash
# 모노레포 진입점. reins 배포 agent 가 repo 루트의 deploy.sh 를 고정 호출하므로 하위 디렉터리로 위임.
set -uo pipefail
cd "$(dirname "$0")"
bash backend/deploy.sh && bash frontend/deploy.sh

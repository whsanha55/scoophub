# TODO

## 배포 — Kotlin 백엔드로 운영 전환

서버에는 아직 legacy(Python) 컨테이너가 떠 있다. 레포에서 `backend-legacy/` 는 제거됐으므로 legacy 재배포는 불가 — 아래 순서로 Kotlin 백엔드로 교체한다.

- [ ] 운영 `JWT_SECRET` 길이 확인 — 32바이트 이상이어야 기동됨(Nimbus HS256). 짧으면 교체(기존 로그인 1회 만료될 뿐 영향 없음)
- [ ] 서버 백엔드 `.env` 를 `backend/.env` 로 이동 (compose `env_file: .env`)
- [ ] reins 보드 배포 agent 의 호출 경로를 `backend/deploy.sh`, `frontend/deploy.sh` 로 변경
  - agent 가 루트 `deploy.sh` 를 고정 호출한다면 하위 디렉터리로 위임하는 루트 `deploy.sh` 추가
- [ ] 운영 DB 로 staging 기동 → Flyway validate 통과 확인 (V1~V20 체크섬 동일해야 함)
- [ ] 교체 배포 (`backend/deploy.sh`) — compose 프로젝트명 `scoophub`·서비스 `app` 동일하므로 `scoophub-app-1` 컨테이너명 유지
  - legacy compose 의 flyway 컨테이너가 orphan 으로 남으면 `docker compose up -d --remove-orphans` 로 정리
- [ ] kal_bonus: Playwright chromium + Xvfb 가 컨테이너에서 정상 동작하는지 확인
- [ ] stock watchlist_group 운영 검증 (DB 통합 테스트 대신)

<!-- convention:start -->
## Convention

코드를 작성하거나 리뷰하기 전에 `docs/convention/`에서 관련 문서와 `LOCAL.md`를 읽고 따른다. `LOCAL.md`가 우선한다.
`docs/convention/`의 파일은 `LOCAL.md`를 제외하고 직접 수정하지 않는다. 갱신은 `/convention-sync`로만 한다.
<!-- convention:end -->

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues (whsanha55/scoophub) via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the default five labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one root `CONTEXT.md` plus `docs/adr/`. See `docs/agents/domain.md`.

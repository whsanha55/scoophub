# Conventions

개인 프로젝트에 적용하는 코드 컨벤션이다.

## 구성

```text
conventions/
├── common/
│   └── git-pr.md          # 티켓, 브랜치, 커밋, push, PR (모든 프로젝트)
├── backend/
│   ├── common/
│   │   ├── api.md         # URL, 인증, 응답, 에러, Request ID, Swagger
│   │   └── test.md        # E2E 테스트
│   ├── kotlin/
│   │   ├── kotlin.md      # 언어 스타일, null, 금액, 시간, 로깅
│   │   ├── spring.md      # 패키지, 레이어, Entity, 트랜잭션, 외부 연동
│   │   ├── test.md        # 테스트
│   │   └── tooling/       # ktlint, detekt, Gradle 설정
│   └── go/
│       ├── go.md          # 이름, 에러, 인터페이스, 동시성, context
│       └── test.md        # 테스트
└── frontend/
    ├── common/            # 디자인 시스템, API 연동
    └── typescript/        # TypeScript + React, 테스트, tooling
```

이 폴더는 개인 스킬 저장소 `whsanha55/claude-code-skills`(private)의 `conventions/`에 있다. 동기화 스킬은 같은 저장소의 `skills/convention-sync/`에 있다.

언어를 추가할 때는 스택 폴더 아래에 언어 폴더를 만든다 (예: `backend/java/`). 스택 안에서 언어와 무관한 규칙은 `{stack}/common/`에, 모든 스택에 적용되는 규칙은 최상위 `common/`에 둔다.

## 우선순위

1. 프로젝트의 `docs/convention/LOCAL.md` (프로젝트별 예외)
2. 이 컨벤션 문서
3. 언어, 프레임워크 공식 컨벤션

포맷 규칙은 문서보다 `tooling/`의 자동화 설정을 기준으로 한다.

## 규칙 예외

규칙을 따르지 않을 때는 이유를 코드 주석이나 커밋 메시지에 남긴다. 같은 예외가 반복되면 규칙을 고친다.

## 프로젝트에 적용하기

Claude Code 스킬 `convention-sync`를 쓴다.

```bash
# 최초 1회: 스킬 설치 (gh 인증 필요)
gh repo clone whsanha55/claude-code-skills ~/temp/personal/claude-code-skills
ln -s ~/temp/personal/claude-code-skills/skills/convention-sync ~/.claude/skills/convention-sync
```

새 컴퓨터에서는 아래 설정도 최초 1회 해 둔다. 커밋·PR에 도구 서명과 작업 표시가 남지 않게 하는 설정이다(`common/git-pr.md` 2절, 5절).

- Claude Code 전역 설정 `~/.claude/settings.json`에 서명 자동 첨부 끄기:

  ```json
  { "attribution": { "commit": "", "pr": "", "sessionUrl": false } }
  ```

- 플러그인 설정(예: ponytail 자동 활성화 끄기)은 저장소 루트 `plugins/configure.sh`에 있다. `jsync-skill`의 `plugin pull`로 복원하면 함께 적용된다.

`/convention-sync`는 동기화할 때마다 두 설정을 확인하고, 어긋나 있으면 고칠지 묻는다.

프로젝트에서 `/convention-sync`를 실행하면 다음을 한다.

- 프로젝트 종류(백엔드/프론트엔드, 언어)에 맞는 문서를 `docs/convention/`에 복사한다.
- README와 CLAUDE.md에 컨벤션 안내를 넣는다.
- 다시 실행하면 원본의 최신 커밋과 비교해 바뀐 내용을 갱신한다.

`docs/convention/`의 파일은 직접 수정하지 않는다. 프로젝트 예외는 `docs/convention/LOCAL.md`에 적는다.

## 컨벤션 수정하기

`conventions/` 변경은 `main`에 직접 push하지 않는다. PR로 올리고 검토 후 병합한다.

- 다른 프로젝트에서 작업하다 수정이 필요하면: `/convention-sync propose`
- 이 저장소에서 직접 수정할 때: `docs/{설명}` 브랜치 → PR → 병합

병합 후 각 프로젝트에서 `/convention-sync`를 실행하면 반영된다.

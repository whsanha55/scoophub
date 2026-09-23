// 인증/프록시 공용 상수. proxy.ts(Next 16)와 route handler가 함께 사용.
// server-only 미사용 — proxy(node runtime)가 import해야 하므로.

export const API_URL = process.env.API_URL || "http://localhost:20010";

// 백엔드가 callback에서 심는 JWT 쿠키명. proxy가 이 쿠키를 읽어
// Authorization: Bearer 로 변환해 백엔드 API에 주입.
export const AUTH_COOKIE_NAME =
  process.env.AUTH_COOKIE_NAME || "access_token";

// 인증 없이 접근 가능한 페이지 경로.
// /auth/callback: 백엔드 OAuth callback이 ?token= 으로 redirect — 아직 access_token 쿠키 심기 전.
export const PUBLIC_PAGES = ["/login", "/forbidden", "/auth/callback"];

// 프론트 route handler가 소유한 /api 경로 — proxy rewrite 대상에서 제외.
// (proxy는 filesystem route보다 먼저 실행되므로, 제외하지 않으면 백엔드로 rewrite되어 도달 불가)
export const API_REWRITE_EXCLUDE = ["/api/auth/logout"];

// 백엔드로 rewrite는 하되 Authorization 주입은 제외할 경로.
// (OAuth state/JWT 발급 자체를 백엔드가 처리)
export const API_TOKEN_EXCLUDE = ["/api/auth/login", "/api/auth/callback"];

// cookie 헤더를 그대로 백엔드로 통과시킬 /api 경로.
// (callback은 백엔드가 oauth_state 쿠키로 state 검증 — proxy의 cookie delete 제외)
export const API_COOKIE_PASSTHROUGH = ["/api/auth/callback"];

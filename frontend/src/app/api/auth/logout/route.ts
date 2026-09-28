import { AUTH_COOKIE_NAME } from "@/shared/lib/auth-config";

// 백엔드 logout 엔드포인트가 없으므로 프론트에서 access_token 쿠키를 만료시킨다.
// proxy가 /api/auth/logout 을 rewrite 제외(API_REWRITE_EXCLUDE)하므로 이 라우트로 도달한다.
export async function GET() {
  // 상대 경로 Location — 운영 reverse proxy 뒤에서 req.url host가
  // 내부 bind(0.0.0.0:20020)로 잡혀 절대 URL 사용 시 잘못된 host로 redirect됨.
  return new Response(null, {
    status: 302,
    headers: {
      Location: "/",
      "Set-Cookie": `${AUTH_COOKIE_NAME}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax`,
    },
  });
}

import { AUTH_COOKIE_NAME } from "@/shared/lib/auth-config";

// 백엔드 OAuth callback이 ?token=<JWT> 로 redirect해 온다.
// 이를 HttpOnly 쿠키로 심고 홈으로. proxy가 access_token 쿠키를 읽어
// 백엔드 API에 Authorization: Bearer 로 주입.
// PUBLIC_PAGES 에 등록되어 있어 token 쿠키 없는 상태로도 도달 가능.
export async function GET(req: Request) {
  const url = new URL(req.url);
  const token = url.searchParams.get("token");

  if (!token) {
    // 상대 경로 Location — 운영 reverse proxy 뒤에서 req.url host가
    // 내부 bind(0.0.0.0:20020)로 잡혀 절대 URL 사용 시 잘못된 host로 redirect됨.
    return new Response(null, {
      status: 302,
      headers: { Location: "/login" },
    });
  }

  const secure =
    url.protocol === "https:" ||
    req.headers.get("x-forwarded-proto") === "https";
  const cookie = `${AUTH_COOKIE_NAME}=${token}; Path=/; HttpOnly; SameSite=Lax${secure ? "; Secure" : ""}`;

  return new Response(null, {
    status: 302,
    headers: {
      Location: "/",
      "Set-Cookie": cookie,
    },
  });
}

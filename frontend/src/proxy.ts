import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import {
  API_URL,
  AUTH_COOKIE_NAME,
  API_REWRITE_EXCLUDE,
  API_TOKEN_EXCLUDE,
  API_COOKIE_PASSTHROUGH,
} from "@/shared/lib/auth-config";

// Next 16: middleware.ts → proxy.ts. 인증 가드 + /api/* 프록시(Bearer 주입) 통합.
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const token = request.cookies.get(AUTH_COOKIE_NAME)?.value;

  // 1. /api/* → 백엔드 rewrite + 인증 헤더 주입
  if (pathname.startsWith("/api/")) {
    // 프론트 route handler 소유 경로는 rewrite하지 않고 filesystem route로 통과
    if (API_REWRITE_EXCLUDE.includes(pathname)) {
      return NextResponse.next();
    }

    const headers = new Headers(request.headers);
    // callback은 백엔드가 oauth_state 쿠키로 state 검증하므로 cookie 통과.
    // 그 외 /api/*는 원본 쿠키 백엔드 유출 방지를 위해 삭제.
    if (!API_COOKIE_PASSTHROUGH.includes(pathname)) {
      headers.delete("cookie");
    }
    if (!API_TOKEN_EXCLUDE.includes(pathname) && token) {
      headers.set("Authorization", `Bearer ${token}`);
    }

    const backendUrl = new URL(`${pathname}${search}`, API_URL);
    return NextResponse.rewrite(backendUrl, { request: { headers } });
  }

  // 2. 페이지 인증 가드
  // 운영 reverse proxy 뒤에서 nextUrl host가 내부 bind(0.0.0.0:20020)로
  // 잡히므로, X-Forwarded-* 헤더로 공개 origin을 복원해 redirect.
  const redirectTo = (to: string) => {
    const url = request.nextUrl.clone();
    url.pathname = to;
    const fwdHost = request.headers.get("x-forwarded-host");
    if (fwdHost) {
      // host setter는 입력에 포트가 없으면 기존 포트(20020)를 안 지움.
      // hostname + port를 따로 지정해야 내부 포트가 누출되지 않음.
      const [host, port] = fwdHost.split(":");
      url.hostname = host;
      url.port = port ?? "";
      const fwdProto = request.headers.get("x-forwarded-proto");
      if (fwdProto) url.protocol = `${fwdProto}:`;
    }
    return NextResponse.redirect(url);
  };

  // 비로그인도 (app) 페이지 조회 허용. mutation UI는 클라이언트에서
  // is_super 상태로 게이트 (useAuth 훅). /api/* GET은 백엔드가 공개.
  // 이미 로그인한 사용자가 /login 접근 시 홈으로
  if (token && pathname === "/login") {
    return redirectTo("/");
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\.png$).*)"],
};

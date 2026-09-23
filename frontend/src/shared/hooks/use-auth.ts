"use client";

import { useEffect, useState } from "react";

// 클라이언트 인증 상태. 비로그인 시 user=null.
// mutation UI(is_super 게이트)와 user-menu가 함께 사용.
export type AuthUser = {
  email?: string;
  name?: string;
  is_super?: boolean;
};

export type AuthState = {
  user: AuthUser | null;
  loading: boolean;
};

// 동시 마운트 dedupe만 수행. 결과를 영구 캐시하면 비로그인→로그인 전환 시
// stale(null)이 남아 is_super이 갱신되지 않으므로, 완료 후 inflight만 해제.
let inflight: Promise<AuthUser | null> | null = null;

function fetchMe(): Promise<AuthUser | null> {
  if (inflight) return inflight;
  inflight = fetch("/api/auth/me")
    .then(async (res) => {
      if (res.ok) {
        // /api/auth/me는 ApiResponse({success, data})로 래핑. data 언랩.
        const body = await res.json();
        return (body?.data ?? null) as AuthUser | null;
      }
      if (res.status === 403) return { is_super: false };
      // 401 등은 비로그인.
      return null;
    })
    .catch(() => null)
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

// /api/auth/me (백엔드 소유, proxy rewrite로 도달) 조회.
// 200 → 로그인 정보, 401 → 비로그인(null), 403 → 로그인은 했으나 비-super.
// 백엔드 미가동 등 네트워크 에러는 비로그인으로 간주해 UI가 부드럽게 동작.
export function useAuth(): AuthState {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    fetchMe()
      .then((u) => {
        if (active) setUser(u);
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, []);

  return { user, loading };
}

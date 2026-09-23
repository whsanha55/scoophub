"use client";

import { useEffect, useState } from "react";
import { Loader2, LogOut, LogIn } from "lucide-react";
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar";

type Me = {
  email?: string;
  name?: string;
  is_super?: boolean;
};

export function UserMenu() {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    fetch("/api/auth/me")
      .then(async (res) => {
        if (!active) return;
        if (res.status === 401) {
          // 비로그인이거나 무효 토큰. access_token은 HttpOnly라
          // document.cookie로 존재 여부를 알 수 없어 자동 정리 불가.
          // user=null(비로그인)로 두어 Google 로그인 진입점 노출.
          return;
        }
        if (res.ok) {
          const body = await res.json();
          // ApiResponse({success, data}) 래핑 — data 언랩.
          setMe(body?.data ?? body);
        } else if (res.status === 403) {
          // 로그인은 됐으나 super 아님. 본문에 사용자 정보가 있으면 사용,
          // 없어도 "로그인됨"으로 식별해 Google 로그인 진입점이 잘못 노출되지 않게.
          try {
            const body = await res.json();
            setMe({ ...body, is_super: false });
          } catch {
            setMe({ is_super: false });
          }
        }
      })
      .catch(() => {
        // 백엔드 미가동 등 — 무시(다음 렌더/네비게이션 시 재시도)
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, []);

  if (loading) {
    return (
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuButton disabled>
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>불러오는 중…</span>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
    );
  }

  // 비로그인 — Google 로그인 진입점 노출.
  if (!me) {
    return (
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuButton
            render={<a href="/api/auth/login" />}
            className="cursor-pointer"
          >
            <LogIn className="h-4 w-4" />
            <span>Google로 로그인</span>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
    );
  }

  const label = me.name || me.email || "사용자";

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <SidebarMenuButton
          onClick={() => {
            if (window.confirm("로그아웃하시겠어요?")) {
              window.location.href = "/api/auth/logout";
            }
          }}
          className="cursor-pointer"
          title={me.email}
        >
          <LogOut className="h-4 w-4" />
          <span className="truncate">{label}</span>
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}

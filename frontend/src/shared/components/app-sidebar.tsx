"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Newspaper,
  TrendingUp,
  Cloud,
  Flame,
  Wrench,
  FileText,
  Rss,
  Zap,
  Rocket,
  Play,
  Mail,
  Plane,
  Telescope,
  ChevronRight,
} from "lucide-react";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  SidebarProvider,
  SidebarInset,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { ThemeToggle } from "./theme-toggle";
import { UserMenu } from "./user-menu";
import { useAuth } from "@/shared/hooks/use-auth";

// 기술 트렌드 소스 — 테마 동일, 그룹으로 묶음.
const techTrendItems = [
  { title: "GitHub", href: "/github", icon: Flame },
  { title: "arXiv", href: "/arxiv", icon: FileText },
  { title: "Dev.to", href: "/devto", icon: Rss },
  { title: "Hacker News", href: "/hacker-news", icon: Zap },
  { title: "Product Hunt", href: "/product-hunt", icon: Rocket },
  { title: "YouTube", href: "/youtube", icon: Play },
  { title: "뉴스레터", href: "/tech-newsletter", icon: Mail },
];

const navItems = [
  { title: "뉴스", href: "/news", icon: Newspaper },
  { title: "주식", href: "/stock", icon: TrendingUp },
  { title: "대한항공 마일리지 좌석", href: "/kal-bonus", icon: Plane },
  { title: "날씨", href: "/weather", icon: Cloud },
  { title: "시스템 관리", href: "/system", icon: Wrench },
];

function AppSidebar() {
  const pathname = usePathname();
  // 비로그인(user=null)은 '시스템 관리'만 숨김. 나머지 항목은 노출.
  const { user } = useAuth();
  // 현재 경로가 그룹 하위면 항상 펼침. 그 외는 사용자 토글 존중.
  const isGroupActive = techTrendItems.some((item) =>
    pathname.startsWith(item.href),
  );
  const [userOpen, setUserOpen] = useState(false);
  const groupOpen = isGroupActive || userOpen;
  const visibleNavItems = navItems.filter(
    (item) => item.href !== "/system" || user,
  );

  return (
    <Sidebar>
      <SidebarHeader className="border-b border-border px-4 py-3">
        <Link href="/news" className="flex items-center gap-2 cursor-pointer">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-primary-foreground font-bold text-sm">
            S
          </div>
          <span className="text-lg font-semibold">ScoopHub</span>
        </Link>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarMenu>
            {/* 기술 트렌드 — 접기/펼치기 서브 네비 */}
            <SidebarMenuItem>
              <SidebarMenuButton
                onClick={() => setUserOpen((o) => !o)}
                isActive={isGroupActive}
                aria-expanded={groupOpen}
                className="cursor-pointer transition-colors duration-200"
              >
                <Telescope className="h-4 w-4" />
                <span>기술 트렌드</span>
                <ChevronRight
                  className={cn(
                    "ml-auto h-4 w-4 transition-transform duration-200",
                    groupOpen && "rotate-90",
                  )}
                />
              </SidebarMenuButton>
              {groupOpen && (
                <SidebarMenuSub>
                  {techTrendItems.map((item) => (
                    <SidebarMenuSubItem key={item.href}>
                      <SidebarMenuSubButton
                        render={<Link href={item.href} />}
                        isActive={pathname.startsWith(item.href)}
                        className="cursor-pointer transition-colors duration-200"
                      >
                        <item.icon className="h-4 w-4" />
                        <span>{item.title}</span>
                      </SidebarMenuSubButton>
                    </SidebarMenuSubItem>
                  ))}
                </SidebarMenuSub>
              )}
            </SidebarMenuItem>

            {visibleNavItems.map((item) => (
              <SidebarMenuItem key={item.href}>
                <SidebarMenuButton
                  render={<Link href={item.href} />}
                  isActive={pathname.startsWith(item.href)}
                  className="cursor-pointer transition-colors duration-200"
                >
                  <item.icon className="h-4 w-4" />
                  <span>{item.title}</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            ))}
          </SidebarMenu>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter>
        <UserMenu />
      </SidebarFooter>
    </Sidebar>
  );
}

function Header() {
  return (
    <header className="flex h-14 items-center gap-3 border-b border-border bg-background px-4">
      <SidebarTrigger className="cursor-pointer transition-colors duration-200" />
      <Separator orientation="vertical" className="h-6" />
      <div className="flex-1" />
      <ThemeToggle />
    </header>
  );
}

export function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        <Header />
        <main className="flex-1 overflow-auto p-4 md:p-6">{children}</main>
      </SidebarInset>
    </SidebarProvider>
  );
}

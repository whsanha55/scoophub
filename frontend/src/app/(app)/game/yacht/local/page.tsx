import Link from "next/link";
import type { Metadata } from "next";
import { SameScreenYachtGame } from "@/domains/game/yacht/components/same-screen-game";

export const metadata: Metadata = {
  title: "야추다이스 같은 화면 대국 — ScoopHub",
};

export default function YachtPage() {
  return (
    <div className="space-y-4">
      <Link href="/game/yacht" className="text-sm text-muted-foreground hover:text-foreground">
        ← 야추다이스
      </Link>
      <h1 className="text-2xl font-bold">야추다이스 · 같은 화면 대국</h1>
      <SameScreenYachtGame />
    </div>
  );
}

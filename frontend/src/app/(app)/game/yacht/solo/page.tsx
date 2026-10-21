import Link from "next/link";
import type { Metadata } from "next";
import { SoloYachtGame } from "@/domains/game/yacht/components/solo-game";

export const metadata: Metadata = {
  title: "야추다이스 혼자 하기 — ScoopHub",
};

export default function YachtSoloPage() {
  return (
    <div className="space-y-4">
      <Link href="/game/yacht" className="text-sm text-muted-foreground hover:text-foreground">
        ← 야추다이스
      </Link>
      <h1 className="text-2xl font-bold">야추다이스 · 혼자 하기</h1>
      <SoloYachtGame />
    </div>
  );
}

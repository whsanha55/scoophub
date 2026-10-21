import Link from "next/link";
import type { Metadata } from "next";
import { Game2048 } from "@/domains/game/2048/components/game-2048";

export const metadata: Metadata = {
  title: "2048 — ScoopHub",
};

export default function Game2048Page() {
  return (
    <div className="space-y-4">
      <Link href="/game" className="text-sm text-muted-foreground hover:text-foreground">
        ← 게임 목록
      </Link>
      <h1 className="text-2xl font-bold">2048</h1>
      <Game2048 />
    </div>
  );
}

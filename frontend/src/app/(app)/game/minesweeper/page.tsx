import Link from "next/link";
import type { Metadata } from "next";
import { MinesweeperGame } from "@/domains/game/minesweeper/components/minesweeper-game";

export const metadata: Metadata = {
  title: "지뢰찾기 — ScoopHub",
};

export default function MinesweeperPage() {
  return (
    <div className="space-y-4">
      <Link href="/game" className="text-sm text-muted-foreground hover:text-foreground">
        ← 게임 목록
      </Link>
      <h1 className="text-2xl font-bold">지뢰찾기</h1>
      <MinesweeperGame />
    </div>
  );
}

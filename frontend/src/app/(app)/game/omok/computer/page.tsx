import Link from "next/link";
import type { Metadata } from "next";
import { ComputerOmokGame } from "@/domains/game/omok/components/computer-game";

export const metadata: Metadata = {
  title: "오목 컴퓨터 대국 — ScoopHub",
};

export default function OmokComputerPage() {
  return (
    <div className="space-y-4">
      <Link href="/game/omok" className="text-sm text-muted-foreground hover:text-foreground">
        ← 오목
      </Link>
      <h1 className="text-2xl font-bold">오목 · 컴퓨터 대국</h1>
      <ComputerOmokGame />
    </div>
  );
}

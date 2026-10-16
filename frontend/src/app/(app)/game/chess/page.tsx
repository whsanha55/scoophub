import type { Metadata } from "next";
import { SameScreenChessGame } from "@/domains/game/chess/components/same-screen-game";

export const metadata: Metadata = {
  title: "체스 같은 화면 대국 — ScoopHub",
};

export default function ChessPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">체스 · 같은 화면 대국</h1>
      <SameScreenChessGame />
    </div>
  );
}

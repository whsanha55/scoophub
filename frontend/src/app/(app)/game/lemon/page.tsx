import type { Metadata } from "next";
import { LemonGame } from "@/domains/game/components/lemon-game";

export const metadata: Metadata = {
  title: "레몬게임 — ScoopHub",
};

export default function LemonGamePage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">레몬게임</h1>
      <LemonGame />
    </div>
  );
}

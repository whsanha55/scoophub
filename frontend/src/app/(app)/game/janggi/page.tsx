import type { Metadata } from "next";
import { SameScreenJanggiGame } from "@/domains/game/janggi/components/same-screen-game";

export const metadata: Metadata = {
  title: "장기 같은 화면 대국 — ScoopHub",
};

export default function JanggiPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">장기 · 같은 화면 대국</h1>
      <SameScreenJanggiGame />
    </div>
  );
}

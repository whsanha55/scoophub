import type { Metadata } from "next";
import { InviteChessGame } from "@/domains/game/chess/components/invite-game";

export const metadata: Metadata = {
  title: "체스 초대 대국 — ScoopHub",
};

export default async function RoomPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">체스 · 초대 대국</h1>
      <InviteChessGame code={code} />
    </div>
  );
}

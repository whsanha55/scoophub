import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { InviteChessGame } from "@/domains/game/chess/components/invite-game";
import { InviteJanggiGame } from "@/domains/game/janggi/components/invite-game";

const GAME_LABELS: Record<string, string> = { chess: "체스", janggi: "장기" };
const INVITE_GAMES = { chess: InviteChessGame, janggi: InviteJanggiGame } as const;

type Params = Promise<{ gameId: string; code: string }>;

export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const { gameId } = await params;
  return { title: `${GAME_LABELS[gameId] ?? "게임"} 초대 대국 — ScoopHub` };
}

export default async function RoomPage({ params }: { params: Params }) {
  const { gameId, code } = await params;
  const label = GAME_LABELS[gameId];
  const InviteGame = INVITE_GAMES[gameId as keyof typeof INVITE_GAMES];
  if (!label || !InviteGame) notFound();
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{label} · 초대 대국</h1>
      <InviteGame gameId={gameId} code={code} />
    </div>
  );
}

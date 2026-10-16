import Link from "next/link";
import { CircleDot } from "lucide-react";

const games = [
  {
    title: "레몬게임",
    description: "합이 10이 되도록 레몬을 골라 지우는 시간제한 게임",
    href: "/game/lemon",
    emoji: "🍋",
  },
  {
    title: "체스 · 같은 화면 대국",
    description: "한 기기에서 둘이 번갈아 두는 체스 대국",
    href: "/game/chess",
    emoji: "♟️",
  },
  {
    title: "장기 · 같은 화면 대국",
    description: "한 기기에서 둘이 번갈아 두는 장기 대국",
    href: "/game/janggi",
    emoji: "🎴",
  },
  {
    title: "체스 · 초대 대국 만들기",
    description: "초대코드로 서로 다른 기기에서 두는 체스 대국",
    href: "/game/room/new",
    emoji: "✉️",
  },
  {
    title: "초대코드로 입장",
    description: "받은 6자리 초대코드로 방에 들어간다",
    href: "/game/join",
    emoji: "🔑",
  },
  {
    title: "기보 다시보기",
    description: "PGN 파일을 불러오거나 공유 링크로 대국을 다시 본다",
    href: "/game/review",
    emoji: "📖",
  },
];

export default function GamePage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">게임</h1>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {games.map((game) => (
          <Link
            key={game.href}
            href={game.href}
            className="group flex flex-col gap-2 rounded-xl border bg-card p-4 transition-colors hover:bg-accent"
          >
            <div className="flex items-center gap-2">
              <span className="text-2xl" aria-hidden>
                {game.emoji}
              </span>
              <span className="font-semibold">{game.title}</span>
              <CircleDot className="ml-auto h-4 w-4 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
            </div>
            <p className="text-sm text-muted-foreground">{game.description}</p>
          </Link>
        ))}
      </div>
    </div>
  );
}

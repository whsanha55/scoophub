import Link from "next/link";
import type { ReactNode } from "react";
import { CircleDot } from "lucide-react";
import { JanggiIcon } from "@/domains/game/components/game-mode-picker";

interface GameCard {
  title: string;
  description: string;
  href: string;
  icon: ReactNode;
}

const soloGames: GameCard[] = [
  {
    title: "레몬게임",
    description: "합이 10이 되도록 레몬을 골라 지우는 시간제한 게임",
    href: "/game/lemon",
    icon: "🍋",
  },
  {
    title: "2048",
    description: "같은 숫자 타일을 밀어 합쳐 2048을 만드는 게임",
    href: "/game/2048",
    icon: "🔢",
  },
  {
    title: "지뢰찾기",
    description: "숫자 단서로 지뢰 없는 칸을 모두 여는 게임",
    href: "/game/minesweeper",
    icon: "💣",
  },
];

const versusGames: GameCard[] = [
  {
    title: "체스",
    description: "같은 화면에서 둘이, 또는 초대해서 따로 두는 체스",
    href: "/game/chess",
    icon: "♟️",
  },
  {
    title: "장기",
    description: "같은 화면에서 둘이, 또는 초대해서 따로 두는 장기",
    href: "/game/janggi",
    icon: <JanggiIcon />,
  },
  {
    title: "오목",
    description: "같은 화면에서 둘이, 또는 초대해서 따로 두는 금수 규칙 오목. 컴퓨터와도 둔다",
    href: "/game/omok",
    icon: "⚫",
  },
  {
    title: "야추다이스",
    description: "주사위 5개로 점수표를 채우는 주사위 게임. 혼자 점수 도전도 된다",
    href: "/game/yacht",
    icon: "🎲",
  },
];

function CardGrid({ cards }: { cards: GameCard[] }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {cards.map((card) => (
        <Link
          key={card.href}
          href={card.href}
          className="group flex flex-col gap-2 rounded-xl border bg-card p-4 transition-colors hover:bg-accent"
        >
          <div className="flex items-center gap-2">
            <span className="text-2xl" aria-hidden>
              {card.icon}
            </span>
            <span className="font-semibold">{card.title}</span>
            <CircleDot className="ml-auto h-4 w-4 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
          </div>
          <p className="text-sm text-muted-foreground">{card.description}</p>
        </Link>
      ))}
    </div>
  );
}

export default function GamePage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">게임</h1>
      <section className="space-y-3">
        <h2 className="text-lg font-semibold">혼자 하기</h2>
        <CardGrid cards={soloGames} />
      </section>
      <section className="space-y-3">
        <h2 className="text-lg font-semibold">둘이 하기</h2>
        <CardGrid cards={versusGames} />
      </section>
    </div>
  );
}

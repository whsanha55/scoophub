import type { Metadata } from "next";
import { GameModePicker } from "@/domains/game/components/game-mode-picker";

export const metadata: Metadata = {
  title: "오목 — ScoopHub",
};

export default function OmokModePage() {
  return (
    <GameModePicker
      gameId="omok"
      title="오목"
      modes={[
        {
          title: "같은 화면에서 둘이",
          description: "한 기기에서 둘이 번갈아 둔다",
          href: "/game/omok/local",
          icon: "📱",
        },
        {
          title: "방 만들고 초대하기",
          description: "초대코드를 보내 서로 다른 기기에서 둔다",
          href: "/game/room/new?game=omok",
          icon: "✉️",
        },
      ]}
    />
  );
}

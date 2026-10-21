import type { Metadata } from "next";
import { GameModePicker } from "@/domains/game/components/game-mode-picker";

export const metadata: Metadata = {
  title: "야추다이스 — ScoopHub",
};

export default function YachtModePage() {
  return (
    <GameModePicker
      gameId="yacht"
      title="야추다이스"
      modes={[
        {
          title: "같은 화면에서 둘이",
          description: "한 기기에서 둘이 번갈아 굴린다",
          href: "/game/yacht/local",
          icon: "📱",
        },
        {
          title: "방 만들고 초대하기",
          description: "초대코드를 보내 서로 다른 기기에서 겨룬다",
          href: "/game/room/new?game=yacht",
          icon: "✉️",
        },
        {
          title: "혼자 하기",
          description: "점수표 하나를 채워 최고 총점에 도전한다",
          href: "/game/yacht/solo",
          icon: "🎯",
        },
      ]}
    />
  );
}

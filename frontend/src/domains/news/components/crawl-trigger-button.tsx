"use client";

import { Button } from "@/components/ui/button";
import { RefreshCw } from "lucide-react";
import { useAuth } from "@/shared/hooks/use-auth";

interface CrawlTriggerButtonProps {
  onClick: () => void;
  loading: boolean;
  label?: string;
}

export function CrawlTriggerButton({
  onClick,
  loading,
  label = "수집 실행",
}: CrawlTriggerButtonProps) {
  const { user, loading: authLoading } = useAuth();

  // 비로그인·비-super는 mutation 버튼 미노출.
  if (authLoading || !user?.is_super) return null;

  return (
    <Button
      onClick={onClick}
      disabled={loading}
      className="cursor-pointer transition-colors duration-200"
    >
      <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} />
      {loading ? "실행 중..." : label}
    </Button>
  );
}

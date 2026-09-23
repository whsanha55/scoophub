import { Button } from "@/components/ui/button";

export default function ForbiddenPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="flex w-full max-w-sm flex-col items-center gap-4 rounded-xl border border-border bg-card p-8 text-center shadow-sm">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-destructive/10 text-destructive font-bold">
          403
        </div>
        <div className="space-y-1">
          <h1 className="text-lg font-semibold">접근이 거부되었습니다</h1>
          <p className="text-sm text-muted-foreground">
            허용된 이메일이 아닙니다. 관리자에게 문의하세요.
          </p>
        </div>
        <Button
          nativeButton={false}
          render={<a href="/api/auth/logout" />}
          variant="outline"
          className="w-full cursor-pointer"
        >
          다른 계정으로 로그인
        </Button>
      </div>
    </div>
  );
}

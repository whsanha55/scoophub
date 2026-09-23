import { Button } from "@/components/ui/button";

export default function LoginPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="flex w-full max-w-sm flex-col items-center gap-6 rounded-xl border border-border bg-card p-8 shadow-sm">
        <div className="flex items-center gap-2">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary font-bold text-primary-foreground">
            S
          </div>
          <span className="text-xl font-semibold">ScoopHub</span>
        </div>
        <div className="space-y-1 text-center">
          <h1 className="text-lg font-semibold">로그인</h1>
          <p className="text-sm text-muted-foreground">
            Google 계정으로 로그인하세요.
          </p>
        </div>
        <Button
          nativeButton={false}
          render={<a href="/api/auth/login" />}
          size="lg"
          className="w-full cursor-pointer"
        >
          Google로 로그인
        </Button>
      </div>
    </div>
  );
}

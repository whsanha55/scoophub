"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Send } from "lucide-react";
import { useLlmTest } from "../hooks/use-llm-test";

export function LlmTestPanel() {
  const [message, setMessage] = useState("");
  const [systemPrompt, setSystemPrompt] = useState("");
  const { response, model, loading, error, testLlm } = useLlmTest();

  const handleSubmit = async () => {
    const trimmed = message.trim();
    if (!trimmed) return;
    await testLlm(trimmed, systemPrompt || undefined);
  };

  return (
    <div className="space-y-4">
      <h2 className="text-2xl font-bold">LLM 호출 테스트</h2>

      <Card>
        <CardContent className="space-y-4 pt-6">
          <div className="space-y-2">
            <label className="text-sm font-medium">시스템 프롬프트</label>
            <Input
              placeholder="You are a helpful assistant."
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
            />
          </div>

          <div className="space-y-2">
            <label className="text-sm font-medium">메시지</label>
            <textarea
              className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
              rows={4}
              placeholder="LLM에 전송할 메시지를 입력하세요"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
            />
          </div>

          <Button onClick={handleSubmit} disabled={loading}>
            <Send className="mr-2 h-4 w-4" />
            {loading ? "테스트 중..." : "전송"}
          </Button>
        </CardContent>
      </Card>

      {response && (
        <Card>
          <CardHeader>
            <CardTitle>응답 결과</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {model && <p className="text-sm text-muted-foreground">모델: {model}</p>}
            <p className="whitespace-pre-wrap">{response}</p>
          </CardContent>
        </Card>
      )}

      {error && <p className="text-destructive">{error}</p>}
    </div>
  );
}

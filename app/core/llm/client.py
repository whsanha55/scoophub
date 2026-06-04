from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class LLMClient:
    """OpenAI Chat Completions 호환 API 호출 클라이언트."""

    def __init__(self, timeout: int = 600) -> None:
        self._client = httpx.AsyncClient(timeout=timeout)
        logger.info("LLMClient.__init__ 시작 - model=%s, timeout=%ds", settings.LLM_MODEL, timeout)

    async def chat(self, system_prompt: str, user_prompt: str) -> str:
        logger.info("LLMClient.chat 시작 - model=%s", settings.LLM_MODEL)
        payload = {
            "model": settings.LLM_MODEL,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.3,
        }
        headers: dict[str, str] = {"Content-Type": "application/json"}
        # API 키가 설정된 경우에만 Authorization 헤더 추가
        if settings.LLM_API_KEY:
            headers["Authorization"] = f"Bearer {settings.LLM_API_KEY}"

        try:
            response = await self._client.post(settings.LLM_API_URL, json=payload, headers=headers)
            response.raise_for_status()
        except httpx.TimeoutException as e:
            logger.error("LLM request timed out: %s", e)
            raise
        except httpx.HTTPStatusError as e:
            logger.error("LLM API error %s: %s", e.response.status_code, e.response.text)
            raise
        except Exception as e:
            logger.error("LLM request failed: %s", e)
            raise

        data = response.json()
        try:
            # OpenAI Chat Completions 응답 구조에서 텍스트 추출
            content = data["choices"][0]["message"]["content"]
            logger.info("LLMClient.chat 완료 - 응답 길이=%d", len(content))
            return content
        except (KeyError, IndexError) as e:
            logger.error("Unexpected LLM response structure: %s — data: %s", e, data)
            raise RuntimeError(f"Invalid LLM response structure: {e}") from e

    async def close(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> LLMClient:
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.close()

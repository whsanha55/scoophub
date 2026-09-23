"use client";

import { useEffect, useRef, useState } from "react";

// 입력값을 delay(ms) 후에 반영하는 디바운스 훅.
// 각 페이지마다 복붙되던 useRef + setTimeout(300) + cleanup 패턴 통합.
export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState<T>(value);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(null);

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setDebounced(value), delay);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [value, delay]);

  return debounced;
}

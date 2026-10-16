"use client";

import { useCallback, useSyncExternalStore } from "react";

// localStorage 값 + 같은 탭/다른 탭 갱신 구독. 게임 설정(판 회전 등)에 쓴다.
const listeners = new Set<() => void>();

function snapshot<T>(key: string, defaultValue: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? defaultValue : (JSON.parse(raw) as T);
  } catch {
    return defaultValue;
  }
}

export function useLocalValue<T>(key: string, defaultValue: T): [T, (v: T) => void] {
  const value = useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      const onStorage = () => onChange();
      window.addEventListener("storage", onStorage);
      return () => {
        listeners.delete(onChange);
        window.removeEventListener("storage", onStorage);
      };
    },
    () => snapshot(key, defaultValue),
    () => defaultValue,
  );
  const setValue = useCallback(
    (v: T) => {
      localStorage.setItem(key, JSON.stringify(v));
      listeners.forEach((l) => l());
    },
    [key],
  );
  return [value, setValue];
}

import { useCallback, useEffect, useRef, useState } from "react";

const BACKOFF_BASE = 500;
const BACKOFF_MAX = 30_000;
const BACKOFF_MULTIPLIER = 2;
const JITTER_RANGE = 0.5;
const MAX_ATTEMPTS = 15;

function getBackoffDelay(attempt: number): number {
  const exponential = Math.min(BACKOFF_BASE * Math.pow(BACKOFF_MULTIPLIER, attempt), BACKOFF_MAX);
  const jitter = exponential * JITTER_RANGE * (Math.random() * 2 - 1);
  return Math.max(0, Math.round(exponential + jitter));
}

interface UseReconnectOptions {
  reconnectFn: () => void;
  onReconnecting: (attempt: number) => void;
  onGaveUp: () => void;
}

interface UseReconnectReturn {
  scheduleReconnect: () => void;
  cancelReconnect: () => void;
  resetAttempts: () => void;
  attempt: number;
  isReconnecting: boolean;
}

export function useReconnect(options: UseReconnectOptions): UseReconnectReturn {
  const optionsRef = useRef(options);
  useEffect(() => {
    optionsRef.current = options;
  });

  const attemptRef = useRef(0);
  const timerRef = useRef<number | null>(null);
  const [isReconnecting, setIsReconnecting] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const scheduleReconnect = useCallback(() => {
    attemptRef.current += 1;
    const currentAttempt = attemptRef.current;

    if (currentAttempt > MAX_ATTEMPTS) {
      optionsRef.current.onGaveUp();
      setIsReconnecting(false);
      setAttempt(currentAttempt);
      return;
    }

    const delay = getBackoffDelay(currentAttempt - 1);
    optionsRef.current.onReconnecting(currentAttempt);
    setIsReconnecting(true);
    setAttempt(currentAttempt);

    timerRef.current = window.setTimeout(() => {
      optionsRef.current.reconnectFn();
    }, delay);
  }, []);

  const cancelReconnect = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    attemptRef.current = 0;
    setIsReconnecting(false);
    setAttempt(0);
  }, []);

  const resetAttempts = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    attemptRef.current = 0;
    setIsReconnecting(false);
    setAttempt(0);
  }, []);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  return {
    scheduleReconnect,
    cancelReconnect,
    resetAttempts,
    attempt,
    isReconnecting,
  };
}

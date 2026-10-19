import { useCallback, useEffect, useState } from "react";

/**
 * A countdown for "too many attempts, try again in N seconds". `start(seconds)` begins it;
 * `remaining` ticks down once a second and is 0 when the person may try again.
 */
export function useLockout(): { remaining: number; start: (seconds: number) => void; clear: () => void } {
  const [until, setUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (until <= Date.now()) return undefined;
    setNow(Date.now());
    const timer = setInterval(() => {
      const t = Date.now();
      setNow(t);
      if (t >= until) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [until]);

  const start = useCallback((seconds: number) => {
    const t = Date.now();
    setNow(t);
    setUntil(t + Math.max(1, Math.ceil(seconds)) * 1000);
  }, []);
  const clear = useCallback(() => setUntil(0), []);

  return { remaining: Math.max(0, Math.ceil((until - now) / 1000)), start, clear };
}

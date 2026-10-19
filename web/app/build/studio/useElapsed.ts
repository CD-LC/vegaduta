import { useEffect, useState } from "react";

export function useElapsed(started: number | null): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!started) return undefined;
    const t = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(t);
  }, [started]);
  return started ? Math.max(0, Math.round((now - started) / 1000)) : 0;
}

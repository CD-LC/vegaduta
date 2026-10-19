import { useCallback, useEffect, useRef, useState } from "react";

/** Copies text and reports which item was copied for ~1.6s (drives the check icon). */
export function useCopy(): [string | null, (id: string, text: string) => void] {
  const [copied, setCopied] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);
  const copy = useCallback((id: string, text: string) => {
    const done = () => {
      setCopied(id);
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => setCopied(null), 1600);
    };
    try {
      void navigator.clipboard?.writeText(text).then(done, () => setCopied(null));
    } catch {
      setCopied(null);
    }
  }, []);
  return [copied, copy];
}

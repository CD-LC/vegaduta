"use client";

// While a reply streams, the chat re-renders on every token. Rebuilding the artifacts registry (and
// re-splitting a half-written 500 KB artifact) at that rate costs roughly O(n^2) over the stream and
// janks the tab, so during streaming the artifacts layer works from a snapshot refreshed at most every
// `ms`. When not streaming the live value is returned directly (no lag, no extra render).

import { useEffect, useRef, useState } from "react";

export const STREAM_SNAPSHOT_MS = 200;

export function useThrottledSnapshot<T>(value: T, active: boolean, ms: number = STREAM_SNAPSHOT_MS): T {
  const [snap, setSnap] = useState<T>(value);
  const latest = useRef<T>(value);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    latest.current = value;
  }, [value]);

  useEffect(() => {
    if (!active) {
      if (timer.current !== null) {
        clearTimeout(timer.current);
        timer.current = null;
      }
      setSnap(value);
      return;
    }
    // Throttle (not debounce): one pending timer at a time, so a steady token stream still refreshes every `ms`.
    if (timer.current === null) {
      timer.current = setTimeout(() => {
        timer.current = null;
        setSnap(latest.current);
      }, ms);
    }
  }, [value, active, ms]);

  useEffect(
    () => () => {
      if (timer.current !== null) clearTimeout(timer.current);
    },
    [],
  );

  return active ? snap : value;
}

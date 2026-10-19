"use client";

// "The platform is doing something" for the rail logo. Backend calls are counted by apiActivity
// (until headers arrive); long-running work that outlives a request, such as a streamed Assistant
// reply, registers itself here by key so the logo keeps moving until it finishes.

import { useEffect, useState } from "react";
import { apiActivity } from "./api/core";

const keys = new Set<string>();
const listeners = new Set<() => void>();

/** Mark `key` busy or idle. Safe to call repeatedly with the same value. */
export function setPlatformBusy(key: string, busy: boolean): void {
  const had = keys.has(key);
  if (busy === had) return;
  if (busy) keys.add(key);
  else keys.delete(key);
  listeners.forEach((l) => l());
}

/** Register `key` as busy while `busy` is true, and release it on change or unmount. */
export function usePlatformBusyFlag(key: string, busy: boolean): void {
  useEffect(() => {
    setPlatformBusy(key, busy);
    return () => setPlatformBusy(key, false);
  }, [key, busy]);
}

/** True while any registered work or backend call is outstanding (150 ms grace so instant calls don't flicker). */
export function usePlatformBusy(): boolean {
  const [flagged, setFlagged] = useState(false);
  const [calls, setCalls] = useState(false);

  useEffect(() => {
    const sync = () => setFlagged(keys.size > 0);
    listeners.add(sync);
    sync();
    return () => {
      listeners.delete(sync);
    };
  }, []);

  useEffect(() => {
    let grace: ReturnType<typeof setTimeout> | null = null;
    const off = apiActivity.subscribe((inflight) => {
      if (inflight > 0) {
        if (!grace) grace = setTimeout(() => setCalls(true), 150);
      } else {
        if (grace) clearTimeout(grace);
        grace = null;
        setCalls(false);
      }
    });
    return () => {
      off();
      if (grace) clearTimeout(grace);
    };
  }, []);

  return flagged || calls;
}

"use client";

import { useEffect, useRef, type MutableRefObject } from "react";
import { handleCanvasKey, type CanvasShortcutActions } from "./shortcuts";

/**
 * One global key handler, registered ONCE: it calls whatever the latest render
 * put in the actions ref, so it never runs against a stale graph.
 */
export function useCanvasShortcuts(actions: CanvasShortcutActions, observingRef: MutableRefObject<boolean>) {
  const actionsRef = useRef(actions);
  useEffect(() => {
    actionsRef.current = actions;
  });
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      handleCanvasKey(e, actionsRef.current, observingRef.current);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [observingRef]);
}

"use client";

import { useCallback, useEffect, useState } from "react";

const SNAP_STORAGE_KEY = "wf-canvas-snap-to-grid";

/** Snap-to-grid preference: a per-viewer convenience (guarded localStorage). */
export function useSnapToGrid() {
  const [snapToGrid, setSnapToGrid] = useState(false);
  useEffect(() => {
    try {
      if (window.localStorage.getItem(SNAP_STORAGE_KEY) === "1") setSnapToGrid(true);
    } catch {
      /* storage unavailable: default off */
    }
  }, []);
  const toggleSnap = useCallback(() => {
    setSnapToGrid((prev) => {
      const next = !prev;
      try {
        window.localStorage.setItem(SNAP_STORAGE_KEY, next ? "1" : "0");
      } catch {
        /* storage unavailable: still works for this session */
      }
      return next;
    });
  }, []);
  return { snapToGrid, toggleSnap };
}

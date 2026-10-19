"use client";

import { useEffect, type ReactNode } from "react";

const SUFFIX = "VegaDuta";

/**
 * Builds "<Page> - VegaDuta" from the first non-empty string among
 * `candidates`, or null when none is a string. Every console route gets its
 * own title this way (WCAG 2.4.2).
 */
export function documentTitleFor(...candidates: ReactNode[]): string | null {
  for (const c of candidates) {
    if (typeof c === "string" && c.trim()) {
      const page = c.trim();
      return page === SUFFIX ? page : `${page} - ${SUFFIX}`;
    }
  }
  return null;
}

export function useDocumentTitle(...candidates: ReactNode[]): void {
  const next = documentTitleFor(...candidates);
  useEffect(() => {
    if (next) document.title = next;
  }, [next]);
}

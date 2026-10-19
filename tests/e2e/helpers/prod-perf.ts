import type { Page } from "@playwright/test";

/**
 * Core Web Vitals capture for page-load specs (SE-79).
 *
 * Reading `performance.getEntriesByType("largest-contentful-paint")` AFTER load is unreliable: Chromium only
 * keeps a bounded buffer of LCP entries and the entry is finalised on first user input, so a late read can see
 * nothing or a stale candidate. A PerformanceObserver has to be registered BEFORE the document starts loading;
 * `observeWebVitals(page)` injects it with addInitScript, so call it before `page.goto(...)`.
 *
 *   await observeWebVitals(page);
 *   await page.goto("/");
 *   const { lcpMs, cls } = await readWebVitals(page);
 *
 * The observer is re-armed on every navigation of the page (addInitScript runs per document); values describe
 * the CURRENT document only.
 */
export interface WebVitals {
  /** Render time of the largest contentful element, in ms since navigation start; null if none was reported. */
  lcpMs: number | null;
  /** The element the LCP candidate belongs to (tag + id/class), for diagnostics. */
  lcpElement: string | null;
  /** Cumulative layout shift (shifts without recent input), unitless. */
  cls: number;
}

declare global {
  interface Window {
    __vdVitals?: { lcp: number | null; lcpEl: string | null; cls: number };
  }
}

export async function observeWebVitals(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const vitals = { lcp: null as number | null, lcpEl: null as string | null, cls: 0 };
    window.__vdVitals = vitals;
    try {
      new PerformanceObserver((list) => {
        const entries = list.getEntries() as Array<PerformanceEntry & { startTime: number; element?: Element | null }>;
        const last = entries[entries.length - 1];
        if (!last) return;
        vitals.lcp = last.startTime;
        const el = last.element;
        vitals.lcpEl = el ? `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ""}` : null;
      }).observe({ type: "largest-contentful-paint", buffered: true });
    } catch {
      /* LCP not supported in this browser */
    }
    try {
      new PerformanceObserver((list) => {
        for (const e of list.getEntries() as Array<PerformanceEntry & { value: number; hadRecentInput: boolean }>) {
          if (!e.hadRecentInput) vitals.cls += e.value;
        }
      }).observe({ type: "layout-shift", buffered: true });
    } catch {
      /* layout-shift not supported */
    }
  });
}

/** Reads what observeWebVitals collected for the current document. Throws if it was not armed before navigation. */
export async function readWebVitals(page: Page): Promise<WebVitals> {
  const v = await page.evaluate(() => window.__vdVitals ?? null);
  if (!v) {
    throw new Error("readWebVitals: observeWebVitals(page) must be called BEFORE page.goto() so the observer exists");
  }
  return { lcpMs: v.lcp, lcpElement: v.lcpEl, cls: v.cls };
}

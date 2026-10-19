"use client";

// Overflow-driven topbar compacting. Lifted out of AppShell.tsx unchanged.

import { useLayoutEffect, useRef } from "react";


export function useTopbarCompact() {
  // Overflow-driven topbar compacting (2026-09-28). The right-hand cluster
  // (org chip · env pill · page guide · usage meter · bell · instrument
  // cluster · trust strip) is wider than plenty of desktop windows once a
  // real org name / token count / session countdown is in it, and
  // console.css's `header.sticky > * { min-width: 0 }` then let each
  // item shrink under its own nowrap label - which is what painted the
  // trust strip's text on top of the language switcher. A fixed px
  // breakpoint can't predict the crowding point (every piece's width is
  // data-dependent), so this measures the row's true required width -
  // children forced unshrinkable via data-measuring so header.scrollWidth
  // is the natural width, not the already-squeezed one - and steps
  // data-compact 0 -> 1 -> 2. console.css §5 turns the word labels
  // into icons at 1 and removes the optional chips entirely at 2.
  const topbarRef = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const el = topbarRef.current;
    if (!el) return undefined;
    let raf = 0;
    function fits(level: number): boolean {
      const header = topbarRef.current;
      if (!header) return true;
      header.setAttribute("data-compact", String(level));
      header.setAttribute("data-measuring", "");
      const needed = header.scrollWidth;
      header.removeAttribute("data-measuring");
      return needed <= header.clientWidth;
    }
    function fit() {
      raf = 0;
      for (let level = 0; level <= 1; level++) {
        if (fits(level)) return;
      }
      topbarRef.current?.setAttribute("data-compact", "2");
    }
    function schedule() {
      if (!raf) raf = window.requestAnimationFrame(fit);
    }
    const ro = new ResizeObserver(schedule);
    ro.observe(el);
    for (const child of Array.from(el.children)) ro.observe(child);
    // Several controls mount late (org name, usage pill and env pill all
    // resolve async): re-fit on childList changes and pick up the new
    // children so their own later resizes are observed too.
    const mo = new MutationObserver(() => {
      const header = topbarRef.current;
      if (header) for (const child of Array.from(header.children)) ro.observe(child);
      schedule();
    });
    mo.observe(el, { childList: true });
    fit();
    return () => {
      ro.disconnect();
      mo.disconnect();
      if (raf) window.cancelAnimationFrame(raf);
    };
  }, []);
  return topbarRef;
}

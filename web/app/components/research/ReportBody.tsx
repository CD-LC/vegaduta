"use client";

import { useEffect, useMemo, useState } from "react";
import RichText from "../RichText";
import { linkCitations, normalizeSourceId, sourceUrlMap } from "./citeLinks";
import { RS } from "./researchStrings";
import { groupIndexById, type ReportOutline, type SourceGroup } from "./reportStructure";
import type { ResearchSource } from "../../lib/api/types";
import { verdictTone } from "./researchRun";

const CITE_LABEL = /^S\d+$/i;

function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

function scrollToEl(el: HTMLElement | null) {
  if (!el) return;
  el.scrollIntoView({ behavior: prefersReducedMotion() ? "auto" : "smooth", block: "start" });
  el.focus({ preventScroll: true });
}

interface Hover {
  id: string;
  left: number;
  top: number;
}

/**
 * The report text, split into its own sections so each heading is a real
 * heading with an id. On top of that:
 *  - a table of contents with scrollspy (three or more sections; a sidebar on
 *    wide screens, a collapsible list on narrow ones);
 *  - citation chips ("S1") that jump to their source card on a plain click
 *    (modified clicks still open the page in a new tab) and show a small card
 *    with the source and its first quote on hover or keyboard focus.
 *
 * The chips are ordinary links produced by linkCitations and rendered by
 * RichText, so scheme checking and rel="noopener" are unchanged; this file
 * only listens for events on them (one delegated handler, no HTML injected).
 */
export default function ReportBody({
  outline,
  sources,
  groups,
  onCite,
}: {
  outline: ReportOutline;
  sources: ReadonlyArray<ResearchSource>;
  groups: ReadonlyArray<SourceGroup>;
  onCite: (id: string) => void;
}) {
  const urls = useMemo(() => sourceUrlMap(sources), [sources]);
  const indexById = useMemo(() => groupIndexById(groups), [groups]);
  const [hover, setHover] = useState<Hover | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const showToc = outline.sections.length >= 3;

  function citation(target: EventTarget | null): { id: string; anchor: HTMLAnchorElement } | null {
    const anchor = (target as HTMLElement | null)?.closest?.("a") as HTMLAnchorElement | null;
    if (!anchor) return null;
    const label = (anchor.textContent ?? "").trim();
    if (!CITE_LABEL.test(label)) return null;
    const id = normalizeSourceId(label);
    const url = urls.get(id);
    // Only a chip that really points at that source counts - a model-written
    // link that merely reads "S1" is left alone.
    if (!url || anchor.getAttribute("href") !== url || !indexById.has(id)) return null;
    return { id, anchor };
  }

  function show(target: EventTarget | null) {
    const c = citation(target);
    if (!c) return;
    const r = c.anchor.getBoundingClientRect();
    const width = 320;
    const left = Math.max(8, Math.min(r.left, window.innerWidth - width - 8));
    setHover({ id: c.id, left, top: r.bottom + 6 });
  }

  // Hide on scroll/Escape so a card never floats away from its chip.
  useEffect(() => {
    if (!hover) return undefined;
    const hide = () => setHover(null);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && hide();
    window.addEventListener("scroll", hide, true);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("scroll", hide, true);
      window.removeEventListener("keydown", onKey);
    };
  }, [hover]);

  // Scrollspy.
  useEffect(() => {
    if (!showToc || typeof IntersectionObserver === "undefined") return undefined;
    const visible = new Set<string>();
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) visible.add(e.target.id);
          else visible.delete(e.target.id);
        }
        const first = outline.sections.find((s) => visible.has(s.id));
        if (first) setActive(first.id);
      },
      { rootMargin: "-80px 0px -65% 0px" }
    );
    for (const s of outline.sections) {
      const el = document.getElementById(s.id);
      if (el) io.observe(el);
    }
    return () => io.disconnect();
  }, [outline.sections, showToc]);

  const hoverGroup = hover ? groups[indexById.get(hover.id) ?? -1] : undefined;
  const hoverQuote = hoverGroup?.quotes[0];

  const toc = showToc && (
    <ol className="space-y-1 text-sm">
      {outline.sections.map((s) => (
        <li key={s.id} className={s.level === 3 ? "ms-3" : undefined}>
          <button
            type="button"
            onClick={() => {
              setActive(s.id);
              scrollToEl(document.getElementById(s.id));
            }}
            aria-current={active === s.id ? "location" : undefined}
            className={`block w-full rounded px-2 py-1 text-start [overflow-wrap:anywhere] transition-colors hover:bg-muted focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring ${
              active === s.id ? "bg-muted font-medium text-foreground" : "text-muted-foreground"
            }`}
          >
            {s.heading}
          </button>
        </li>
      ))}
    </ol>
  );

  return (
    <div className={showToc ? "lg:grid lg:grid-cols-[minmax(0,1fr)_13rem] lg:gap-8" : undefined}>
      {showToc && (
        <details className="research-no-print mb-4 rounded-lg border border-border p-3 lg:hidden">
          <summary className="cursor-pointer text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {RS.contents}
          </summary>
          <nav aria-label={RS.contents} className="mt-2">
            {toc}
          </nav>
        </details>
      )}

      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- delegated handler for the citation <a> links inside; Enter on a focused link fires this click */}
      <div
        className="min-w-0 space-y-4"
        onClick={(e) => {
          const c = citation(e.target);
          if (!c || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
          e.preventDefault();
          setHover(null);
          onCite(c.id);
        }}
        onMouseOver={(e) => show(e.target)}
        onMouseOut={(e) => citation(e.target) && setHover(null)}
        onFocus={(e) => show(e.target)}
        onBlur={(e) => citation(e.target) && setHover(null)}
      >
        {outline.intro && <RichText text={linkCitations(outline.intro, sources)} />}
        {outline.sections.map((s) => {
          const Tag = s.level === 3 ? "h4" : "h3";
          return (
            <section key={s.id} className="space-y-2">
              <Tag
                id={s.id}
                tabIndex={-1}
                className={`scroll-mt-24 font-semibold text-foreground outline-none ${s.level === 3 ? "text-sm" : "text-base"}`}
              >
                {s.heading}
              </Tag>
              {s.body && <RichText text={linkCitations(s.body, sources)} />}
            </section>
          );
        })}
      </div>

      {showToc && (
        <nav aria-label={RS.contents} className="research-no-print hidden lg:block">
          <div className="sticky top-20 space-y-2">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{RS.contents}</p>
            {toc}
          </div>
        </nav>
      )}

      {hover && hoverGroup && (
        <div
          role="tooltip"
          style={{ left: hover.left, top: hover.top, width: 320, maxWidth: "calc(100vw - 16px)" }}
          className="pointer-events-none fixed z-50 space-y-1 rounded-lg border border-border bg-popover p-3 text-xs text-popover-foreground shadow-lg"
        >
          <p className="text-sm font-medium [overflow-wrap:anywhere]">{hoverGroup.title || hoverGroup.domain}</p>
          <p className="text-muted-foreground">{hoverGroup.domain}</p>
          {hoverQuote && (
            <p className="italic [overflow-wrap:anywhere]">
              “{hoverQuote.quote.length > 180 ? `${hoverQuote.quote.slice(0, 179)}…` : hoverQuote.quote}”
              {hoverQuote.verdict && verdictTone(hoverQuote.verdict) === "unsupported" ? " (check failed)" : ""}
            </p>
          )}
          <p className="text-muted-foreground">{RS.hoverGoToSource}</p>
        </div>
      )}
    </div>
  );
}

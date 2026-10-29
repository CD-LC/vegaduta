"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { usePathname, useRouter } from "next/navigation";
import { Activity, Cable, Gauge, Hammer, Settings2, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { useTour } from "../TourProvider";
import { Button } from "../ui/button";
import { courtIsDocked, courtRegion, visibleCourtRegions } from "./court";
import { isBillingEnabled } from "../../lib/billing-flag";
import type { CourtRegionId } from "./court";
import { queryTourTarget } from "./targets";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

// The Court (tour mode "court"): the console drawn as a courtyard plan, five
// regions around a central yard (Earned Autonomy, 2026-09-26). Hover or focus a
// region and the matching group on the REAL rail lights up beside it, with its
// one-line purpose underneath; click and it walks that region's existing tour
// steps (or, where none exist, opens its first page). "Skip to briefing" is
// always on screen - the map is a way in, never a gate.
//
// Paper-and-ink: existing tokens only. Hue is each rail group's own category
// colour (the same --cat-* AppShell sets per section), and every region also
// carries its icon and its written name, so hue is never the only cue.
//
// Copy lives in the `tour` namespace, not `brief`: this component is mounted by
// TourProvider at the root layout, so every namespace it names ships on EVERY
// route (i18n-route-namespaces.mjs), anonymous landing included. The region
// names repeat the rail group labels (nav.navGroup*) for the same reason.
//
// Below the dock breakpoint (the width at which the tour card itself becomes a
// bottom sheet) the plan is replaced by the same regions as a list in a bottom
// sheet - a courtyard plan needs room a phone does not have.

type View = {
  icon: LucideIcon;
  cat: string;
  // Building footprint on the 760x500 plan, and its door (on the yard side).
  x: number;
  y: number;
  w: number;
  h: number;
  door: { x: number; y: number; w: number; h: number };
};

const VIEW: Record<CourtRegionId, View> = {
  build: {
    icon: Hammer,
    cat: "var(--cat-assistant)",
    x: 210,
    y: 44,
    w: 340,
    h: 104,
    door: { x: 366, y: 145, w: 28, h: 6 },
  },
  connect: {
    icon: Cable,
    cat: "var(--cat-comms)",
    x: 560,
    y: 170,
    w: 160,
    h: 160,
    door: { x: 557, y: 236, w: 6, h: 28 },
  },
  config: {
    icon: Settings2,
    cat: "var(--cat-automation)",
    x: 40,
    y: 170,
    w: 160,
    h: 160,
    door: { x: 197, y: 236, w: 6, h: 28 },
  },
  monitor: {
    icon: Activity,
    cat: "var(--cat-analysis)",
    x: 210,
    y: 352,
    w: 340,
    h: 104,
    door: { x: 366, y: 349, w: 28, h: 6 },
  },
  plans: {
    icon: Gauge,
    cat: "var(--cat-creative)",
    x: 584,
    y: 364,
    w: 136,
    h: 92,
    door: { x: 581, y: 396, w: 6, h: 24 },
  },
};

const LIT_OUTLINE = "2px solid var(--primary)";

/** Lights the rail element for `railTarget` while set; restores what was there. */
function useRailLight(railTarget: string | null) {
  useEffect(() => {
    if (!railTarget) return undefined;
    const el = queryTourTarget(railTarget) as HTMLElement | null;
    if (!el) return undefined;
    const prev = { outline: el.style.outline, offset: el.style.outlineOffset };
    el.style.outline = LIT_OUTLINE;
    el.style.outlineOffset = "2px";
    return () => {
      el.style.outline = prev.outline;
      el.style.outlineOffset = prev.offset;
    };
  }, [railTarget]);
}

/** Right edge of the nav rail, so the map sits beside it instead of over it. */
function useRailEdge(active: boolean, docked: boolean) {
  const [edge, setEdge] = useState(0);
  useEffect(() => {
    if (!active || docked) return undefined;
    const read = () => {
      const rail = queryTourTarget("nav-rail");
      setEdge(rail ? Math.round(rail.getBoundingClientRect().right) : 0);
    };
    read();
    window.addEventListener("resize", read);
    return () => window.removeEventListener("resize", read);
  }, [active, docked]);
  return docked ? 0 : edge;
}

function useViewportWidth() {
  const [w, setW] = useState(() => (typeof window === "undefined" ? 0 : window.innerWidth));
  useEffect(() => {
    const onResize = () => setW(window.innerWidth);
    onResize();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return w;
}

export default function CourtMap() {
  const tour = useTour();
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations("tour");
  const [active, setActive] = useState<CourtRegionId | null>(null);
  const [mounted, setMounted] = useState(false);
  const closeRef = useRef<HTMLButtonElement | null>(null);
  const open = tour.courtOpen;
  const docked = courtIsDocked(useViewportWidth());
  const railEdge = useRailEdge(open, docked);
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => setMounted(true), []);
  useRailLight(open && !docked ? (courtRegion(active)?.railTarget ?? null) : null);

  // FE-041. Declared before the effect below so it records the opener before focus moves to the close button.
  // The docked (phone) sheet sits over a backdrop, so it is modal: aria-modal + Tab trap. The desktop map leaves
  // the left rail live (regions light it up), so it stays non-modal - no trap. Both return focus to the opener
  // on close. Escape stays with the effect below.
  useDialogFocus(dialogRef, closeRef, null, { enabled: mounted && open, trap: docked });

  useEffect(() => {
    if (!open) {
      setActive(null);
      return undefined;
    }
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        tour.closeCourt();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, tour]);

  const walk = useCallback(
    (id: CourtRegionId) => {
      const region = courtRegion(id);
      if (!region) return;
      if (tour.walkCourtRegion(id)) return;
      tour.closeCourt();
      router.push(region.href);
    },
    [tour, router]
  );

  const skipToBriefing = useCallback(() => {
    tour.closeCourt();
    if (pathname !== "/build") router.push("/build");
    // The brief box may not be mounted yet after a route change; look for ~2s.
    let tries = 0;
    const focus = () => {
      const box = queryTourTarget("build-brief") as HTMLElement | null;
      if (box) {
        box.focus();
        return;
      }
      tries += 1;
      if (tries < 20) window.setTimeout(focus, 100);
    };
    focus();
  }, [tour, pathname, router]);

  if (!mounted || !open) return null;

  const regionText = (id: CourtRegionId) => ({
    name: t(`court.regions.${id}.name`),
    purpose: t(`court.regions.${id}.purpose`),
  });
  const stopsLine = (id: CourtRegionId) => {
    const n = tour.courtStopCount(id);
    return n > 0 ? t("court.stops", { count: n }) : t("court.noStops");
  };

  const header = (
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0">
        <h2 id="court-title" className="m-0 text-lg font-semibold tracking-tight text-foreground">
          {t("court.title")}
        </h2>
        <p className="m-0 mt-1 max-w-xl text-sm text-muted-foreground">{t("court.subtitle")}</p>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <Button variant="outline" size="sm" onClick={skipToBriefing} data-testid="court-skip">
          {t("court.skip")}
        </Button>
        <button
          ref={closeRef}
          type="button"
          onClick={tour.closeCourt}
          aria-label={t("court.close")}
          className="inline-flex size-9 cursor-pointer items-center justify-center rounded-md border-0 bg-transparent text-muted-foreground shadow-none outline-none transition-colors hover:bg-accent hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50"
        >
          <X className="size-4" aria-hidden="true" />
        </button>
      </div>
    </div>
  );

  if (docked) {
    return createPortal(
      <div className="fixed inset-0 z-[1200]" data-testid="court-sheet">
        <div className="absolute inset-0 bg-black/50" onClick={tour.closeCourt} aria-hidden="true" />
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="court-title"
          className="absolute inset-x-0 bottom-0 max-h-[85vh] overflow-y-auto rounded-t-xl border border-border bg-card p-4 text-card-foreground shadow-[var(--shadow-modal)]"
        >
          {header}
          <ul aria-label={t("court.listLabel")} className="m-0 mt-4 grid list-none gap-2 p-0">
            {visibleCourtRegions().map((r) => {
              const v = VIEW[r.id];
              const Icon = v.icon;
              const text = regionText(r.id);
              return (
                <li key={r.id}>
                  <button
                    type="button"
                    onClick={() => walk(r.id)}
                    data-testid={`court-region-${r.id}`}
                    className="flex w-full cursor-pointer items-start gap-3 rounded-lg border border-border bg-card p-3 text-start shadow-none outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50"
                    style={{ ["--cat" as string]: v.cat }}
                  >
                    <span className="cat-chip" aria-hidden="true">
                      <Icon className="size-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-semibold text-foreground">{text.name}</span>
                      <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">{text.purpose}</span>
                      <span className="mt-1 block text-xs text-foreground">{stopsLine(r.id)}</span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      </div>,
      document.body
    );
  }

  return createPortal(
    <div
      ref={dialogRef}
      role="dialog"
      aria-labelledby="court-title"
      data-testid="court-map"
      className="fixed bottom-0 right-0 top-0 z-[1200] overflow-y-auto bg-background p-6"
      style={{ left: railEdge }}
    >
      {header}
      <div className="mx-auto mt-4 max-w-[860px]">
        <svg viewBox="0 0 760 500" role="group" aria-label={t("court.mapLabel")} className="block h-auto w-full">
          {/* Walls: an outer ring and a fainter inner one, corner towers as small blocks. */}
          <rect x="20" y="20" width="720" height="460" rx="14" fill="none" stroke="var(--border)" strokeWidth="2" />
          <rect
            x="30"
            y="30"
            width="700"
            height="440"
            rx="10"
            fill="none"
            stroke="var(--border)"
            strokeWidth="1"
            strokeDasharray="4 6"
          />
          {[
            [14, 14],
            [726, 14],
            [14, 474],
            [726, 474],
          ].map(([x, y]) => (
            <rect
              key={`${x}-${y}`}
              x={x}
              y={y}
              width="20"
              height="12"
              rx="2"
              fill="var(--card)"
              stroke="var(--border)"
              strokeWidth="1.5"
            />
          ))}
          {/* The central yard, its fountain, and the paths to each hall. */}
          <rect
            x="250"
            y="176"
            width="260"
            height="148"
            rx="12"
            fill="var(--muted)"
            stroke="var(--border)"
            strokeWidth="1.5"
          />
          <circle cx="380" cy="250" r="34" fill="none" stroke="var(--muted-foreground)" strokeWidth="1.5" />
          <circle cx="380" cy="250" r="16" fill="none" stroke="var(--muted-foreground)" strokeWidth="1.5" />
          <g stroke="var(--muted-foreground)" strokeWidth="1.5" strokeDasharray="3 6" fill="none" opacity="0.7">
            <path d="M380 151 V176" />
            <path d="M380 324 V349" />
            <path d="M203 250 H250" />
            <path d="M510 250 H557" />
            {isBillingEnabled() && <path d="M510 324 L581 408" />}
          </g>
          {visibleCourtRegions().map((r) => {
            const v = VIEW[r.id];
            const Icon = v.icon;
            const text = regionText(r.id);
            const on = active === r.id;
            return (
              <g
                key={r.id}
                role="button"
                tabIndex={0}
                aria-label={`${text.name}. ${text.purpose}`}
                data-testid={`court-region-${r.id}`}
                className="cursor-pointer outline-none"
                style={{ ["--cat" as string]: v.cat }}
                onMouseEnter={() => setActive(r.id)}
                onMouseLeave={() => setActive((a) => (a === r.id ? null : a))}
                onFocus={() => setActive(r.id)}
                onBlur={() => setActive((a) => (a === r.id ? null : a))}
                onClick={() => walk(r.id)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    walk(r.id);
                  }
                }}
              >
                <rect
                  x={v.x}
                  y={v.y}
                  width={v.w}
                  height={v.h}
                  rx="10"
                  fill={`color-mix(in srgb, var(--cat) ${on ? 22 : 8}%, var(--card))`}
                  stroke="var(--cat)"
                  strokeWidth={on ? 3 : 1.75}
                />
                <rect
                  x={v.door.x}
                  y={v.door.y}
                  width={v.door.w}
                  height={v.door.h}
                  fill="var(--card)"
                  stroke="var(--cat)"
                  strokeWidth="1.5"
                />
                <Icon
                  x={v.x + v.w / 2 - 11}
                  y={v.y + v.h / 2 - 28}
                  width="22"
                  height="22"
                  color="var(--cat)"
                  aria-hidden="true"
                />
                <text
                  x={v.x + v.w / 2}
                  y={v.y + v.h / 2 + 12}
                  textAnchor="middle"
                  fontSize="15"
                  fontWeight="600"
                  fill="var(--foreground)"
                >
                  {text.name}
                </text>
              </g>
            );
          })}
        </svg>
        <div
          aria-live="polite"
          data-testid="court-caption"
          className="mt-3 min-h-[4.5rem] rounded-lg border border-border bg-card p-3 text-sm"
        >
          {active ? (
            <>
              <p className="m-0 font-semibold text-foreground">{regionText(active).name}</p>
              <p className="m-0 mt-0.5 text-muted-foreground">{regionText(active).purpose}</p>
              <p className="m-0 mt-1 text-xs text-foreground">{stopsLine(active)}</p>
            </>
          ) : (
            <p className="m-0 text-muted-foreground">{t("court.hint")}</p>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}

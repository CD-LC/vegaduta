"use client";

// Topbar "How this page works" button + panel. Mounted once in AppShell, keyed
// off the browser path, so every signed-in page - including the full-viewport
// workflow canvas - gets its guide with no per-page wiring.
//
// Behaviour:
//   - Opens ONLY from the button (2026-10-05, direct instruction: "how this
//     page works should not get opened - just keep only the tour guide").
//     It used to auto-open once per page per browser on a 700ms timer; that
//     meant a new user could be handed two guides in the same visit. The
//     first-run product tour is now the only surface that opens by itself.
//   - The product tour starting still closes an open panel, so the two never
//     overlap.
//   - Escape, an outside click, the close button or navigating away closes it.
//   - Renders nothing on a page without a guide.

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { CSSProperties } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { CircleHelp, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { useTour } from "../TourProvider";
import { copyOrFallback } from "../tour/consumerGuide";
import { ENTITY_FACTS } from "../../lib/platform-facts";
import { academyHref, hasAcademy } from "../../lib/academyUrl";
import { guideForPath } from "./pageGuides";

export default function PageGuideButton({ isAdmin }: { isAdmin: boolean }) {
  const pathname = usePathname();
  const t = useTranslations("pageGuide");
  const { status } = useTour();
  const guide = guideForPath(pathname, { isAdmin, edgeAvailable: edgeEnabled() });
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  // Only a user-initiated open steals focus into the panel.
  const manualOpenRef = useRef(false);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>({});
  const tourBusy = status === "welcome" || status === "active";

  // Navigating away always closes the panel.
  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  // The tour starting closes an already-open panel.
  useEffect(() => {
    if (tourBusy) setOpen(false);
  }, [tourBusy]);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setOpen(false);
        triggerRef.current?.focus();
      }
    }
    function onDown(e: MouseEvent) {
      // The panel is portaled to document.body (see below), so "inside" is
      // either the trigger wrapper or the panel itself.
      if (e.target instanceof Node && !rootRef.current?.contains(e.target) && !panelRef.current?.contains(e.target))
        setOpen(false);
    }
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("pointerdown", onDown as EventListener, true);
    return () => {
      document.removeEventListener("pointerdown", onDown as EventListener, true);
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onDown);
    };
  }, [open]);

  // User-initiated open: move focus into the panel.
  useEffect(() => {
    if (open && manualOpenRef.current) {
      panelRef.current?.focus();
    }
  }, [open]);

  // Position from the trigger's own bounding rect rather than `absolute
  // right-0` (anchored to the trigger, not the viewport): at phone width,
  // with several topbar controls to the trigger's right, that pushed a large
  // chunk of the panel off the left edge of the screen with no way to reach
  // it. Recomputed each time the panel opens.
  useEffect(() => {
    if (!open) return;
    const trigger = triggerRef.current;
    if (!trigger) return;
    const rect = trigger.getBoundingClientRect();
    const panelWidth = Math.min(352, window.innerWidth - 32);
    const left = Math.max(16, Math.min(rect.right - panelWidth, window.innerWidth - panelWidth - 16));
    setPanelStyle({
      position: "fixed",
      top: rect.bottom + 8,
      left,
      width: panelWidth,
      backgroundColor: "var(--surface-raised)",
    });
  }, [open]);

  if (!guide) return null;

  const copy = (key: string, fallback: string) => copyOrFallback(t(key), key, fallback, "pageGuide");
  const buttonLabel = copy("button", "How this page works");
  const panelId = `page-guide-${guide.id}`;

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        ref={triggerRef}
        onClick={() => {
          manualOpenRef.current = true;
          setOpen((v) => !v);
        }}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={buttonLabel}
        title={buttonLabel}
        className="flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md border border-[var(--surface-border)] bg-transparent px-2 py-0 text-xs font-medium text-[var(--text-muted)] shadow-none transition-colors hover:bg-transparent hover:text-[var(--text-primary)]"
      >
        <CircleHelp size={14} aria-hidden="true" />
        <span className="topbar-label hidden md:inline">{buttonLabel}</span>
      </button>

      {/* Portaled to <body>: the topbar <header> carries backdrop-blur-xl, and
          a non-none backdrop-filter makes it the containing block for fixed-
          positioned descendants - which anchors the panel's viewport
          coordinates to the header box and misplaces it entirely. */}
      {open &&
        createPortal(
          <div
            id={panelId}
            ref={panelRef}
            tabIndex={-1}
            role="dialog"
            aria-label={copy(`${guide.id}.title`, guide.title)}
            className="z-50 max-w-[calc(100vw-2rem)] rounded-lg border border-[var(--surface-border)] p-4 text-left shadow-lg outline-none"
            style={panelStyle}
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="text-sm font-semibold text-[var(--text-primary)]">
                {copy(`${guide.id}.title`, guide.title)}
              </h2>
              <button
                type="button"
                onClick={() => {
                  setOpen(false);
                  triggerRef.current?.focus();
                }}
                aria-label={copy("close", "Close")}
                className="flex size-6 shrink-0 items-center justify-center rounded border-0 bg-transparent p-0 text-[var(--text-muted)] shadow-none hover:bg-transparent hover:text-[var(--text-primary)]"
              >
                <X size={14} aria-hidden="true" />
              </button>
            </div>
            <p className="mt-1 text-xs leading-relaxed text-[var(--text-muted)]">
              {copy(`${guide.id}.purpose`, guide.purpose)}
            </p>
            <ol className="mt-3 space-y-2">
              {guide.steps.map((step, i) => (
                <li key={i} className="flex gap-2 text-xs leading-relaxed text-[var(--text-primary)]">
                  <span
                    aria-hidden="true"
                    className="flex size-5 shrink-0 items-center justify-center rounded-full border border-[var(--surface-border)] text-[length:var(--fs-micro)] font-semibold text-[var(--text-muted)]"
                  >
                    {i + 1}
                  </span>
                  <span>{copy(`${guide.id}.step${i + 1}`, step)}</span>
                </li>
              ))}
            </ol>
            {guide.reassurance && (
              <p className="mt-3 rounded-md border border-[var(--surface-border)] p-2 text-xs leading-relaxed text-[var(--text-muted)]">
                {copy(`${guide.id}.reassurance`, guide.reassurance)}
              </p>
            )}
            {guide.next && (
              <Link
                href={guide.next.href}
                onClick={() => setOpen(false)}
                className="mt-3 inline-block text-xs font-semibold text-primary hover:underline"
              >
                {copy(`${guide.id}.next`, guide.next.label)} →
              </Link>
            )}
            {/* The Academy teaches this surface — a plain link to the Academy
              origin (academyHref(); the OSS console never names private
              lesson ids). Distinct from `next`, which stays inside the
              console. Opens a new tab: it leaves the app. */}
            {guide.learnInAcademy && hasAcademy() && (
              <a
                href={academyHref() + (guide.academyTopic ?? "")}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-3 block text-xs font-semibold text-primary hover:underline"
              >
                {copy(`${guide.id}.learn`, copy("learnCta", "Learn this in the Academy"))} →
              </a>
            )}
            {/* Always last: a human is one email away, for ANY question. */}
            <p className="mt-3 border-t border-[var(--surface-border)] pt-3 text-xs leading-relaxed text-[var(--text-muted)]">
              {copy(
                "helpLine",
                "Stuck, or just curious how agentic AI works? No question is too simple. Write to us at"
              )}{" "}
              <a href={`mailto:${ENTITY_FACTS.contactEmail}`} className="font-semibold text-primary hover:underline">
                {ENTITY_FACTS.contactEmail}
              </a>
            </p>
          </div>,
          document.body
        )}
    </div>
  );
}

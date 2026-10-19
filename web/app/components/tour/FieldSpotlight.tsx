"use client";

import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { usePathname, useSearchParams } from "next/navigation";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { fieldFromSearch, resolveFieldTarget, stripFieldParam } from "./deepLink";

// One-step spotlight for `?tour=field:<data-tour-id>` (Earned Autonomy,
// 2026-09-26). Mounted by TourProvider next to its children so a deep link
// works on any console route, and deliberately NOT a step of the walkthrough:
// no audience or entitlement filtering, no persistence, no effect on the
// first-run tour's resume/"done" state. Someone who was sent to "the secret
// field" is sent there whatever their audience is.
//
// The scrim is a box-shadow on the ring and is pointer-transparent, so the
// lit control stays usable - the point is to type into it, not to admire it.
// Not shown = not found: the target is polled for ~6s (route transitions and
// fetches can beat it) and then the link quietly degrades to "you are on the
// right page".

const POLL_MS = 150;
const MAX_ATTEMPTS = 40;
const PAD = 6;

type Box = { top: number; left: number; width: number; height: number };

export default function FieldSpotlight() {
  const search = useSearchParams();
  const pathname = usePathname();
  const t = useTranslations("tour");
  const [target, setTarget] = useState<string | null>(null);
  const [box, setBox] = useState<Box | null>(null);
  const [label, setLabel] = useState("");

  // Capture the id, then strip the parameter at once so a refresh or a shared
  // URL does not re-fire it. `search` is the dependency because a same-page
  // link (a second card on /agents) changes only the query.
  useEffect(() => {
    const id = fieldFromSearch(window.location.search);
    if (!id) return;
    setTarget(id);
    try {
      window.history.replaceState(
        window.history.state,
        "",
        stripFieldParam(`${window.location.pathname}${window.location.search}${window.location.hash}`)
      );
    } catch {
      // A sandboxed frame may refuse; the spotlight still runs.
    }
  }, [search, pathname]);

  useEffect(() => {
    setBox(null);
    setLabel("");
    if (!target) return undefined;
    let cancelled = false;
    let poll = 0;
    let settle = 0;
    let attempts = 0;
    let el: Element | null = null;

    const measure = () => {
      if (cancelled || !el) return;
      const r = el.getBoundingClientRect();
      setBox(r.width > 0 && r.height > 0 ? { top: r.top, left: r.left, width: r.width, height: r.height } : null);
    };
    const found = (node: Element) => {
      el = node;
      setLabel((node.getAttribute("aria-label") || node.textContent || "").trim().replace(/\s+/g, " ").slice(0, 80));
      try {
        node.scrollIntoView({ block: "center", inline: "nearest", behavior: "smooth" });
      } catch {
        // jsdom / very old browsers: measure in place.
      }
      // A smooth scroll is still travelling after the call returns.
      const until = Date.now() + 800;
      const tick = () => {
        if (cancelled) return;
        measure();
        if (Date.now() < until) settle = window.setTimeout(tick, 60);
      };
      tick();
    };
    const look = () => {
      if (cancelled) return;
      const node = resolveFieldTarget(target);
      if (node) return found(node);
      attempts += 1;
      if (attempts < MAX_ATTEMPTS) poll = window.setTimeout(look, POLL_MS);
      else setTarget(null);
    };
    look();

    window.addEventListener("scroll", measure, true);
    window.addEventListener("resize", measure);
    return () => {
      cancelled = true;
      window.clearTimeout(poll);
      window.clearTimeout(settle);
      window.removeEventListener("scroll", measure, true);
      window.removeEventListener("resize", measure);
    };
  }, [target]);

  const dismiss = useCallback(() => setTarget(null), []);

  useEffect(() => {
    if (!target) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") dismiss();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [target, dismiss]);

  if (!target || !box || typeof document === "undefined") return null;

  // Below the target when there is room, above it otherwise; clamped to the viewport.
  const cardW = Math.min(320, window.innerWidth - 24);
  const below = box.top + box.height + PAD + 12 + 96 < window.innerHeight;
  const top = below ? box.top + box.height + PAD + 12 : Math.max(12, box.top - PAD - 12 - 96);
  const left = Math.min(Math.max(12, box.left), Math.max(12, window.innerWidth - cardW - 12));

  return createPortal(
    <>
      <div
        aria-hidden="true"
        className="pointer-events-none fixed z-[1150] rounded-[10px]"
        style={{
          top: box.top - PAD,
          left: box.left - PAD,
          width: box.width + PAD * 2,
          height: box.height + PAD * 2,
          border: "1.5px solid var(--primary)",
          boxShadow: "0 0 0 9999px rgb(0 0 0 / 0.55), 0 0 0 5px color-mix(in srgb, var(--primary) 20%, transparent)",
        }}
      />
      <div
        role="dialog"
        aria-label={t("field.here")}
        data-testid="field-spotlight-card"
        className="fixed z-[1160] rounded-xl border border-border bg-card p-3 text-card-foreground shadow-[var(--shadow-modal)]"
        style={{ top, left, width: cardW }}
      >
        <p className="m-0 text-sm font-medium text-foreground">{t("field.here")}</p>
        {label && <p className="m-0 mt-0.5 truncate text-xs text-muted-foreground">{label}</p>}
        <div className="mt-2 flex justify-end">
          <Button size="sm" onClick={dismiss}>
            {t("field.gotIt")}
          </Button>
        </div>
      </div>
    </>,
    document.body
  );
}

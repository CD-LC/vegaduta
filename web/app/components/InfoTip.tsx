"use client";

import { useEffect, useId, useRef, useState } from "react";
import { HelpCircle } from "lucide-react";
import { useTranslations } from "../lib/i18n";

/**
 * Small "?" info icon that reveals a short explanation on hover/click -
 * for controls whose label alone doesn't say enough (cron syntax, MCP
 * transport types, tool-call ceilings, etc). Deliberately tiny and
 * self-contained (no portal, no positioning library).
 *
 * Rewritten (UI polish follow-up) to use theme-aware classes
 * (.info-tip-trigger/.info-tip-bubble) instead of hardcoded hex colors, and
 * to open below-right of the trigger with a safe max-width instead of a
 * fixed 16rem anchored above-left - the old positioning could run off the
 * top of the viewport for any field near the top of a page, and off a
 * panel's edge for fields near the left/right border. Panels no longer
 * clip overflow (see .panel's comment in globals.css), so this bubble is
 * now actually visible in those cases too.
 *
 * A11y pass (UI audit follow-up): opens on keyboard focus (not just hover/
 * click), is associated with its trigger via aria-describedby + aria-
 * expanded, closes on Escape, and flips to the left when it would
 * otherwise run off the right edge of the viewport.
 */
export default function InfoTip({ text }: { text: string }) {
  const t = useTranslations("infoTip");
  const [open, setOpen] = useState(false);
  const [alignRight, setAlignRight] = useState(false);
  const bubbleId = useId();
  const rootRef = useRef<HTMLSpanElement>(null);
  const bubbleRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    // Flip anchor side when the bubble would run off the viewport's right
    // edge - measured, same approach LanguageSwitcher uses for its menu.
    const bubble = bubbleRef.current;
    if (bubble) {
      const rect = bubble.getBoundingClientRect();
      setAlignRight(rect.right > window.innerWidth - 8);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open]);

  return (
    <span
      ref={rootRef}
      style={{ position: "relative", display: "inline-block", marginLeft: "0.3rem" }}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        className="info-tip-trigger"
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setOpen((v) => !v);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        aria-label={t("moreInfo")}
        aria-expanded={open}
        aria-describedby={open ? bubbleId : undefined}
      >
        <HelpCircle size={12} strokeWidth={2.5} aria-hidden="true" />
      </button>
      {open && (
        <span
          ref={bubbleRef}
          id={bubbleId}
          role="tooltip"
          className="info-tip-bubble"
          style={{
            top: "calc(100% + 6px)",
            left: alignRight ? "auto" : 0,
            right: alignRight ? 0 : "auto",
          }}
        >
          {text}
        </span>
      )}
    </span>
  );
}

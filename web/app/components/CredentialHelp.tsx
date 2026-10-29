"use client";

import { useEffect, useId, useRef, useState } from "react";
import { HelpCircle, ExternalLink } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";

/**
 * "How do I get this?" popover for credential/setup fields - agentic AI and
 * everything it wires together (Twilio, LLM provider keys, MCP servers,
 * knowledge bases) is new to most people opening this console for the first
 * time, and a bare input labeled "Auth Token" or "API Key" gives no hint
 * where that value even comes from. Distinct from {@code InfoTip} (which
 * explains what a field DOES) - this explains how to go GET the value,
 * as a short numbered list plus a link to the real, current docs (numbered
 * steps live here rather than only linking out, since a still-unfamiliar
 * user benefits from a preview before leaving the app).
 */
interface CredentialHelpProps {
  title?: string;
  steps?: string[];
  href?: string;
  hrefLabel?: string;
  /** Official pricing page for this provider - linked, never quoted: prices,
   * free tiers and credit offers change, so the provider's own page is the
   * only source that's ever current. */
  pricingHref?: string;
}

export default function CredentialHelp({ title, steps, href, hrefLabel, pricingHref }: CredentialHelpProps) {
  const t = useTranslations("credentialHelp");
  const [open, setOpen] = useState(false);
  const [alignRight, setAlignRight] = useState(false);
  const ref = useRef<HTMLSpanElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const resolvedHrefLabel = hrefLabel || t("openOfficialDocs");
  const panelId = useId();

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  useEffect(() => {
    if (!open) return undefined;

    function getFocusable(): HTMLElement[] {
      const panel = panelRef.current;
      if (!panel) return [];
      return Array.from(
        panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])')
      );
    }

    // Not a modal - no backdrop, no full focus trap - so Tab should carry
    // focus on out to the rest of the page instead of cycling forever
    // inside a two-item popover. We just close it first so focus lands on
    // whatever the browser would naturally move to next.
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusable = getFocusable();
    (focusable[0] || panelRef.current)?.focus();

    const el = ref.current;
    if (el) {
      const rect = el.getBoundingClientRect();
      const panelWidth = Math.min(288, window.innerWidth - 32);
      setAlignRight(rect.left + panelWidth > window.innerWidth - 16);
    }

    let skipRefocus = false;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        setOpen(false);
        return;
      }
      if (e.key === "Tab") {
        const items = getFocusable();
        if (items.length === 0) {
          skipRefocus = true;
          setOpen(false);
          return;
        }
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          skipRefocus = true;
          setOpen(false);
        } else if (!e.shiftKey && document.activeElement === last) {
          skipRefocus = true;
          setOpen(false);
        }
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      if (!skipRefocus) previousFocusRef.current?.focus();
      previousFocusRef.current = null;
    };
  }, [open]);

  return (
    <span className="relative ms-1.5 inline-block" ref={ref}>
      <Button
        type="button"
        variant="link"
        className="h-auto gap-1 p-0 text-xs font-medium"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={(e) => {
          e.preventDefault();
          setOpen((v) => !v);
        }}
      >
        <HelpCircle aria-hidden="true" size={13} /> {t("howToGetThis")}
      </Button>
      {open && (
        <div
          id={panelId}
          // max-w clamps to the viewport (minus a small margin) so this
          // fixed w-72 popover can't push the page into horizontal scroll
          // when its trigger sits close to the right edge on a narrow
          // (~360-430px) viewport - w-72 still wins on any screen wide
          // enough to fit it. alignRight (measured on open) swaps the
          // anchor edge so a trigger near the right edge doesn't get its
          // popover clipped off-screen.
          className={`absolute top-full z-50 mt-2 w-72 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-card p-4 text-sm text-muted-foreground shadow-lg ${alignRight ? "right-0" : "left-0"}`}
          ref={panelRef}
          tabIndex={-1}
        >
          <strong className="mb-1.5 block font-semibold text-foreground">{title}</strong>
          <ol className="m-0 flex list-decimal flex-col gap-1.5 ps-4">
            {steps?.map((step: string, i: number) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
          {(href || pricingHref) && (
            <span className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1">
              {href && (
                <a
                  href={href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-primary hover:underline"
                >
                  {resolvedHrefLabel} <ExternalLink aria-hidden="true" size={12} />
                </a>
              )}
              {pricingHref && (
                <a
                  href={pricingHref}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-primary hover:underline"
                >
                  {t("pricingLinkLabel")} <ExternalLink aria-hidden="true" size={12} />
                </a>
              )}
            </span>
          )}
        </div>
      )}
    </span>
  );
}

"use client";

// The hub rail's section flyout panel. Lifted out of AppShell.tsx unchanged.

import Link from "next/link";
import { Shield } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { MutableRefObject } from "react";
import type { NavItem, NavSection } from "../nav/navSections";
import { NAV_TOUR_TARGETS } from "./navTourTargets";

export default function HubFlyout({
  section,
  top,
  panelRef,
  cancelClose,
  scheduleClose,
  isActive,
  markTourNavigation,
}: {
  section: NavSection;
  top: number;
  panelRef: MutableRefObject<HTMLDivElement | null>;
  cancelClose: () => void;
  scheduleClose: () => void;
  isActive: (item: NavItem) => boolean;
  markTourNavigation: () => void;
}) {
  const tNav = useTranslations("nav");
  const tLocalCode = useTranslations("localCode");
  return (
    <div
      role="menu"
      tabIndex={-1}
      aria-label={tNav(section.labelKey as string)}
      onMouseEnter={cancelClose}
      onMouseLeave={scheduleClose}
      // Focus into the panel (from the glyph's Enter/ArrowRight handoff)
      // must also cancel the glyph's blur-close, or the panel we just
      // focused into would tear itself down 140ms later.
      onFocus={cancelClose}
      onBlur={scheduleClose}
      // Measured clamp: the open-time estimate (340px) is too short for tall
      // groups (Admin), which cut the last rows (Maintenance) off at the
      // viewport bottom. Slide the panel up to fit, and scroll if taller.
      ref={(el) => {
        panelRef.current = el;
        if (!el) return;
        const h = el.offsetHeight;
        const maxTop = Math.max(8, window.innerHeight - 8 - h);
        const want = Math.min(top, maxTop);
        if (Math.abs(parseFloat(el.style.top) - want) > 0.5) el.style.top = want + "px";
      }}
      className="fixed start-[68px] z-[60] hidden max-h-[calc(100vh-1rem)] w-64 overflow-y-auto rounded-xl border border-[var(--surface-border-strong)] p-2 shadow-2xl lg:block"
      style={{
        top,
        ["--cat" as string]: section.cat,
        backgroundColor: "var(--surface-card)",
        animation: "popover-in 120ms ease-out",
      }}
    >
      <div
        className="flex items-center gap-1.5 px-2.5 pb-2 pt-1 text-[10px] font-semibold uppercase tracking-[0.16em]"
        style={{ color: "var(--cat)" }}
      >
        {section.adminOnly && <Shield className="size-3 shrink-0" aria-hidden="true" />}
        <span className="truncate">{tNav(section.labelKey as string)}</span>
        <span className="ms-auto font-normal tabular-nums">{section.items.length}</span>
      </div>
      <div
        className="h-px"
        style={{ background: "color-mix(in srgb, var(--cat) 30%, var(--surface-border))" }}
        aria-hidden="true"
      />
      <div className="mt-1 space-y-0.5">
        {section.items.map((item) => {
          const ItemIcon = item.icon;
          const active = isActive(item);
          return (
            <Link
              key={item.href}
              href={item.href}
              prefetch={false}
              data-tour={NAV_TOUR_TARGETS[item.href]}
              onClick={markTourNavigation}
              className={`group flex items-center gap-3 rounded-lg px-2.5 py-2 text-sm transition-colors ${
                active
                  ? "bg-[var(--surface-hover)] font-medium text-[var(--text-primary)]"
                  : "text-[var(--text-secondary)] hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
              }`}
            >
              <span
                className="flex size-7 shrink-0 items-center justify-center rounded-md transition-transform duration-150 group-hover:scale-110"
                style={{
                  color: "color-mix(in srgb, var(--cat) 80%, var(--text-muted))",
                  background: active ? "color-mix(in srgb, var(--cat) 14%, transparent)" : "var(--surface-sunken)",
                }}
              >
                <ItemIcon className="size-4" aria-hidden="true" />
              </span>
              <span className="min-w-0 flex-1 truncate">
                {item.href === "/code" ? tLocalCode(item.labelKey) : tNav(item.labelKey)}
              </span>
              {item.badge ? (
                // Auto-width pill, not a fixed circle - same reasoning as
                // the hub-glyph aggregate badge above (responsive audit
                // follow-up).
                <span className="flex h-4 min-w-4 shrink-0 items-center justify-center rounded-full bg-destructive px-1 text-[9px] font-bold tabular-nums text-destructive-foreground">
                  {typeof item.badge === "number" && item.badge > 9 ? "9+" : item.badge}
                </span>
              ) : null}
              {active && (
                <span
                  className="size-1.5 shrink-0 rounded-full"
                  style={{ background: "var(--cat)" }}
                  aria-hidden="true"
                />
              )}
            </Link>
          );
        })}
      </div>
    </div>
  );
}

"use client";

// Hub rail: one glyph per section; hover/focus opens the flyout. Lifted out of AppShell.tsx unchanged.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "../../lib/i18n";
import type { NavItem, NavSection } from "../nav/navSections";
import { NAV_GROUP_TOUR_TARGETS, NAV_TOUR_TARGETS } from "./navTourTargets";

export default function HubRail({
  navSections,
  isActive,
  sectionActive,
  sectionBadgeCount,
  flyoutKey,
  openFlyout,
  openFlyoutAndFocusFirst,
  scheduleFlyoutClose,
  closeFlyout,
  markTourNavigation,
}: {
  navSections: NavSection[];
  isActive: (item: NavItem) => boolean;
  sectionActive: (section: NavSection) => boolean;
  sectionBadgeCount: (section: NavSection) => number;
  flyoutKey: string | undefined;
  openFlyout: (key: string, el: HTMLElement) => void;
  openFlyoutAndFocusFirst: (key: string, el: HTMLElement) => void;
  scheduleFlyoutClose: () => void;
  closeFlyout: () => void;
  markTourNavigation: () => void;
}) {
  const tNav = useTranslations("nav");
  const router = useRouter();
  return (
          <div className="flex flex-col items-center gap-1 pt-1">
            {navSections.map((section) => {
              if (!section.labelKey) {
                // The lone HOME row stays a direct link, not a flyout.
                const item = section.items[0];
                if (!item) return null;
                const Icon = item.icon;
                const active = isActive(item);
                return (
                  <Link
                    key="home"
                    href={item.href} prefetch={false}
                    aria-label={tNav(item.labelKey)}
                    data-tour={NAV_TOUR_TARGETS[item.href]}
                    onClick={markTourNavigation}
                    className={`group relative flex size-11 items-center justify-center rounded-xl transition-colors ${
                      active
                        ? "bg-[var(--surface-hover)] text-[var(--text-primary)]"
                        : "text-[var(--text-secondary)] hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
                    }`}
                  >
                    <Icon
                      strokeWidth={active ? 2.4 : 2}
                      className="size-5 transition-transform duration-150 group-hover:scale-110"
                      aria-hidden="true"
                    />
                  </Link>
                );
              }
              const SectionIcon = section.icon;
              const active = sectionActive(section);
              const badges = sectionBadgeCount(section);
              const open = flyoutKey === section.labelKey;
              return (
                <button
                  key={section.labelKey}
                  type="button"
                  aria-label={tNav(section.labelKey)}
                  aria-haspopup="menu"
                  aria-expanded={open}
                  // PRODUCT-TOUR ANCHOR - same role as the section wrapper's
                  // marker in the classic list; in hub mode this glyph IS
                  // the "Test & Monitor" referent.
                  data-tour={section.labelKey ? NAV_GROUP_TOUR_TARGETS[section.labelKey] : undefined}
                  onMouseEnter={(e) => openFlyout(section.labelKey as string, e.currentTarget)}
                  onMouseLeave={scheduleFlyoutClose}
                  onFocus={(e) => openFlyout(section.labelKey as string, e.currentTarget)}
                  onBlur={scheduleFlyoutClose}
                  onKeyDown={(e) => {
                    if (e.key === "ArrowRight" || e.key === "Enter" || e.key === " ") {
                      // Enter/Space already navigate via onClick below for a
                      // mouse click; as a keyboard disclosure they instead
                      // open the flyout and hand focus to its first link,
                      // matching ArrowRight and every other menu-button
                      // pattern - clicking still jumps straight to the
                      // section's first destination.
                      e.preventDefault();
                      openFlyoutAndFocusFirst(section.labelKey as string, e.currentTarget);
                    }
                  }}
                  onClick={() => {
                    const first = section.items[0];
                    if (!first) return;
                    markTourNavigation();
                    closeFlyout();
                    router.push(first.href);
                  }}
                  className={`group relative flex size-11 items-center justify-center rounded-xl transition-colors ${
                    active || open
                      ? "text-[var(--text-primary)]"
                      : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
                  }`}
                  style={{
                    ["--cat" as string]: section.cat,
                    background: open || active ? "color-mix(in srgb, var(--cat) 16%, transparent)" : undefined,
                    boxShadow: active
                      ? "inset 0 0 0 1px color-mix(in srgb, var(--cat) 40%, transparent), 0 0 14px -4px var(--cat)"
                      : undefined,
                  }}
                >
                  {SectionIcon && (
                    <SectionIcon
                      strokeWidth={active || open ? 2.4 : 2}
                      className="size-5 transition-transform duration-150 group-hover:scale-110"
                      style={{ color: "color-mix(in srgb, var(--cat) 88%, var(--text-muted))" }}
                    />
                  )}
                  {badges > 0 && (
                    // Auto-width pill (min-width, not a fixed circle) plus
                    // a 9+ cap (responsive audit follow-up) - the global
                    // type floor raises this text-[9px] to 12px, which a
                    // fixed size-4 (16px) circle can't fit a bold two-digit
                    // count into. Matches NotificationBell's own badge.
                    <span
                      className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[9px] font-bold tabular-nums text-destructive-foreground"
                      aria-hidden="true"
                    >
                      {badges > 9 ? "9+" : badges}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
  );
}

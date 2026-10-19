"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { GraduationCap, History, Landmark, Search, Upload, type LucideIcon } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import type { NavSection } from "./AppShell";
import { classifyIntent } from "./tour/askCourt";
import { useTutorAvailable, withTutorRow } from "../learn/tutor/tutorAvailability";

// Sub-routes that are not rail rows (reached from a button on their parent page).
// Each is offered only when its parent row is visible to the caller, so it never
// widens who can see a destination.
const PALETTE_EXTRAS: ReadonlyArray<{ href: string; labelKey: string; parent: string; icon: LucideIcon }> = [
  { href: "/quick-task/history", labelKey: "paletteQuickTaskHistory", parent: "/quick-task", icon: History },
  { href: "/workflows/import", labelKey: "paletteWorkflowImport", parent: "/workflows", icon: Upload },
  { href: "/workflows/templates", labelKey: "paletteWorkflowTemplates", parent: "/workflows", icon: Upload },
  { href: "/agents/learning", labelKey: "paletteAgentLearning", parent: "/agents", icon: GraduationCap },
  { href: "/agents/import", labelKey: "paletteAgentImport", parent: "/agents", icon: Upload },
  { href: "/agents/templates", labelKey: "paletteAgentTemplates", parent: "/agents", icon: Upload },
];

/**
 * Global Cmd/Ctrl+K jump palette. Hand-rolled rather than pulling in `cmdk` -
 * neither `cmdk` nor `@radix-ui/react-dropdown-menu` is an existing
 * dependency, and this is a flat "jump to a known route" list, not a command
 * runner - a controlled fixed overlay covers the real need without a new
 * package. `sections` is the SAME already-filtered `navSections` AppShell
 * renders (admin-gating/APP_BUILDER_CONFIGURED/live-badge already applied),
 * so this can never offer a destination the rail itself wouldn't show.
 */
interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  sections: NavSection[];
}

export default function CommandPalette({ open, onOpenChange, sections }: CommandPaletteProps) {
  const router = useRouter();
  const tNav = useTranslations("nav");
  const tLocalCode = useTranslations("localCode");
  const tTour = useTranslations("tour");
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const listboxId = useId();

  // The personal tutor (/learn/tutor) has no rail row of its own; it joins the
  // palette next to Learn, and only once the server says the feature is on.
  const tutorOn = useTutorAvailable(open);
  const navRows = useMemo(
    () =>
      sections.flatMap((section) =>
        section.items.flatMap((item) => [
          {
            href: item.href,
            icon: item.icon,
            label: item.href === "/code" ? tLocalCode(item.labelKey) : tNav(item.labelKey),
            groupLabel: section.labelKey ? tNav(section.labelKey) : "",
          },
          ...PALETTE_EXTRAS.filter((extra) => extra.parent === item.href).map((extra) => ({
            href: extra.href,
            icon: extra.icon,
            label: tNav(extra.labelKey),
            groupLabel: section.labelKey ? tNav(section.labelKey) : "",
          })),
        ])
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [sections]
  );
  const flatItems = useMemo(
    () => withTutorRow(navRows, tutorOn, GraduationCap, tNav("railLearnTutor")),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [navRows, tutorOn]
  );

  // Ask-the-court: a question ("where do I set the whatsapp token") gets one
  // extra first row that opens the page WITH the control lit (?tour=field:...).
  // Static table in ./tour/askCourt.ts; the nav rows below still filter as before.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const rows = !q
      ? flatItems
      : flatItems.filter((item) => item.label.toLowerCase().includes(q) || item.groupLabel.toLowerCase().includes(q));
    const ask = classifyIntent(q);
    if (!ask) return rows;
    return [{ href: ask.href, icon: Landmark, label: tTour(`ask.${ask.id}`), groupLabel: tTour("ask.group") }, ...rows];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flatItems, query]);

  useEffect(() => {
    if (open) {
      setQuery("");
      setActiveIndex(0);
      // Focus after the overlay paints, not before - matches the existing
      // AvatarUploadModal/BrandIdentityModal open-focus pattern in this app.
      const id = requestAnimationFrame(() => inputRef.current?.focus());
      return () => cancelAnimationFrame(id);
    }
    return undefined;
  }, [open]);

  useEffect(() => {
    setActiveIndex(0);
  }, [query]);

  // Focus trap + focus restore (a11y audit follow-up). aria-modal="true" on
  // its own does not stop Tab from leaving the dialog into the page behind
  // it, and nothing previously returned focus to whatever opened the
  // palette (the rail's Jump-to button, or wherever Ctrl/Cmd+K was pressed)
  // once it closed.
  useEffect(() => {
    if (!open) return undefined;
    previousFocusRef.current = document.activeElement as HTMLElement | null;
    function onTabTrap(e: KeyboardEvent) {
      if (e.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
      ).filter((el) => !el.hasAttribute("disabled"));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", onTabTrap);
    return () => {
      document.removeEventListener("keydown", onTabTrap);
      previousFocusRef.current?.focus?.();
    };
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        onOpenChange(false);
        return;
      }
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((i) => Math.min(i + 1, filtered.length - 1));
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((i) => Math.max(i - 1, 0));
        return;
      }
      if (e.key === "Enter") {
        const target = filtered[activeIndex];
        if (target) {
          e.preventDefault();
          router.push(target.href);
          onOpenChange(false);
        }
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, filtered, activeIndex, onOpenChange, router]);

  // Keep the highlighted row in view when Arrow keys move activeIndex past
  // what's currently scrolled into view (a11y/controls audit follow-up).
  useEffect(() => {
    if (!open) return;
    listRef.current
      ?.querySelector(`#${CSS.escape(listboxId)}-option-${activeIndex}`)
      ?.scrollIntoView({ block: "nearest" });
  }, [open, activeIndex, listboxId]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[1200] flex items-start justify-center px-5 pt-[14vh]"
      data-escape-layer="command-palette"
      style={{ background: "color-mix(in srgb, var(--surface-sunken) 78%, transparent)", backdropFilter: "blur(2px)" }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onOpenChange(false);
      }}
      role="presentation"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={tNav("railJump")}
        className="w-full max-w-[560px] overflow-hidden rounded-xl border border-[var(--surface-border-strong)] shadow-2xl"
        style={{ background: "var(--surface-raised)" }}
      >
        <div className="flex items-center gap-2.5 border-b border-[var(--surface-border)] px-4 py-3">
          <Search size={16} className="shrink-0" style={{ color: "var(--text-faint)" }} aria-hidden="true" />
          <input
            ref={inputRef}
            id="command-palette-input"
            type="text"
            role="combobox"
            aria-expanded={filtered.length > 0}
            aria-controls={listboxId}
            aria-activedescendant={filtered[activeIndex] ? `${listboxId}-option-${activeIndex}` : undefined}
            aria-autocomplete="list"
            aria-label={tNav("railJump")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={tNav("railJump")}
            className="w-full min-w-0 bg-transparent text-[15px] outline-none"
            style={{ color: "var(--text-primary)" }}
            autoComplete="off"
            spellCheck={false}
          />
          <kbd
            className="shrink-0 rounded border border-[var(--surface-border-strong)] px-1.5 py-0.5 text-[10px]"
            style={{ background: "var(--surface-hover)", color: "var(--text-faint)" }}
          >
            esc
          </kbd>
        </div>
        <div
          ref={listRef}
          id={listboxId}
          role="listbox"
          aria-label={tNav("railJump")}
          className="max-h-[280px] overflow-y-auto py-1.5"
        >
          {filtered.length === 0 && (
            <p className="px-4 py-6 text-center text-sm" style={{ color: "var(--text-faint)" }}>
              {tNav("railJump")}
            </p>
          )}
          {filtered.map((item, i) => {
            const Icon = item.icon;
            const active = i === activeIndex;
            return (
              <button
                key={`${i}:${item.href}`}
                id={`${listboxId}-option-${i}`}
                role="option"
                aria-selected={active}
                type="button"
                onMouseEnter={() => setActiveIndex(i)}
                onClick={() => {
                  router.push(item.href);
                  onOpenChange(false);
                }}
                className="flex w-full items-center gap-3 rounded-lg px-4 py-2.5 text-left text-sm transition-colors"
                style={{
                  // Active row needs a cue that clears 3:1 non-text contrast
                  // against the popover surface in both themes - a plain
                  // surface-hover tint measured well under that (a11y/color
                  // audit follow-up), so this adds an accent-tinted fill plus
                  // a solid left rule.
                  background: active
                    ? "color-mix(in srgb, var(--accent-500) 12%, var(--surface-hover))"
                    : "transparent",
                  color: active ? "var(--text-primary)" : "var(--text-secondary)",
                  boxShadow: active ? "inset 2px 0 0 0 var(--accent-600)" : "none",
                }}
              >
                <Icon size={15} className="shrink-0" style={{ color: "var(--text-faint)" }} aria-hidden="true" />
                <span className="truncate">{item.label}</span>
                {item.groupLabel && (
                  <span className="ml-auto shrink-0 truncate text-[11px]" style={{ color: "var(--text-faint)" }}>
                    {item.groupLabel}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

"use client";

// The classic grouped, foldable rail (mobile drawer, touch, active tour). Lifted out of AppShell.tsx unchanged.

import { Shield, ChevronDown } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { NavItem, NavSection } from "../nav/navSections";
import { NAV_GROUP_TOUR_TARGETS } from "./navTourTargets";
import NavRow from "./NavRow";

export default function ClassicRail({
  navSections,
  closedGroups,
  toggleGroup,
  isActive,
  railRowCollapse,
  railLabelCls,
  onNavigate,
}: {
  navSections: NavSection[];
  closedGroups: Set<string>;
  toggleGroup: (labelKey: string) => void;
  isActive: (item: NavItem) => boolean;
  railRowCollapse: string;
  railLabelCls: string;
  onNavigate: () => void;
}) {
  const tNav = useTranslations("nav");
  return (
    <>
      {navSections.map((section, sectionIndex) => (
        <div
          key={section.labelKey ?? "top"}
          // PRODUCT-TOUR ANCHOR - keep. The tour's "monitor" step has no
          // single nav row to point at (the Test & Monitor group is
          // Playground + Files + three admin-only rows), so it spotlights
          // the whole group: header plus its rows.
          //
          // This used to sit on the group HEADER <div> below, which
          // carries railLabelCls - and railLabelCls is `lg:opacity-0` on
          // collapsibleRail pages (the workflow editor, the one caller
          // that passes collapsibleRail). TourOverlay's visibleRect()
          // rejects zero-size and off-screen rects but NOT opacity:0, so
          // the step happily spotlit a fully transparent element. The
          // section wrapper is never faded, so anchoring here is correct
          // at every width and on every page.
          data-tour={section.labelKey ? NAV_GROUP_TOUR_TARGETS[section.labelKey] : undefined}
          // Names the group for both consumers of this wrapper:
          //  - assistive tech, which ignores aria-label on a generic <div>
          //    (hence role="group"; this is a labelled set of links, which
          //    is exactly what role=group means);
          //  - the tour's referent pill, whose accessibleText() reads
          //    aria-label first and otherwise falls through to innerText -
          //    which on this wrapper concatenates the header and every row
          //    ("Test & Monitor Playground Files Issues Live activity
          //    Audit log"). The label is the SAME string the visible
          //    header below renders, tNav(section.labelKey), so the two
          //    can never drift; no new i18n key was added (en.json is not
          //    this file's to edit).
          role={section.labelKey ? "group" : undefined}
          aria-label={section.labelKey ? tNav(section.labelKey) : undefined}
          style={{
            ["--cat" as string]: section.cat,
            // Administration is visually fenced, not just labelled: a
            // faint tint of its own --cat plus the dashed border below.
            // This is the header's own request ("feel safe" alongside
            // "workstation") applied to elevated privilege specifically -
            // a member reads "not for me" at a glance, an admin reads
            // "handle with care," without splitting Administration into a
            // separate console (that fragmentation is the actual failure
            // mode this deliberately avoids - see the Console Bridge
            // artifact's research table on Make.com/MS Copilot Studio).
            ...(section.adminOnly ? { background: `color-mix(in srgb, ${section.cat} 6%, transparent)` } : {}),
          }}
          className={
            section.adminOnly
              ? "mt-2.5 rounded-lg border border-dashed border-[var(--surface-border-strong)] px-1 pb-1.5"
              : sectionIndex > 0
                ? "mt-2.5 border-t border-[var(--surface-border)] pt-2.5"
                : "pt-1"
          }
        >
          {section.labelKey && (
            <button
              type="button"
              onClick={() => toggleGroup(section.labelKey as string)}
              aria-expanded={!closedGroups.has(section.labelKey ?? "")}
              // The "nav-monitor" tour anchor used to live here; it moved
              // up to the section wrapper because this element is
              // lg:opacity-0 on collapsibleRail pages. See the note there.
              className={`flex w-full items-center gap-1.5 rounded px-3 pb-1.5 text-[10px] font-semibold uppercase tracking-[0.16em] select-none ${railLabelCls}`}
              style={{ color: "var(--cat)" }}
            >
              {section.adminOnly && <Shield className="size-3 shrink-0" aria-hidden="true" />}
              <span className="truncate">{tNav(section.labelKey)}</span>
              <ChevronDown
                aria-hidden="true"
                className={`size-3 ms-auto shrink-0 transition-transform duration-150 ${
                  closedGroups.has(section.labelKey ?? "") ? "-rotate-90" : ""
                }`}
              />
            </button>
          )}
          {section.labelKey ? (
            // CSS-only accordion (grid-template-rows 0fr/1fr) rather than
            // a guessed max-height - the eight groups range 1-8 items and
            // a fixed max-height either clips the long ones or leaves an
            // animation gap on the short ones. Default state (empty
            // closedGroups) is every section open - "all options
            // available," folding is opt-in per session, not a shipped
            // default.
            <div
              className="grid transition-[grid-template-rows] duration-200 ease-out"
              style={{ gridTemplateRows: closedGroups.has(section.labelKey ?? "") ? "0fr" : "1fr" }}
            >
              <div className="overflow-hidden">
                <div className="space-y-0.5 pt-0.5">
                  {section.items.map((item) => (
                    <NavRow
                      key={item.href}
                      item={item}
                      active={isActive(item)}
                      railRowCollapse={railRowCollapse}
                      railLabelCls={railLabelCls}
                      onNavigate={onNavigate}
                    />
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-0.5">
              {section.items.map((item) => (
                <NavRow
                  key={item.href}
                  item={item}
                  active={isActive(item)}
                  railRowCollapse={railRowCollapse}
                  railLabelCls={railLabelCls}
                  onNavigate={onNavigate}
                />
              ))}
            </div>
          )}
        </div>
      ))}
    </>
  );
}

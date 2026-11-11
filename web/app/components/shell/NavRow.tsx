"use client";

// One rail row (icon, label, badge, animated active indicator). Lifted out of AppShell.tsx unchanged.

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { useTranslations } from "../../lib/i18n";
import type { NavItem } from "../nav/navSections";
import { NAV_TOUR_TARGETS } from "./navTourTargets";

export default function NavRow({
  item,
  active,
  railRowCollapse,
  railLabelCls,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  railRowCollapse: string;
  railLabelCls: string;
  onNavigate: () => void;
}) {
  const tNav = useTranslations("nav");
  // /code's rail label lives in the "localCode" namespace with the rest of that page's copy.
  const tLocalCode = useTranslations("localCode");
  // prefers-reduced-motion can't be expressed in CSS for a JS-driven layout
  // animation, so the spring is swapped for a zero-duration transition.
  const reduceMotion = useReducedMotion();
  const railIndicatorTransition = reduceMotion
    ? { duration: 0 }
    : { type: "spring" as const, stiffness: 420, damping: 34 };
  const Icon = item.icon;
  return (
    <Link
      key={item.href}
      href={item.href}
      prefetch={false}
      aria-current={active ? "page" : undefined}
      data-tour={NAV_TOUR_TARGETS[item.href]}
      onClick={onNavigate}
      className={`group relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors ${railRowCollapse} ${
        active
          ? "font-semibold text-[var(--text-primary)]"
          : "font-medium text-[var(--text-secondary)] hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
      }`}
    >
      {active && (
        <motion.span
          layoutId="ax-rail-indicator"
          className="absolute inset-0 -z-10 rounded-lg border"
          style={{
            backgroundColor: "var(--surface-hover)",
            borderColor: "color-mix(in srgb, var(--cat) 30%, transparent)",
          }}
          transition={railIndicatorTransition}
        />
      )}
      {active && (
        <motion.span
          layoutId="ax-rail-accent"
          className="absolute start-0 top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-e-full"
          style={{
            backgroundColor: "var(--cat)",
            boxShadow: "0 0 10px 1px color-mix(in srgb, var(--cat) 45%, transparent)",
          }}
          transition={railIndicatorTransition}
        />
      )}
      <Icon
        strokeWidth={active ? 2.4 : 2.1}
        // A small bounce on hover/press, not just a color swap - transform
        // is a "group" hover (Link above already carries that class) so it
        // fires from anywhere in the row, not just the 18px glyph itself.
        className="size-5 shrink-0 transition-[color,transform] duration-150 group-hover:scale-[1.12] group-active:scale-95"
        style={{
          color: active ? "var(--cat)" : "color-mix(in srgb, var(--cat) 82%, var(--text-muted))",
        }}
      />
      <span className={`truncate ${railLabelCls}`}>
        {item.href === "/code" ? tLocalCode(item.labelKey) : tNav(item.labelKey)}
      </span>
      {item.badge && (
        <span
          className={`ms-auto rounded-full px-1.5 py-0.5 text-[10px] font-semibold tabular-nums ${railLabelCls} ${
            item.badgeVariant === "attention"
              ? "bg-destructive text-destructive-foreground"
              : "bg-[var(--surface-border)] text-[var(--text-secondary)]"
          }`}
        >
          {item.badge}
        </span>
      )}
    </Link>
  );
}

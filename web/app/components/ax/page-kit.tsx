"use client";

import type { ComponentType, ReactNode } from "react";
import { cn } from "../../lib/utils";
import { Card } from "../ui/card";

// Premium page kit — the shared building blocks that give every authenticated
// console screen one consistent rhythm (spacing, type scale, borders). All
// token-driven so they follow the app's light/dark theme + accent. Compose
// these with PageHeader + the shadcn ui/* primitives; keep bespoke data-viz
// (canvas, gauges, react-flow) inside them, don't replace it.

type IconType = ComponentType<{ className?: string }>;

export const SECTION_HEADER_CLASS = "flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between";
export const SECTION_ACTIONS_CLASS = "flex max-w-full flex-wrap items-center gap-2 sm:shrink-0";
/** For an action group a section passes as `actions`: its buttons wrap on a phone. */
export const SECTION_ACTION_GROUP_CLASS = "flex max-w-full flex-wrap items-center gap-2";

/** A titled content block with optional description + right-aligned actions. */
export function Section({
  title,
  description,
  actions,
  children,
  className,
}: {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("space-y-4", className)}>
      {(title || actions) && (
        // Below `sm` the header stacks and the actions wrap: a no-wrap row with
        // `shrink-0` actions pushed "New dataset" / "Configure gate" past the
        // right edge of a 375px screen with no way to scroll to them (prod pass
        // bug 5). From `sm` up the layout is the original single row.
        <div data-slot="section-header" className={SECTION_HEADER_CLASS}>
          <div className="min-w-0 space-y-0.5">
            {title && <h2 className="text-base font-semibold tracking-tight text-foreground">{title}</h2>}
            {description && <p className="text-sm text-muted-foreground">{description}</p>}
          </div>
          {actions && (
            <div data-slot="section-actions" className={SECTION_ACTIONS_CLASS}>
              {actions}
            </div>
          )}
        </div>
      )}
      {children}
    </section>
  );
}

/** Consistent zero/empty state — icon, title, description, optional CTA. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon?: IconType;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-card/40 px-6 py-14 text-center",
        className
      )}
    >
      {Icon && (
        // Soft radial accent glow behind the icon tile (aurora depth layer,
        // 2026-08-12) - decorative only, so contrast rules don't apply.
        <div className="relative mb-4">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -inset-6 rounded-full"
            style={{
              background:
                "radial-gradient(closest-side, color-mix(in srgb, var(--accent-500) 16%, transparent), transparent 72%)",
            }}
          />
          <div className="relative flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary">
            <Icon className="size-6" aria-hidden="true" />
          </div>
        </div>
      )}
      <h3 className="text-base font-semibold text-foreground">{title}</h3>
      {description && <p className="mt-1.5 max-w-sm text-sm text-muted-foreground">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

/** A single metric tile. `value` is the big figure; `sub` an optional caption.
 * Aurora depth layer (2026-08-12): the icon now sits in a .cat-chip identity
 * tile (tinted by the optional `cat` token, falling back to the accent), the
 * tile responds to hover via the shared .card-interactive treatment, and a
 * .card-sheen specular follows the pointer when the tile sits inside a
 * <Tilt3d> wrapper. API is backward-compatible - `cat` is the only new prop. */
export function StatCard({
  label,
  value,
  sub,
  icon: Icon,
  accent = false,
  cat,
  className,
}: {
  label: ReactNode;
  value: ReactNode;
  sub?: ReactNode;
  icon?: IconType;
  accent?: boolean;
  cat?: string;
  className?: string;
}) {
  return (
    <Card cat={cat} className={cn("card-interactive card-sheen h-full gap-0 p-5", className)}>
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</span>
        {Icon && (
          <span className="cat-chip cat-chip-sm" aria-hidden="true">
            <Icon className="size-3.5" />
          </span>
        )}
      </div>
      <div
        className={cn(
          "mt-3 text-[1.7rem] font-semibold leading-none tracking-tight tabular-nums",
          accent ? "text-gradient-accent" : "text-foreground"
        )}
      >
        {value}
      </div>
      {sub && <div className="mt-2 text-xs text-muted-foreground">{sub}</div>}
    </Card>
  );
}

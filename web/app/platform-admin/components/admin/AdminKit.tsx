"use client";

import type { ComponentProps, ReactNode } from "react";
import { AlertTriangle, Inbox, type LucideIcon } from "lucide-react";
import PageHeader from "../../../components/ax/PageHeader";
import { EmptyState } from "../../../components/ax/page-kit";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";

// Shared building blocks for platform-admin screens: header, empty/error
// states, filter row and a horizontally scrolling table wrapper. They exist so
// every admin list page reads, wraps and fails the same way.

/** Page header with the console's shared PageHeader (wraps actions on phones). */
export function AdminPageHeader(props: ComponentProps<typeof PageHeader>) {
  return <PageHeader {...props} />;
}

export function AdminEmptyState({
  title,
  description,
  icon = Inbox,
  action,
  className,
}: {
  title: ReactNode;
  description?: ReactNode;
  icon?: LucideIcon;
  action?: ReactNode;
  className?: string;
}) {
  return <EmptyState icon={icon} title={title} description={description} action={action} className={cn("py-10", className)} />;
}

/** Inline load-failure state with a Retry button. */
export function AdminErrorState({
  title = "Could not load this data",
  message,
  onRetry,
  className,
}: {
  title?: ReactNode;
  message?: ReactNode;
  onRetry?: () => void;
  className?: string;
}) {
  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center gap-2 rounded-xl border border-destructive/30 bg-destructive/5 px-6 py-8 text-center",
        className
      )}
    >
      <AlertTriangle className="size-6 text-destructive" aria-hidden="true" />
      <p className="text-sm font-semibold text-foreground">{title}</p>
      {message ? <p className="max-w-md text-sm text-muted-foreground">{message}</p> : null}
      {onRetry ? (
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  );
}

/** Wrapping row for search boxes, selects and toggles above a table. */
export function AdminFilterBar({
  children,
  className,
  label = "Filters",
}: {
  children: ReactNode;
  className?: string;
  label?: string;
}) {
  return (
    <div role="group" aria-label={label} className={cn("flex flex-wrap items-end gap-2 sm:gap-3", className)}>
      {children}
    </div>
  );
}

/** Scroll container so wide tables scroll inside the card, never the page. */
export function AdminTableWrap({
  children,
  className,
  label,
}: {
  children: ReactNode;
  className?: string;
  label?: string;
}) {
  return (
    <div
      className={cn("-mx-1 max-w-full overflow-x-auto px-1", className)}
      // Scrollable regions must be keyboard reachable (WCAG 2.1.1).
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
      role="region"
      aria-label={label ?? "Table"}
    >
      {children}
    </div>
  );
}

/** Tone helpers backed by the --state-* tokens (light/dark aware). */
export type AdminTone = "ok" | "warn" | "error";
export const TONE_TEXT: Record<AdminTone, string> = {
  ok: "text-[color:var(--state-success)]",
  warn: "text-[color:var(--state-warning)]",
  error: "text-[color:var(--state-error)]",
};
export const TONE_STATE_VAR: Record<AdminTone, string> = {
  ok: "var(--state-success)",
  warn: "var(--state-warning)",
  error: "var(--state-error)",
};

/** Tinted pill/box classes (border + soft fill + text) from the same tokens. */
export const TONE_BOX: Record<AdminTone, string> = {
  ok: "border-[color-mix(in_srgb,var(--state-success)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-success)_12%,transparent)] text-[color:var(--state-success)]",
  warn: "border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] text-[color:var(--state-warning)]",
  error: "border-[color-mix(in_srgb,var(--state-error)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-error)_12%,transparent)] text-[color:var(--state-error)]",
};

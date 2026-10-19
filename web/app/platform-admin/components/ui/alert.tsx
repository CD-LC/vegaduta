"use client";

import type { ComponentProps } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Alert (new-york), token-driven, with success/warning
// variants added on top of the stock default/destructive pair. Grid layout so
// an optional leading icon lines up with the title/description column.
const alertVariants = cva(
  "relative grid w-full grid-cols-[0_1fr] items-start gap-y-0.5 rounded-lg border px-4 py-3 text-sm " +
    "has-[>svg]:grid-cols-[calc(var(--spacing)*4)_1fr] has-[>svg]:gap-x-3 [&>svg]:size-4 [&>svg]:translate-y-0.5",
  {
    variants: {
      variant: {
        default: "border-border bg-card text-card-foreground [&>svg]:text-foreground",
        destructive: "border-destructive/30 bg-destructive/10 text-destructive [&>svg]:text-destructive",
        // text-*-600 measured ~2.95-3.43:1 on this alert's own bg tint -
        // under 4.5:1 AA for this body-text size. -700 clears it comfortably
        // (dark mode's -400 pairing was already AA and is untouched).
        success:
          "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)] [&>svg]:text-[var(--state-success)]",
        warning:
          "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)] [&>svg]:text-[var(--warning)]",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

function Alert({ className, variant, role, ...props }: ComponentProps<"div"> & VariantProps<typeof alertVariants>) {
  // role="alert" is an assertive live region meant for urgent, time-sensitive
  // announcements - appropriate for destructive/warning variants (which
  // appear in response to an event, e.g. a failed save), but wrong for the
  // default/success variants that render static, unchanging explanatory
  // copy present from first paint. Callers can still override explicitly.
  const defaultRole = variant === "destructive" || variant === "warning" ? "alert" : "note";
  return (
    <div
      data-slot="alert"
      role={role ?? defaultRole}
      className={cn(alertVariants({ variant }), className)}
      {...props}
    />
  );
}

function AlertTitle({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-title"
      className={cn("col-start-2 line-clamp-1 min-h-4 font-medium tracking-tight", className)}
      {...props}
    />
  );
}

function AlertDescription({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-description"
      className={cn("col-start-2 grid justify-items-start gap-1 text-sm [&_p]:leading-relaxed", className)}
      {...props}
    />
  );
}

export { Alert, AlertTitle, AlertDescription };

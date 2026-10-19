"use client";

import type { ComponentProps } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Alert (new-york), token-driven, with success/warning/
// error/info variants added on top of the stock default/destructive pair.
// Grid layout so an optional leading icon lines up with the title/
// description column.
const alertVariants = cva(
  "relative grid w-full grid-cols-[0_1fr] items-start gap-y-0.5 rounded-lg border px-4 py-3 text-sm " +
    "has-[>svg]:grid-cols-[calc(var(--spacing)*4)_1fr] has-[>svg]:gap-x-3 [&>svg]:size-4 [&>svg]:translate-y-0.5",
  {
    variants: {
      variant: {
        default: "border-border bg-card text-card-foreground [&>svg]:text-foreground",
        destructive:
          "border-destructive/30 bg-destructive/10 text-destructive [&>svg]:text-destructive",
        // 2026-07-29 design-system contract (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md
        // §3): re-based on --state-success/--state-warning/--state-error
        // instead of the older --success-*/--warning-* triplet, so this alert
        // reads the same hue as a .state-dot/.state-pill success/warning/
        // error indicator elsewhere in the app. Derivation matches
        // .state-pill's own formula (12%/32% color-mix) so the two systems
        // stay visually identical. Both --state-* tokens are AA-contrast
        // verified in light and dark (contract §3).
        success:
          "border-[color-mix(in_srgb,var(--state-success)_32%,transparent)] " +
          "bg-[color-mix(in_srgb,var(--state-success)_12%,transparent)] " +
          "text-[var(--state-success)] [&>svg]:text-[var(--state-success)]",
        warning:
          "border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] " +
          "bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] " +
          "text-[var(--state-warning)] [&>svg]:text-[var(--state-warning)]",
        // New: state-hue error alert, distinct from `destructive` (which
        // stays on the shared --destructive/--destructive-foreground tokens
        // other components key off). Use this one when the surrounding UI is
        // already speaking the state-hue system, e.g. next to a
        // `.state-dot`/`.state-pill` using --state-error.
        error:
          "border-[color-mix(in_srgb,var(--state-error)_32%,transparent)] " +
          "bg-[color-mix(in_srgb,var(--state-error)_12%,transparent)] " +
          "text-[var(--state-error)] [&>svg]:text-[var(--state-error)]",
        // New: neutral informational alert. Not an execution state, so it
        // stays on the existing --info-* triplet (globals.css) rather than
        // --state-* - already a real, contrast-verified token, just not
        // previously exposed as an Alert variant.
        info:
          "border-[var(--info-border)] bg-[var(--info-bg)] text-[var(--info-text)] [&>svg]:text-[var(--info-text)]",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

function Alert({ className, variant, ...props }: ComponentProps<"div"> & VariantProps<typeof alertVariants>) {
  return <div data-slot="alert" role="alert" className={cn(alertVariants({ variant }), className)} {...props} />;
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

"use client";

import type { ComponentProps, CSSProperties } from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Badge (new-york), token-driven.
const badgeVariants = cva(
  "inline-flex w-fit shrink-0 items-center justify-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-medium " +
    "[&>svg]:pointer-events-none [&>svg]:size-3 transition-colors",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary text-primary-foreground",
        // Was `bg-secondary text-secondary-foreground` — --secondary resolves to a
        // near-transparent overlay (rgba(255,255,255,0.06) in the .dark shadcn scope,
        // #f1f2f5 barely off the light-mode page bg), so the pill read as almost no
        // badge at all. `text-foreground` guarantees max contrast, and a real (if
        // soft) border gives the pill a visible edge in both themes, while staying
        // distinct from `outline` (full-opacity border, no fill) and `accent`
        // (violet-tinted border+fill).
        secondary: "border-border/50 bg-muted text-foreground",
        outline: "border-border text-foreground",
        accent: "border-primary/25 bg-accent text-accent-foreground",
        destructive: "border-transparent bg-destructive text-destructive-foreground",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

// 2026-07-29 design-system contract additions
// (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md §3/§4): two opt-in variants
// layered on top of the cva set above rather than folded into it, since
// their colour comes entirely from a custom property (--state/--cat) set via
// a prop instead of a fixed Tailwind class string. Existing variants/props
// (`default`/`secondary`/`outline`/`accent`/`destructive`) are unchanged.
export type BadgeStateOrCategoryVariant = "state" | "category";
export type BadgeVariant = NonNullable<VariantProps<typeof badgeVariants>["variant"]> | BadgeStateOrCategoryVariant;

type BadgeProps = Omit<ComponentProps<"span">, "color"> & {
  variant?: BadgeVariant;
  asChild?: boolean;
  /** `variant="state"` only: the `--state` custom-property value, e.g.
   *  `"var(--state-success)"`. Renders via the shared `.state-pill` utility
   *  (contract §3/§4). Falls back to the idle hue if omitted, same as the
   *  raw `.state-pill` class. */
  state?: string;
  /** `variant="category"` only: the `--cat` custom-property value, e.g.
   *  `"var(--cat-data)"`. Per the contract's hard rule (§2), colour must
   *  never be the only cue for category - pair this with an icon child. */
  cat?: string;
};

function Badge({ className, variant, asChild = false, state, cat, style, ...props }: BadgeProps) {
  const Comp: any = asChild ? Slot : "span";

  if (variant === "state") {
    return (
      <Comp
        data-slot="badge"
        className={cn("state-pill", "[&>svg]:pointer-events-none [&>svg]:size-3", className)}
        style={
          {
            ...(state ? { ["--state" as string]: state } : null),
            ...style,
          } as CSSProperties
        }
        {...props}
      />
    );
  }

  if (variant === "category") {
    return (
      <Comp
        data-slot="badge"
        className={cn(
          "inline-flex w-fit shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-2 py-0.5 " +
            "text-[0.6875rem] font-medium leading-5 transition-colors [&>svg]:pointer-events-none [&>svg]:size-3",
          className
        )}
        style={
          {
            color: "var(--cat, var(--accent-500))",
            backgroundColor: "color-mix(in srgb, var(--cat, var(--accent-500)) 13%, transparent)",
            borderColor: "color-mix(in srgb, var(--cat, var(--accent-500)) 28%, transparent)",
            ...(cat ? { ["--cat" as string]: cat } : null),
            ...style,
          } as CSSProperties
        }
        {...props}
      />
    );
  }

  return (
    <Comp
      data-slot="badge"
      className={cn(badgeVariants({ variant: variant as VariantProps<typeof badgeVariants>["variant"] }), className)}
      {...props}
    />
  );
}

export { Badge, badgeVariants };

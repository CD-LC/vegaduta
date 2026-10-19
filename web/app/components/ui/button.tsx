"use client";

import { useRef, type ComponentProps } from "react";
import Link from "next/link";
import { Slot } from "@radix-ui/react-slot";
import { Sparkles } from "lucide-react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";
import { showUpgradeLink } from "../../lib/billing-flag";
import { Tooltip, TooltipContent, TooltipTrigger } from "./tooltip";

// Canonical shadcn/ui Button (new-york), token-driven so it follows the
// shadcn theme layer in app/shadcn-theme.css (--primary/--secondary/--ring…).
// `asChild` lets a Next.js <Link> (or <a>) inherit the button styling without
// nesting a <button> inside an <a> — the pattern the landing CTAs use.
const buttonVariants = cva(
  "inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 whitespace-nowrap rounded-full text-sm font-medium outline-none transition-all " +
    "focus-visible:ring-[3px] focus-visible:ring-ring " +
    "disabled:pointer-events-none disabled:opacity-60 " +
    "[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*=size-])]:size-4",
  {
    variants: {
      variant: {
        // btn-glossy replaces shadow-sm shadow-primary/25 (it owns the full
        // box-shadow: inset top light-catch + primary-tinted drop; a shadow-*
        // utility here would out-cascade the components-layer rule).
        default: "bg-primary text-primary-foreground hover:bg-primary/90",
        destructive: "bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90",
        outline: "border border-border bg-transparent hover:bg-accent hover:text-accent-foreground",
        secondary: "bg-secondary text-secondary-foreground hover:bg-secondary/80",
        ghost: "hover:bg-accent hover:text-accent-foreground",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        default: "h-9 px-4 py-2 has-[>svg]:px-3",
        sm: "h-8 gap-1.5 px-3.5 text-xs",
        lg: "h-11 px-6 text-base",
        // p-0 is load-bearing: the global `button {}` rule in globals.css
        // (components layer) forces px-3.5 py-2 on every <button>, and the icon
        // size sets no padding of its own — without this reset a size-9 icon
        // button is squeezed to ~0 content width and the glyph is clipped away.
        icon: "size-9 p-0",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  }
);

type ButtonProps = ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
    // Billing / FREE-tier gate. When true, the button reads as locked — it looks
    // disabled and does nothing on click — but, UNLIKE a native `disabled`
    // button (which swallows all pointer events), it still reveals an Upgrade
    // affordance on hover/focus via a tooltip. This keeps the upsell ON the
    // control itself instead of as a separate inline element taking up page
    // space. Use this instead of `disabled` wherever a control is plan-gated:
    //   <Button upgradeLocked={isFreePlan} upgradeReason={t("proOnlyFeature")}>…</Button>
    upgradeLocked?: boolean;
    // One line explaining what upgrading unlocks (shown above the Upgrade link).
    upgradeReason?: string;
    // Where the Upgrade link points. Defaults to the billing screen.
    upgradeHref?: string;
    // Label for the Upgrade link inside the tooltip.
    upgradeLabel?: string;
  };

function Button({
  className,
  variant,
  size,
  asChild = false,
  type,
  disabled,
  onClick,
  upgradeLocked = false,
  upgradeReason,
  upgradeHref = "/admin/billing",
  upgradeLabel = "Upgrade",
  ...props
}: ButtonProps) {
  const Comp: any = asChild ? Slot : "button";
  // The hover-upgrade treatment only makes sense on a real <button> (not a
  // Slot/asChild passthrough, which owns its own element + events).
  const locked = upgradeLocked && !asChild;
  // WB-017: anchor the portal right after the trigger in DOM order (see
  // upgrade-lock.tsx for the same pattern) so the focusable "Upgrade" link
  // is reachable by Tab immediately after the trigger. Declared unconditionally
  // - hooks can't follow the `if (!locked) return button;` early return below.
  const anchorRef = useRef<HTMLSpanElement>(null);

  const button = (
    <Comp
      data-slot="button"
      type={asChild ? undefined : (type ?? "button")}
      aria-disabled={locked ? true : undefined}
      data-upgrade-locked={locked ? "" : undefined}
      // Intentionally NOT natively `disabled` when locked, so the wrapper below
      // can still receive hover/focus and surface the tooltip.
      disabled={locked ? undefined : disabled}
      onClick={
        locked
          ? (e: any) => {
              e.preventDefault();
              e.stopPropagation();
            }
          : onClick
      }
      className={cn(buttonVariants({ variant, size }), locked && "cursor-not-allowed opacity-60", className)}
      {...props}
    />
  );

  if (!locked) return button;

  return (
    <span className="relative inline-flex">
      <Tooltip>
        <TooltipTrigger asChild>
          {/* A focusable wrapper so the tooltip opens on hover AND keyboard focus
              even though the inner button is click-inert. */}
          <span className="inline-flex" tabIndex={0}>
            {button}
          </span>
        </TooltipTrigger>
        <TooltipContent container={anchorRef.current ?? undefined} className="flex flex-col items-start gap-1.5">
          {upgradeReason && <span>{upgradeReason}</span>}
          {/* Billing off (lib/billing-flag.ts): the lock and its reason stay,
              the Upgrade link does not - there is no plan to buy. */}
          {showUpgradeLink(upgradeHref) && (
            <Link
              href={upgradeHref}
              className="inline-flex items-center gap-1 font-semibold text-[var(--upgrade)] hover:underline"
            >
              <Sparkles size={12} aria-hidden="true" />
              {upgradeLabel}
            </Link>
          )}
        </TooltipContent>
      </Tooltip>
      <span ref={anchorRef} aria-hidden="true" />
    </span>
  );
}

export { Button, buttonVariants };

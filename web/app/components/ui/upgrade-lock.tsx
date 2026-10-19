"use client";

import { useRef, type ReactNode } from "react";
import Link from "next/link";
import { Sparkles } from "lucide-react";
import { cn } from "../../lib/utils";
import { showUpgradeLink } from "../../lib/billing-flag";
import { Tooltip, TooltipContent, TooltipTrigger } from "./tooltip";

// Hover-popup upgrade affordance for NON-button controls (badges, chips, static
// labels) — the counterpart to the shared Button's `upgradeLocked` prop. Wraps
// any element so that hovering/focusing it reveals a tooltip with the reason +
// an "Upgrade" link, keeping the upsell ON the control instead of as a separate
// inline pill/button on the page. Use this to replace inline <UpgradePill> next
// to a plan-gated, non-interactive control.
function UpgradeLock({
  reason,
  href = "/admin/billing",
  label = "Upgrade",
  className,
  children,
}: {
  reason?: string;
  href?: string;
  label?: string;
  className?: string;
  children: ReactNode;
}) {
  // WB-017: anchor the portal right after the trigger in DOM order so the
  // focusable "Upgrade" link inside is reachable by Tab immediately after
  // the trigger, instead of wherever Radix's default document.body portal
  // happens to land (end of body - unreachable in natural tab order).
  const anchorRef = useRef<HTMLSpanElement>(null);

  return (
    <span className="relative inline-flex">
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            tabIndex={0}
            className={cn(
              "inline-flex outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-md",
              className
            )}
          >
            {children}
          </span>
        </TooltipTrigger>
        <TooltipContent container={anchorRef.current ?? undefined} className="flex flex-col items-start gap-1.5">
          {reason && <span>{reason}</span>}
          {/* Billing off (lib/billing-flag.ts): reason only, no Upgrade link. */}
          {showUpgradeLink(href) && (
            <Link
              href={href}
              className="inline-flex items-center gap-1 font-semibold text-[var(--upgrade)] hover:underline"
            >
              <Sparkles size={12} aria-hidden="true" />
              {label}
            </Link>
          )}
        </TooltipContent>
      </Tooltip>
      <span ref={anchorRef} aria-hidden="true" />
    </span>
  );
}

export { UpgradeLock };

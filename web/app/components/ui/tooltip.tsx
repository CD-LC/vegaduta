"use client";

import type { ComponentProps } from "react";
import * as TooltipPrimitive from "@radix-ui/react-tooltip";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Tooltip (new-york), Radix-backed. Each <Tooltip> bundles
// its own Provider so call sites don't need a global one. Token-driven so it
// inherits the shadcn dark theme. Used to explain plan-gated / disabled
// controls (e.g. FREE-tier "Set fallback") on hover + focus, replacing the raw
// native `title` attribute with an accessible, styled surface.
function TooltipProvider({
  delayDuration = 200,
  ...props
}: ComponentProps<typeof TooltipPrimitive.Provider>) {
  return <TooltipPrimitive.Provider data-slot="tooltip-provider" delayDuration={delayDuration} {...props} />;
}

function Tooltip(props: ComponentProps<typeof TooltipPrimitive.Root>) {
  return (
    <TooltipProvider>
      <TooltipPrimitive.Root data-slot="tooltip" {...props} />
    </TooltipProvider>
  );
}

function TooltipTrigger(props: ComponentProps<typeof TooltipPrimitive.Trigger>) {
  return <TooltipPrimitive.Trigger data-slot="tooltip-trigger" {...props} />;
}

function TooltipContent({
  className,
  sideOffset = 6,
  children,
  container,
  ...props
}: ComponentProps<typeof TooltipPrimitive.Content> & {
  // Optional portal target (WB-017). Defaults to undefined, which Radix's
  // Portal resolves to document.body - identical to prior behavior for every
  // caller that doesn't pass it. Callers that render a focusable CTA inside
  // the tooltip (e.g. UpgradeLock, Button's upgradeLocked) can pass a DOM
  // node placed right after their trigger so the portaled content lands next
  // to it in DOM/tab order instead of at the end of <body>.
  container?: HTMLElement | null;
}) {
  return (
    <TooltipPrimitive.Portal container={container}>
      <TooltipPrimitive.Content
        data-slot="tooltip-content"
        sideOffset={sideOffset}
        className={cn(
          "z-[1000] max-w-xs rounded-md border border-border bg-popover px-3 py-1.5 text-xs leading-relaxed text-popover-foreground shadow-md " +
            // Reuse the app's shared `popover-in` keyframe (globals.css) so the
            // tooltip's entrance matches every other popover/dropdown, rather
            // than depending on tailwindcss-animate (not installed here).
            "origin-[var(--radix-tooltip-content-transform-origin)] animate-[popover-in_0.14s_cubic-bezier(0.16,1,0.3,1)_both]",
          className
        )}
        {...props}
      >
        {children}
        <TooltipPrimitive.Arrow className="-my-px fill-popover drop-shadow-[0_1px_0_var(--border)]" />
      </TooltipPrimitive.Content>
    </TooltipPrimitive.Portal>
  );
}

export { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider };

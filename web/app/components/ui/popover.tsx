"use client";

import type { ComponentProps } from "react";
import * as PopoverPrimitive from "@radix-ui/react-popover";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Popover (new-york), Radix-backed - this app's other
// floating-panel primitives (Tooltip, AlertDialog) already went through this
// exact port; this is the same shape for a click-triggered (not hover)
// panel. First real call site: NotificationBell.tsx. Token-driven so it
// themes light/dark, and its entrance reuses the app's shared `popover-in`
// keyframe (globals.css) rather than tailwindcss-animate (not installed here).
function Popover(props: ComponentProps<typeof PopoverPrimitive.Root>) {
  return <PopoverPrimitive.Root data-slot="popover" {...props} />;
}

function PopoverTrigger(props: ComponentProps<typeof PopoverPrimitive.Trigger>) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />;
}

function PopoverAnchor(props: ComponentProps<typeof PopoverPrimitive.Anchor>) {
  return <PopoverPrimitive.Anchor data-slot="popover-anchor" {...props} />;
}

function PopoverContent({
  className,
  align = "end",
  sideOffset = 8,
  children,
  ...props
}: ComponentProps<typeof PopoverPrimitive.Content>) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        data-slot="popover-content"
        align={align}
        sideOffset={sideOffset}
        className={cn(
          // z-[1000] - the shared floating-panel tier (see globals.css's
          // "Floating-panel z tier" block): above the dock/orb/canvas
          // overlays, below toasts. Was z-50, which canvas overlays could
          // bury.
          "z-[1000] w-80 max-w-[90vw] rounded-lg border border-border bg-popover text-popover-foreground shadow-lg outline-none",
          "origin-[var(--radix-popover-content-transform-origin)] animate-[popover-in_0.16s_cubic-bezier(0.16,1,0.3,1)_both]",
          className
        )}
        {...props}
      >
        {children}
      </PopoverPrimitive.Content>
    </PopoverPrimitive.Portal>
  );
}

export { Popover, PopoverTrigger, PopoverContent, PopoverAnchor };

"use client";

import { forwardRef } from "react";
import type { ComponentProps, ReactNode } from "react";
import { Button } from "../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import { cn } from "../../lib/utils";

/**
 * The workflow designer's one icon-button, built exactly to the console's
 * icon-button convention (KnowledgePanel, AgentWorkbench): shadcn `Button`
 * variant ghost, size icon, muted -> foreground on hover, pill shape from the
 * design system, `aria-label` + Radix `Tooltip` showing the label and, when
 * there is one, the keyboard shortcut. Every icon control in the studio chrome (top bar, panel
 * headers, rails, canvas toolbar) uses this so they share size, hover, focus
 * ring, pressed state and tooltip behaviour instead of four hand-rolled
 * button styles.
 *
 * `label` is also the accessible name (aria-label), so selectors keyed on it
 * keep working.
 */
export interface IconActionProps extends Omit<ComponentProps<typeof Button>, "children" | "size"> {
  label: string;
  shortcut?: string;
  pressed?: boolean;
  side?: "top" | "bottom" | "left" | "right";
  /** "md" (32px) for bars, "sm" (28px - the console default) for panel headers. */
  scale?: "sm" | "md";
  children: ReactNode;
}

export const IconAction = forwardRef<HTMLButtonElement, IconActionProps>(function IconAction(
  { label, shortcut, pressed, side = "bottom", scale = "md", className, variant = "ghost", children, ...props },
  ref,
) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          ref={ref}
          type="button"
          variant={variant}
          size="icon"
          aria-label={label}
          aria-pressed={pressed}
          data-pressed={pressed ? "true" : undefined}
          className={cn(
            "wf-icon-action text-muted-foreground hover:text-foreground",
            scale === "md" ? "size-8" : "size-7",
            pressed && "bg-accent text-foreground",
            className,
          )}
          {...props}
        >
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent side={side} className="wf-icon-action-tip">
        <span>{label}</span>
        {shortcut && <kbd className="wf-icon-action-kbd">{shortcut}</kbd>}
      </TooltipContent>
    </Tooltip>
  );
});

/** A bordered, segmented cluster of IconActions (e.g. the layout toggles). */
export function ActionGroup({ label, className, children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <div role="group" aria-label={label} className={cn("wf-action-group", className)}>
      {children}
    </div>
  );
}

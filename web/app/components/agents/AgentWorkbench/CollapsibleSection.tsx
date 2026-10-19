"use client";

import type { ReactNode } from "react";
import * as AccordionPrimitive from "@radix-ui/react-accordion";
import { ChevronDown } from "lucide-react";
import { cn } from "../../../lib/utils";

// A config section rendered as a collapsible card (direct feedback: "use
// Accordion to keep sections minimized until expanded by user"). Built on the
// Radix Accordion primitive so the label+chevron is the toggle while any
// header `actions` stay as siblings — clicking an action never toggles the
// section. Each section is its own single-item accordion so they collapse
// independently and the surrounding conditional layout is untouched.
export function CollapsibleSection({
  icon,
  label,
  actions = null,
  defaultOpen = false,
  className,
  cat,
  anchorId,
  tourId,
  children,
}: {
  icon?: ReactNode;
  label: string;
  // Guide anchor (2026-09-19): the "Create your agent" checklist scrolls to a
  // section by this id and opens it by clicking its closed trigger - these
  // accordions are uncontrolled, so the DOM is the only handle (guideDom.ts).
  anchorId?: string;
  // data-tour id for ?tour=field:<id> deep links (Coach cards, Build Center).
  tourId?: string;
  actions?: ReactNode;
  defaultOpen?: boolean;
  className?: string;
  // Per-section category token (e.g. "var(--cat-data)"). Replaces the previous
  // `accent` prop, which took a raw Tailwind palette pair
  // ("text-violet-500 dark:text-violet-400") — six hand-picked ramps that had
  // no relationship to the platform's category system and no guaranteed
  // contrast on the dark rungs. The token now drives BOTH the icon chip and
  // the card's hover hairline (.card-interactive reads --cat), and the icon
  // is mandatory so hue is never the only cue (design contract §2).
  cat?: string;
  children: ReactNode;
}) {
  // Deliberately NOT .card-interactive: that utility lifts on :focus-within,
  // which on a section full of inputs would make the whole panel jump every
  // time a field is clicked. .card-interactive is reserved on this screen for
  // things that really are one clickable target (switcher rows, tool cards).
  return (
    <AccordionPrimitive.Root
      id={anchorId}
      data-tour={tourId}
      type="single"
      collapsible
      defaultValue={defaultOpen ? "section" : undefined}
      className={cn("rounded-xl border border-border bg-card", className)}
      style={cat ? ({ ["--cat" as any]: cat } as any) : undefined}
    >
      <AccordionPrimitive.Item value="section">
        <div className="flex items-center justify-between gap-2 px-5 pt-4 pb-1.5">
          <AccordionPrimitive.Header className="flex min-w-0 flex-1">
            <AccordionPrimitive.Trigger
              data-guide-trigger=""
              className="group flex flex-1 cursor-pointer items-center gap-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:text-foreground"
            >
              {icon != null && <span className="cat-chip cat-chip-sm">{icon}</span>}
              <span className="truncate">{label}</span>
              <ChevronDown className="ml-1 size-3.5 shrink-0 text-muted-foreground transition-transform duration-200 group-data-[state=open]:rotate-180" />
            </AccordionPrimitive.Trigger>
          </AccordionPrimitive.Header>
          {actions ? <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">{actions}</div> : null}
        </div>
        <AccordionPrimitive.Content className="overflow-hidden data-[state=closed]:animate-accordion-up data-[state=open]:animate-accordion-down">
          <div className="px-5 pb-5 pt-1.5">{children}</div>
        </AccordionPrimitive.Content>
      </AccordionPrimitive.Item>
    </AccordionPrimitive.Root>
  );
}

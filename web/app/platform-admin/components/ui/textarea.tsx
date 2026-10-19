"use client";

import type { ComponentProps } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Textarea (new-york), token-driven — matches Input.
// Several screens composed inline token-styled textareas before this existed;
// new code should import this instead.
function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        // See Input's identical fix: border-border is ~1.2:1 against the
        // card - border-[var(--text-muted)] uses the app's own AA-verified
        // per-theme muted-text token instead, clearing 3:1 in both modes.
        "flex min-h-16 w-full rounded-md border border-[var(--text-muted)] bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow]",
        "placeholder:text-muted-foreground field-sizing-content",
        "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40",
        "disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
      {...props}
    />
  );
}

export { Textarea };

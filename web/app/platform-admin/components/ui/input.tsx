"use client";

import type { ComponentProps } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Input (new-york), token-driven.
function Input({ className, type = "text", ...props }: ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        // border-border (--sc-border) resolves to ~1.2:1 against the card in
        // both themes - well under the 3:1 non-text contrast minimum for a
        // form field boundary. border-[var(--text-muted)] instead uses the
        // app's own per-theme muted-text token (already AA-verified as text
        // elsewhere), which clears 3:1 solidly against the card in both modes.
        "flex h-10 w-full min-w-0 rounded-md border border-[var(--text-muted)] bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow]",
        "placeholder:text-muted-foreground",
        "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40",
        "disabled:cursor-not-allowed disabled:opacity-50",
        "file:border-0 file:bg-transparent file:text-sm file:font-medium",
        className
      )}
      {...props}
    />
  );
}

export { Input };

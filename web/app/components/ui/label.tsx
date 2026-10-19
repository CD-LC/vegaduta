"use client";

import type { ComponentProps } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Label (new-york), token-driven.
function Label({ className, ...props }: ComponentProps<"label">) {
  return (
    // eslint-disable-next-line jsx-a11y/label-has-associated-control -- generic primitive; callers pass htmlFor or nest the control via {...props}
    <label
      data-slot="label"
      className={cn(
        "flex items-center gap-2 text-sm font-medium leading-none text-foreground select-none",
        "peer-disabled:cursor-not-allowed peer-disabled:opacity-50",
        className
      )}
      {...props}
    />
  );
}

export { Label };

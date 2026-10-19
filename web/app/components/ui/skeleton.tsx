"use client";

import type { ComponentProps } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Skeleton (new-york), token-driven.
function Skeleton({ className, ...props }: ComponentProps<"div">) {
  return <div data-slot="skeleton" className={cn("animate-pulse rounded-md bg-secondary", className)} {...props} />;
}

export { Skeleton };

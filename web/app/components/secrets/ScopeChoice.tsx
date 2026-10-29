"use client";

import type { ReactNode } from "react";
import { cn } from "../../lib/utils";

/** One segmented-control option for a secret's scope (workspace vs personal). */
export default function ScopeChoice({
  active,
  disabled,
  onClick,
  icon,
  label,
  hint,
  testid,
}: {
  active: boolean;
  disabled: boolean;
  onClick: () => void;
  icon: ReactNode;
  label: string;
  hint: string;
  testid: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
      data-testid={testid}
      className={cn(
        "flex max-w-xs flex-col items-start gap-0.5 rounded-md px-3 py-2 text-start",
        active && "border-primary bg-primary/10",
        disabled && "cursor-not-allowed opacity-50"
      )}
    >
      <span className="flex items-center gap-1.5 text-xs font-medium">
        {icon} {label}
      </span>
      <span className="text-xs text-muted-foreground">{hint}</span>
    </button>
  );
}

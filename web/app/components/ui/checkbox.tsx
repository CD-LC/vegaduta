"use client";

// Checkbox: accessible tri-state checkbox (checked / unchecked / indeterminate).
//
// Usage:
//   <Checkbox aria-label="Select all" checked={all ? true : some ? "indeterminate" : false}
//             onCheckedChange={(next) => toggleAll(next)} />
// Renders role="checkbox" with aria-checked="true|false|mixed"; Space toggles
// (native button), indeterminate -> checked on activation.

import type { ComponentProps } from "react";
import { Check, Minus } from "lucide-react";
import { cn } from "../../lib/utils";

export type CheckedState = boolean | "indeterminate";

function Checkbox({
  checked = false,
  onCheckedChange,
  className,
  disabled,
  ...props
}: Omit<ComponentProps<"button">, "onChange" | "role"> & {
  checked?: CheckedState;
  onCheckedChange?: (checked: boolean) => void;
}) {
  const state = checked === "indeterminate" ? "mixed" : checked ? "true" : "false";
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={state}
      data-slot="checkbox"
      data-state={checked === "indeterminate" ? "indeterminate" : checked ? "checked" : "unchecked"}
      disabled={disabled}
      onClick={() => onCheckedChange?.(checked === "indeterminate" ? true : !checked)}
      className={cn(
        "inline-flex size-4 shrink-0 items-center justify-center rounded-[4px] border border-control p-0 shadow-xs outline-none transition-all",
        "focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "border-primary bg-primary text-primary-foreground" : "bg-transparent",
        className
      )}
      {...props}
    >
      {checked === "indeterminate" ? (
        <Minus aria-hidden="true" className="size-3" />
      ) : checked ? (
        <Check aria-hidden="true" className="size-3" />
      ) : null}
    </button>
  );
}

export { Checkbox };

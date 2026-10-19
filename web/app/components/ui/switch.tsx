"use client";

import type { ComponentProps } from "react";
import { cn } from "../../lib/utils";

// shadcn/ui Switch (new-york) without the @radix-ui/react-switch dependency -
// same markup contract (role="switch", data-state, sized thumb) and token
// styling, controlled only. Used for on/off capabilities where a checkbox
// would read as "select": tools, rules, connectors.
function Switch({
  checked,
  onCheckedChange,
  className,
  disabled,
  ...props
}: Omit<ComponentProps<"button">, "onChange"> & { checked: boolean; onCheckedChange?: (checked: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      data-slot="switch"
      data-state={checked ? "checked" : "unchecked"}
      disabled={disabled}
      onClick={() => onCheckedChange?.(!checked)}
      className={cn(
        "peer relative inline-flex h-[1.15rem] w-8 shrink-0 items-center rounded-full border border-transparent p-0 shadow-xs outline-none transition-all",
        "focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60",
        checked ? "bg-primary" : "bg-switch-off",
        className
      )}
      {...props}
    >
      <span
        data-slot="switch-thumb"
        className={cn(
          "pointer-events-none block size-4 rounded-full bg-background shadow-sm ring-0 transition-transform",
          checked ? "translate-x-[calc(100%-2px)]" : "translate-x-0"
        )}
      />
    </button>
  );
}

export { Switch };

"use client";

import { AlertTriangle, CheckCircle2 } from "lucide-react";

// Small token-driven inline notice — replaces the bespoke .error-box/
// .success-box chrome with a theme-following alert used across this screen.
// Both variants now derive their wash/border/text from a single --state token
// (design contract §3) instead of a hand-picked Tailwind palette ramp, so
// success here is the same green as every other success signal on the screen.
export function Notice({ variant = "error", children }: { variant?: "error" | "success"; children: any }) {
  const Icon = variant === "success" ? CheckCircle2 : AlertTriangle;
  return (
    <div
      role={variant === "success" ? "status" : "alert"}
      className="flex items-start gap-2 rounded-lg border px-3 py-2 text-sm"
      style={
        {
          ["--state" as any]: variant === "success" ? "var(--state-success)" : "var(--state-error)",
          color: "var(--state)",
          background: "color-mix(in srgb, var(--state) 11%, transparent)",
          borderColor: "color-mix(in srgb, var(--state) 38%, transparent)",
        } as any
      }
    >
      <Icon size={15} className="mt-0.5 shrink-0" />
      <span className="min-w-0 flex-1">{children}</span>
    </div>
  );
}

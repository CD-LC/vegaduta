import type { ReactNode } from "react";
import { cn } from "../../lib/utils";

// Token-driven switch (no shadcn Switch primitive exists yet) — replaces the
// bespoke `.ax-toggle` chrome. Keeps role="switch" + aria-checked so the
// control stays reachable and accessible.
export function Toggle({
  on,
  disabled,
  onClick,
  ariaLabel,
}: {
  on: boolean;
  disabled?: boolean;
  onClick?: () => void;
  ariaLabel?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        "relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border border-border outline-none transition-colors focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50",
        on ? "bg-primary" : "bg-secondary"
      )}
    >
      <span
        className={cn(
          "inline-block size-4 rounded-full bg-background shadow-sm transition-transform",
          on ? "translate-x-5" : "translate-x-1"
        )}
      />
    </button>
  );
}

// Semantic inline alerts on the shared --danger-*/--success-* tokens.
export function Alert({ tone, children }: { tone: "error" | "success"; children: ReactNode }) {
  return (
    <div
      role={tone === "error" ? "alert" : "status"}
      className={cn(
        "rounded-lg border px-3 py-2 text-sm",
        tone === "error"
          ? "border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]"
          : "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
      )}
    >
      {children}
    </div>
  );
}

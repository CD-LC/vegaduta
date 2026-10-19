"use client";

import { Shield, ShieldHalf } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import type { Rung } from "./types";

// Trust rung as a SHAPE, not a colour (design-system contract section 4):
// outline shield = under watch, half-filled = trusted, filled = autonomous.
// The label always rides along so the rung never depends on shape alone.
export default function RungBadge({ rung, className, compact = false }: { rung: Rung; className?: string; compact?: boolean }) {
  const t = useTranslations("brief");
  const label = t(`rung.${rung}`);
  const glyph =
    rung === "UNDER_WATCH" ? (
      <Shield className="size-3.5" aria-hidden="true" />
    ) : rung === "TRUSTED" ? (
      <ShieldHalf className="size-3.5" aria-hidden="true" />
    ) : (
      <Shield className="size-3.5" fill="currentColor" aria-hidden="true" />
    );
  return (
    <span
      className={cn("inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-2 py-0.5 text-[0.6875rem] font-medium text-foreground", className)}
      title={label}
      data-rung={rung}
    >
      {glyph}
      <span className={compact ? "sr-only" : undefined}>{label}</span>
    </span>
  );
}

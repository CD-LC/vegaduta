import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Card } from "../../components/ui/card";
import type { ChipTone } from "./model";

export function StatusChip({
  tone,
  icon,
  children,
  className = "",
}: {
  tone: ChipTone;
  icon?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-medium [&>svg]:size-3 [&>svg]:shrink-0 ${className}`}
      style={{
        backgroundColor: `var(--tint-${tone}-bg)`,
        borderColor: `var(--tint-${tone}-line)`,
        color: `var(--tint-${tone}-fg)`,
      }}
    >
      {icon}
      {children}
    </span>
  );
}

// 12px caption eyebrow: Plus Jakarta (inherited), uppercase, muted ink. Used
// for every tile / readout label so the casing is uniform even though the
// i18n strings themselves are mixed ("LLM PROVIDERS" vs "Workflows live").
export function Eyebrow({
  icon,
  children,
  className = "",
}: {
  icon?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`flex items-center gap-1.5 text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground [&>svg]:size-3.5 [&>svg]:shrink-0 ${className}`}
    >
      {icon}
      <span className="truncate">{children}</span>
    </div>
  );
}

// Row-1 stat tile. Numerals are DM Mono (the one place mono is used for
// something other than an id), everything else is body type.
export function StatTile({
  icon,
  label,
  sub,
  children,
  testId,
}: {
  icon: ReactNode;
  label: string;
  sub?: ReactNode;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <Card className="gap-3 p-5" data-testid={testId}>
      <Eyebrow icon={icon}>{label}</Eyebrow>
      <div className="font-mono text-[1.75rem] font-semibold leading-none tabular-nums text-foreground">{children}</div>
      {sub != null && <div className="text-sm text-muted-foreground">{sub}</div>}
    </Card>
  );
}

// Section heading for the list / readout cards: 16px semibold body type.
export function CardHeading({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h2 className="text-base font-semibold leading-tight text-foreground">{children}</h2>
      {action}
    </div>
  );
}

// "HH:MM" for a real ISO timestamp; an empty string for a record without one
// (never "Invalid Date" in the UI).
export function formatTime(iso: string | null | undefined): string {
  const ms = iso ? new Date(iso).getTime() : NaN;
  return Number.isFinite(ms) ? " · " + new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "";
}

export function CardLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="inline-flex shrink-0 items-center gap-1 text-sm font-medium text-primary underline-offset-4 hover:underline [&>svg]:size-3.5"
    >
      {children}
      <ArrowRight aria-hidden="true" />
    </Link>
  );
}

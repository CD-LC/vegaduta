"use client";

import { motion } from "motion/react";
import { Info, Construction } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { Badge } from "../ui/badge";
import { Alert, AlertDescription } from "../ui/alert";
import { useTranslations } from "../../../lib/i18n";

// Shared primitives for the Governance & Operations Console sections
// (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3). Rebuilt on the same shadcn
// token surfaces (Card/Badge/Alert + Tailwind tokens) every other screen in
// this app now uses, so every section composes the same building blocks and
// reads as one product.

export function formatUsd(value: any): string {
  const n = Number(value ?? 0);
  return `$${n.toFixed(n < 1 ? 4 : 2)}`;
}

export function timeAgo(iso: any): string {
  if (!iso) return "-";
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

// Forward counterpart of timeAgo for deadlines/expiry timestamps - a future
// instant fed to timeAgo reads "just now", which would tell an admin a
// throttle expires "just now" when it may have an hour left.
export function timeUntil(iso: any): string {
  if (!iso) return "-";
  const diffMs = new Date(iso).getTime() - Date.now();
  if (diffMs <= 0) return "expired";
  const mins = Math.ceil(diffMs / 60000);
  if (mins < 60) return `in ${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `in ${hours}h`;
  return `in ${Math.floor(hours / 24)}d`;
}

// The short lead paragraph every section page opens with.
export function PageHint({ children }: { children: any }) {
  return (
    <motion.p
      className="mb-5 max-w-3xl text-sm leading-relaxed text-muted-foreground"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      {children}
    </motion.p>
  );
}

// A titled section card - the shadcn Card shell wrapped so pages don't repeat
// the markup, with an entrance animation matching the rest of the app.
export function SectionCard({
  title,
  subtitle,
  icon: Icon,
  actions,
  delay = 0,
  children,
}: {
  title: any;
  subtitle?: any;
  icon?: any;
  actions?: any;
  delay?: number;
  children: any;
}) {
  return (
    <motion.div
      className="mb-5"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay, ease: [0.16, 1, 0.3, 1] }}
    >
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <CardTitle className="flex items-center gap-2 text-sm">
              {Icon && <Icon size={16} className="text-primary" />}
              {title}
            </CardTitle>
            {subtitle && <p className="mt-1 text-xs text-muted-foreground">{subtitle}</p>}
          </div>
          {actions}
        </CardHeader>
        <CardContent className="space-y-2">{children}</CardContent>
      </Card>
    </motion.div>
  );
}

// Labeled "coming soon" panel for sections whose backend is still being built
// in parallel (core-3, per the doc's phased roadmap §9). Deliberately explicit
// about WHAT is coming and WHICH endpoint it will call, so it reads as a
// planned, scoped gap rather than a broken screen. `endpoints` lists the API
// paths this section will consume once they land.
export function ComingSoon({
  title,
  description,
  endpoints,
  roadmap,
}: {
  title: any;
  description: any;
  endpoints?: string[];
  roadmap?: any;
}) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsCommon");
  return (
    <div className="flex items-start gap-3 rounded-lg border border-border bg-secondary/30 p-4">
      <Construction size={18} className="mt-0.5 shrink-0 text-[var(--warning)]" />
      <div className="min-w-0 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2 text-sm font-semibold text-foreground">
          {title}
          <Badge variant="secondary" className="text-2xs uppercase tracking-wide">
            {tHC("s2")}
          </Badge>
        </div>
        <p className="text-xs text-muted-foreground">{description}</p>
        {roadmap && <p className="text-xs text-muted-foreground/80">{roadmap}</p>}
        {endpoints && endpoints.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 pt-0.5">
            <span className="text-[11px] text-muted-foreground">{tHC("s1")}</span>
            {endpoints.map((ep) => (
              <code
                key={ep}
                className="rounded border border-border bg-background px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground"
              >
                {ep}
              </code>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// Small inline info callout (reused for scope/help notes).
export function InfoNote({ children }: { children: any }) {
  return (
    <Alert className="mb-5">
      <Info />
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  );
}

// Token-driven shadcn-style on/off switch - the same control Tenants & Users
// uses inline for its plan-limits toggles, lifted here so every section that
// needs a boolean control shares one implementation instead of re-rolling it.
export function SwitchToggle({
  on,
  onClick,
  label,
  disabled = false,
}: {
  on: boolean;
  onClick: () => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={!!on}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="inline-flex shrink-0 rounded-full outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--ring)] disabled:opacity-50"
    >
      <span
        className={`relative inline-flex h-5 w-9 items-center rounded-full border transition-colors ${
          on ? "border-primary/40 bg-primary" : "border-border bg-muted-foreground/25"
        }`}
      >
        <span
          className={`inline-block size-3.5 rounded-full bg-background shadow-sm transition-transform ${
            on ? "translate-x-4" : "translate-x-0.5"
          }`}
        />
      </span>
    </button>
  );
}

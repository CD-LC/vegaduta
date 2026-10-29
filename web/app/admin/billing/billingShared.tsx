"use client";

import type { ComponentType, SVGProps } from "react";

// Shared constants, helpers and small presentational pieces for the tenant
// admin "Billing & usage" screen (page.tsx). Split out so the page stays a
// composition of focused components.

// Fields the billing page reads off TenantSettingsResponse (core has no
// exported TS type for it yet - see api.ts's untyped getTenantSettings).
export interface TenantBillingSettings {
  plan?: string;
  organizationName?: string;
  maxUsers?: number;
  maxAgents?: number;
  maxWorkflows?: number;
}

export const PLAN_INFO = {
  FREE: { labelKey: "planLabelFree", price: "$0/mo", blurbKey: "planBlurbFree" },
  PREMIUM: { labelKey: "planLabelPremium", price: "$19/mo", blurbKey: "planBlurbPremium" },
  UNLIMITED: { labelKey: "planLabelUnlimited", price: "$79/mo", blurbKey: "planBlurbUnlimited" },
};

// #974 - matches core/audit's IssueCategory enum exactly (labelKey resolves
// display copy via useTranslations; value is what's actually sent to the API).
export const TICKET_CATEGORIES = [
  { value: "BILLING", labelKey: "ticketCategoryBilling" },
  { value: "TECHNICAL", labelKey: "ticketCategoryTechnical" },
  { value: "ACCOUNT", labelKey: "ticketCategoryAccount" },
  { value: "FEATURE_REQUEST", labelKey: "ticketCategoryFeatureRequest" },
  { value: "OTHER", labelKey: "ticketCategoryOther" },
];

// Share of a cap that counts as "near the limit" - at or past this the bar
// turns amber and the inline upgrade button appears next to the usage title.
export const NEAR_LIMIT_RATIO = 0.8;

// Some tier caps come back from the backend as Java's Integer.MAX_VALUE as the
// sentinel for "no cap" rather than null/undefined. Treated as uncapped so
// every row shares one unlimited-cap rule.
const UNCAPPED_SENTINEL = 2147483647;

// Client-side GSTIN shape check (wave-2 D6, GST invoicing). 15 characters:
// 2-digit state code, 5-letter + 4-digit + 1-letter PAN core, entity code,
// 'Z'-slot (any alnum), check character. Input is uppercased before testing.
export const GSTIN_PATTERN = /^\d{2}[A-Z]{5}\d{4}[A-Z]\d[A-Z\d][A-Z]$/;

export function isRealCap(cap: unknown): cap is number {
  return typeof cap === "number" && cap > 0 && cap < UNCAPPED_SENTINEL;
}

export function limitRatio(used: number | null, cap: unknown): number {
  return typeof used === "number" && isRealCap(cap) ? used / cap : 0;
}

export function UsageBar({ label, used, cap }: { label: string; used: number | null; cap?: number | null }) {
  const hasCap = isRealCap(cap);
  const capValue = hasCap && typeof cap === "number" ? cap : null;
  const usedValue = typeof used === "number" ? used : null;
  const pct = capValue !== null && usedValue !== null ? Math.min(100, Math.round((usedValue / capValue) * 100)) : 0;
  const atLimit = capValue !== null && usedValue !== null && usedValue >= capValue;
  const nearLimit = !atLimit && capValue !== null && usedValue !== null && usedValue / capValue >= NEAR_LIMIT_RATIO;
  // Semantic, token-resolved fill: calm accent under 80%, amber approaching
  // the cap, red at/over it.
  const fill = atLimit ? "var(--ax-red)" : nearLimit ? "var(--ax-amber)" : "var(--accent-600)";
  return (
    <div>
      <div className="mb-1.5 flex items-baseline justify-between gap-2 text-[0.8rem]">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-mono text-muted-foreground">
          {(atLimit || nearLimit) && (
            <span className="me-2 font-semibold" style={{ color: fill }}>
              {pct}%
            </span>
          )}
          {usedValue ?? "—"} <span className="text-[var(--text-faint)]">/ {capValue ?? "∞"}</span>
        </span>
      </div>
      {/* Uncapped rows have nothing to measure, so the track is omitted and
       * the infinity readout carries it. */}
      {capValue !== null && (
        <div
          role="progressbar"
          aria-label={label}
          aria-valuemin={0}
          aria-valuemax={capValue}
          aria-valuenow={usedValue !== null ? usedValue : undefined}
          className="h-1.5 overflow-hidden rounded-full bg-[var(--ax-track)]"
        >
          <div
            className="h-full rounded-full transition-[width] duration-200 ease-out"
            style={{ width: `${pct}%`, background: fill }}
          />
        </div>
      )}
    </div>
  );
}

export function ProfileRow({
  icon: Icon,
  title,
  note,
  first = false,
}: {
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  title: string;
  note: string;
  first?: boolean;
}) {
  return (
    <div className={`flex items-start gap-3 px-6 py-4 ${first ? "" : "border-t border-border"}`}>
      <Icon width={15} height={15} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
      <div className="min-w-0">
        <div className="mb-1 font-mono text-[0.68rem] uppercase tracking-[0.1em] text-muted-foreground">{title}</div>
        <p className="m-0 text-[0.8rem] text-muted-foreground">{note}</p>
      </div>
    </div>
  );
}

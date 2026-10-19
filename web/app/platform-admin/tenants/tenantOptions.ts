// Option lists, tone classes and plan-limit field definitions for the
// Tenants & Users console (page.tsx).

import { TONE_BOX, TONE_TEXT } from "../components/admin/AdminKit";

export const SORT_OPTIONS = [
  { value: "spend", label: "30-day spend" },
  { value: "lastActivity", label: "Last activity" },
  { value: "created", label: "Created" },
  { value: "name", label: "Name" },
];

export const STATUS_TONE_CLASS = {
  ok: TONE_BOX.ok,
  warn: TONE_BOX.warn,
  bad: "border-destructive/30 bg-destructive/10 text-destructive",
};

// Spend-cap usage cell colour; blocked/over reads red, never colour alone (the
// figure and a "Blocked" chip carry it too).
export const CAP_TONE_CLASS: Record<string, string> = {
  ok: "",
  warn: TONE_TEXT.warn,
  bad: "font-semibold text-destructive",
  muted: "text-muted-foreground",
};
// TenantPlan enum values (core/security/TenantPlan.java) verbatim.
export const PLAN_OPTIONS = [
  { value: "FREE", label: "Free" },
  { value: "PREMIUM", label: "Premium" },
  { value: "UNLIMITED", label: "Unlimited" },
];

// DataResidencyRegion enum values (core/security/DataResidencyRegion.java)
// verbatim - declared jurisdiction, informational only (no data moves).
export const RESIDENCY_OPTIONS = [
  { value: "UNSPECIFIED", label: "Unspecified" },
  { value: "INDIA", label: "India" },
  { value: "EU", label: "EU" },
  { value: "US", label: "US" },
  { value: "OTHER", label: "Other" },
];

export const LIMIT_NUMBER_FIELDS = [
  { key: "maxUsers", label: "Max users" },
  { key: "maxAgents", label: "Max agents" },
  { key: "maxWorkflows", label: "Max workflows" },
  { key: "maxKbCollections", label: "Max knowledge-base collections" },
];
export const LIMIT_TOGGLE_FIELDS = [
  { key: "byokAllowed", label: "BYOK (bring your own provider key)" },
  { key: "whatsappAllowed", label: "WhatsApp channel" },
  { key: "whiteLabel", label: "White-label" },
  { key: "liveVisionAllowed", label: "Live vision (VegaDūta Sight watch/live)" },
];

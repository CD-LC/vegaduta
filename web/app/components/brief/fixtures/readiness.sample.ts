// Sample readiness rows and envoy list.
// Shapes follow the readiness row in EARNED-AUTONOMY-CONTRACT.md section 2.
// TEST-ONLY fixtures (vitest); nothing in app code imports this.
import type { BriefListItem, ReadinessRow } from "../types";

export const SAMPLE_READINESS: ReadinessRow[] = [
  {
    kind: "SECRET",
    ref: "s1",
    label: "TALLY_API_KEY",
    state: "MISSING",
    reason: "No value is stored yet. The invoice fetch step cannot run without it.",
    actionHref: "/secrets?tour=field:secret-add&name=TALLY_API_KEY",
  },
  {
    kind: "SECRET",
    ref: "s2",
    label: "WHATSAPP_TOKEN",
    state: "WAITING_ON_ADMIN",
    reason: "Tenant secrets need an admin with integration permission. Everything else can be applied without it.",
  },
  {
    kind: "KNOWLEDGE",
    ref: "k1",
    label: "Customer ledger notes and payment terms",
    state: "MISSING",
    reason: "Nothing uploaded yet. The agents will answer without your payment terms until you add them.",
    actionHref: "/knowledge?tour=field:knowledge-toggle",
  },
  {
    kind: "CONNECTOR",
    ref: "m1",
    label: "WhatsApp Business",
    state: "GATED",
    reason: "Connecting an MCP server is not part of your current plan.",
    unlocksAt: "PREMIUM",
  },
  {
    kind: "CHANNEL",
    ref: "c1",
    label: "WHATSAPP",
    state: "SIMULATED",
    reason: "Not connected, so replies are rehearsed and never leave the platform.",
  },
  {
    kind: "PLAN_LIMIT",
    ref: "w1",
    label: "Workflows",
    state: "READY",
    reason: "One workflow fits within your plan.",
  },
];

export const SAMPLE_ENVOYS: BriefListItem[] = [
  {
    id: "sample-1",
    title: "Unpaid-invoice follow-up and order-status desk",
    rung: "UNDER_WATCH",
    status: "APPLIED",
    baseline: null,
    lastDelta: null,
    readinessSummary: { ready: 1, missing: 2, waitingOnAdmin: 1, gated: 1, simulated: 1 },
  },
];

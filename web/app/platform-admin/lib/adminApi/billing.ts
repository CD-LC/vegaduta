import { apiFetch, toJson } from "../api";

// READ-ONLY platform billing view (PlatformBillingController,
// /api/platform/billing/**). GET endpoints only - there are deliberately no
// refund, charge, cancel or plan-change wrappers here. All money is minor
// units (paise/cents), reported per currency and never summed across them.

export type BillingState = "FREE" | "PAID" | "COMP" | "PAST_DUE" | "CANCELING" | "ADMIN_OVERRIDE";

export interface CurrencyTotal {
  currency: string;
  invoices: number;
  amountMinor: number;
  gstMinor: number;
  invoicesLast30d: number;
  amountMinorLast30d: number;
}

export interface BillingSummary {
  liveTenants: number;
  tenantsByPlan: Record<string, number>;
  tenantsBySubscriptionStatus: Record<string, number>;
  activeCompGrants: number;
  compGrantsExpiringWithin7d: number;
  compGrantsOverdueForRevert: number;
  pastDue: number;
  cancelScheduled: number;
  pendingUpgrade: number;
  invoiceTotals: CurrencyTotal[];
}

export interface SubscriptionRow {
  tenantId: string;
  name: string;
  plan: string;
  state: BillingState;
  paymentGateway: string | null;
  gatewaySubscriptionId: string | null;
  subscriptionStatus: string | null;
  trialEndsAt: string | null;
  currentPeriodEnd: string | null;
  pastDueSince: string | null;
  cancelScheduled: boolean;
  pendingPlan: string | null;
  compExpiresAt: string | null;
  compGrantedAt: string | null;
  compReason: string | null;
  planBeforeComp: string | null;
  createdAt: string | null;
}

export interface SubscriptionList {
  total: number;
  scanTruncated: boolean;
  subscriptions: SubscriptionRow[];
}

export interface CompGrantRow {
  tenantId: string;
  name: string;
  plan: string;
  planBeforeComp: string | null;
  grantedAt: string | null;
  expiresAt: string | null;
  reason: string | null;
  overdue: boolean;
}

export interface InvoiceRow {
  id: string;
  tenantId: string;
  tenantName: string | null;
  invoiceNumber: string;
  gateway: string | null;
  gatewayPaymentId: string | null;
  plan: string | null;
  period: string | null;
  currency: string | null;
  amountMinor: number;
  gstAmountMinor: number;
  issuedAt: string | null;
}

export const billingApi = {
  summary: (): Promise<BillingSummary> => apiFetch("/api/platform/billing/summary").then(toJson),

  subscriptions: (params: { plan?: string; state?: string; q?: string; limit?: number } = {}): Promise<SubscriptionList> => {
    const qs = new URLSearchParams();
    if (params.plan) qs.set("plan", params.plan);
    if (params.state) qs.set("state", params.state);
    if (params.q) qs.set("q", params.q);
    if (params.limit) qs.set("limit", String(params.limit));
    return apiFetch(`/api/platform/billing/subscriptions${qs.toString() ? `?${qs}` : ""}`).then(toJson);
  },

  compGrants: (limit = 200): Promise<{ total: number; grants: CompGrantRow[] }> =>
    apiFetch(`/api/platform/billing/comp-grants?limit=${limit}`).then(toJson),

  invoices: (params: { tenantId?: string; limit?: number } = {}): Promise<{ count: number; invoices: InvoiceRow[] }> => {
    const qs = new URLSearchParams();
    if (params.tenantId) qs.set("tenantId", params.tenantId);
    if (params.limit) qs.set("limit", String(params.limit));
    return apiFetch(`/api/platform/billing/invoices${qs.toString() ? `?${qs}` : ""}`).then(toJson);
  },
};

/** Minor units to a display string in the invoice's own currency, e.g. 118000 INR -> "INR 1,180.00". */
export function formatMinor(amountMinor: number, currency: string | null): string {
  const value = (amountMinor || 0) / 100;
  return `${currency || "?"} ${value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

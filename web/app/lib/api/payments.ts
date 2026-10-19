import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";

// E1 Agentic UPI Payments - tenant-facing API (core/payments
// PaymentController). All amounts are integer PAISE end to end - the UI
// converts to rupees only for display, never for transport. Every call is
// quiet on 403/404/405 so the surfaces degrade to "not enabled" against an
// older core, same posture the billing reads above use.

export type MandateScope = "COLLECT" | "PAY" | "BOTH";
export type MandateApprovalPolicy = "ALWAYS" | "ABOVE_THRESHOLD" | "NEVER";
export type MandateStatus = "ACTIVE" | "REVOKED" | "EXPIRED" | "SUSPENDED";
export type PaymentTransactionStatus =
  | "INITIATED"
  | "PENDING_APPROVAL"
  | "AWAITING_PAYMENT"
  | "CAPTURED"
  | "FAILED"
  | "EXPIRED"
  | "REFUNDED";

export interface CreateMandateRequest {
  agentId: string;
  scope: MandateScope;
  maxPerTxnPaise: number;
  maxDailyPaise: number;
  maxMonthlyPaise: number;
  allowedVpas?: string[] | null;
  allowedMerchantCategories?: string[] | null;
  approvalPolicy: MandateApprovalPolicy;
  approvalThresholdPaise?: number | null;
  validFrom: string;
  validUntil: string;
}

export interface PaymentMandate {
  id: string;
  agentId: string;
  grantedByUserId: string;
  scope: MandateScope;
  maxPerTxnPaise: number;
  maxDailyPaise: number;
  maxMonthlyPaise: number;
  allowedVpas: string[];
  allowedMerchantCategories: string[];
  approvalPolicy: MandateApprovalPolicy;
  approvalThresholdPaise?: number | null;
  validFrom: string;
  validUntil: string;
  status: MandateStatus;
  canonicalHashSha256: string;
  createdAt: string;
  revokedAt?: string | null;
  revokedBy?: string | null;
  revokeReason?: string | null;
}

export interface MandatePreview {
  canonicalJson: string;
  canonicalHashSha256: string;
}

export interface MandateSpend {
  mandateId: string;
  maxPerTxnPaise: number;
  maxDailyPaise: number;
  maxMonthlyPaise: number;
  dailySpendPaise: number;
  monthlySpendPaise: number;
}

export interface PaymentTransaction {
  id: string;
  mandateId: string;
  agentId: string;
  sessionId?: string | null;
  type: "PAYMENT_LINK" | "UPI_COLLECT" | "PAYOUT";
  amountPaise: number;
  currency: string;
  vpa?: string | null;
  description?: string | null;
  merchantCategory?: string | null;
  status: PaymentTransactionStatus;
  approvalId?: string | null;
  shortUrl?: string | null;
  receiptUrl?: string | null;
  gatewayPaymentId?: string | null;
  failureReason?: string | null;
  expiresAt?: string | null;
  paidAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

// ---- display/input helpers (pure - unit-tested in payments.test.ts) ----

const inrFmt = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 2,
});

// paise -> "₹5,000.00" for display; em-dash for absent, never "₹0.00".
export function fmtPaise(paise: number | null | undefined): string {
  return paise == null ? "—" : inrFmt.format(paise / 100);
}

/** Rupee text input -> paise. Returns null when the box is empty or invalid. */
export function rupeesToPaise(raw: string): number | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.round(n * 100);
}

/** Meter tone: >=100% of cap is down, >=80% warn, below ok. */
export function spendTone(spent: number, cap: number): "ok" | "warn" | "down" {
  if (cap <= 0) return "ok";
  const ratio = spent / cap;
  if (ratio >= 1) return "down";
  if (ratio >= 0.8) return "warn";
  return "ok";
}

export const paymentsApi = {
  // The grant wizard's review step - the canonical AP2-shaped JSON + its
  // SHA-256, before anything is persisted or signed.
  previewMandate: (req: CreateMandateRequest): Promise<MandatePreview> =>
    apiFetch(`/api/payments/mandates/preview`, { method: "POST", ...jsonBody(req) }).then(toJson<MandatePreview>),

  grantMandate: (req: CreateMandateRequest): Promise<PaymentMandate> =>
    apiFetch(`/api/payments/mandates`, { method: "POST", ...jsonBody(req) }).then(toJson<PaymentMandate>),

  listMandates: (agentId: string): Promise<PaymentMandate[]> =>
    apiFetch(`/api/payments/mandates?agentId=${encodeURIComponent(agentId)}`).then(
      toJsonQuietOn([403, 404, 405])),

  getMandate: (id: string): Promise<PaymentMandate> =>
    apiFetch(`/api/payments/mandates/${encodeURIComponent(id)}`).then(toJsonQuietOn([403, 404, 405])),

  revokeMandate: (id: string, reason?: string): Promise<PaymentMandate> =>
    apiFetch(`/api/payments/mandates/${encodeURIComponent(id)}/revoke`, {
      method: "POST",
      ...jsonBody({ reason: reason ?? null }),
    }).then(toJson<PaymentMandate>),

  getMandateSpend: (id: string): Promise<MandateSpend> =>
    apiFetch(`/api/payments/mandates/${encodeURIComponent(id)}/spend`).then(
      toJsonQuietOn([403, 404, 405])),

  listPaymentTransactions: (agentId: string, opts?: { mandateId?: string; limit?: number }): Promise<PaymentTransaction[]> =>
    apiFetch(
      `/api/payments/transactions?agentId=${encodeURIComponent(agentId)}`
        + (opts?.mandateId ? `&mandateId=${encodeURIComponent(opts.mandateId)}` : "")
        + `&limit=${opts?.limit ?? 50}`).then(toJsonQuietOn([403, 404, 405])),

  getPaymentTransaction: (id: string): Promise<PaymentTransaction> =>
    apiFetch(`/api/payments/transactions/${encodeURIComponent(id)}`).then(toJsonQuietOn([403, 404, 405])),
};

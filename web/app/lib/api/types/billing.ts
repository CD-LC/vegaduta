// GET /api/tenant/referral - give-a-month-get-a-month referral program.
export interface TenantReferralResponse {
  code: string;
  redemptionsCount: number;
  redeemedByMe: boolean;
  canRedeem: boolean;
}

// GET /api/tenant/subscription - plan + lifecycle state for the
// subscription-management card (cancel-at-cycle-end, dunning banner).
export interface TenantSubscriptionResponse {
  plan: string;
  subscriptionStatus?: string | null;
  cancelScheduled?: boolean;
  pastDue?: boolean;
  // Site-wide "try Unlimited free for 2 weeks" promo (2026-08-14) and any
  // future complimentary plan grant (scholarship/trial/referral) reuse the
  // same two fields - compPlanReason is free text (matched below against
  // the known promo reason, not rendered raw), null whenever there's no
  // live comp grant.
  compPlanReason?: string | null;
  compPlanExpiresAt?: string | null;
}

// GET /api/tenant/invoices - one row per issued tax invoice. Deliberately
// loose: date/number field names are probed across the likely spellings
// (see AdminBillingPage's invoice row rendering) until the backend contract
// is final.
export interface TenantInvoiceSummary {
  id: string;
  number?: string | null;
  invoiceNumber?: string | null;
  date?: string | null;
  invoiceDate?: string | null;
  createdAt?: string | null;
  amount?: number | string | null;
  currency?: string | null;
  status?: string | null;
  [key: string]: unknown;
}

// PUT /api/tenant/billing-profile - GSTIN/name/address printed on invoices.
export interface TenantBillingProfileUpdate {
  gstin?: string;
  billingName?: string;
  billingAddress?: string;
}

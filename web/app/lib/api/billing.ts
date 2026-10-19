import { apiFetch, friendlyStatusFallback, toJson, toJsonQuietOn, jsonBody, type ApiError } from "./core";
import type { GatewayList } from "../payments/types";
import type { TenantSpendCap } from "../spendCap";
import type {
  TenantReferralResponse,
  TenantSubscriptionResponse,
  TenantInvoiceSummary,
  TenantBillingProfileUpdate,
} from "./types";

export const billingApi = {
  // Monthly hosted-LLM spend cap, read-only for tenant admins (set by a platform admin). 403 (not an admin),
  // 404 (no cap feature / older core) are expected states - the /usage card simply hides, no toast.
  getTenantSpendCap: (): Promise<TenantSpendCap | null> =>
    apiFetch(`/api/tenant/spend-cap`).then(toJsonQuietOn([401, 403, 404])),

  // Which payment gateways this deployment has, whether each is configured, its public key, checkout mode and
  // capabilities (core PaymentCheckoutController). The console picks its checkout from this, never from a name.
  // Optional probe: an older core has no such route (404) and a member without billing rights gets 403 - both are
  // expected states, so no error toast (the generic toJson would pop "No endpoint matches this request." at the user).
  listPaymentGateways: () => apiFetch(`/api/billing/gateways`).then(toJsonQuietOn<GatewayList>([403, 404])),

  // Currencies/periods that can really be billed on this deployment (core TenantUpgradeController#options).
  // Same optional-probe rule as listPaymentGateways: silent on an older core (404) or a forbidden member (403).
  getUpgradeOptions: () => apiFetch(`/api/tenant/upgrade/options`).then(toJsonQuietOn([403, 404])),

  // One-off payments through ANY gateway (core PaymentCheckoutController). `gateway` is optional: blank = the
  // deployment's default. The browser's relative fetch used to be used here, which never reached the API origin
  // or carried the auth token - apiFetch does both.
  createCheckoutOrder: (body: { amountInSubunits: number; currency: string; receipt?: string; gateway?: string }) =>
    apiFetch(`/api/billing/checkout/create-order`, { method: "POST", ...jsonBody(body) }).then(toJson),
  verifyCheckoutPayment: (body: { gateway?: string; orderId: string; paymentId: string; signature: string }) =>
    apiFetch(`/api/billing/checkout/verify-payment`, { method: "POST", ...jsonBody(body) }).then(toJson),

  // LLM-gateway content policy (core/chat LlmGatewayContentPolicyController):
  // per-tenant PII redaction + prompt-injection screening, both OFF by default.
  // GET is readable by any member; PUT needs MANAGE_GUARDRAILS and requires
  // EVERY field (a partial body is a 400), so send the whole policy.
  getLlmGatewayContentPolicy: () => apiFetch(`/api/tenant/llm-gateway/content-policy`).then(toJson),
  updateLlmGatewayContentPolicy: (policy: unknown) =>
    apiFetch(`/api/tenant/llm-gateway/content-policy`, {
      method: "PUT",
      ...jsonBody(policy),
    }).then(toJson),

  // #811 - real self-serve plan upgrade, backed by TenantUpgradeController.
  // Returns a Razorpay subscription id + public key for the browser to open
  // Standard Checkout with (same client-side pattern signup/page.js already
  // uses) - the response is a UX handoff only, not confirmation the upgrade
  // took effect (that only happens once /webhook/razorpay fires).
  // Pricing/monetization pass 2026-07-31 (audit §5.1): the payload now also
  // carries currency ("INR"|"USD", from the same region signal the pricing
  // page uses to decide ₹ vs $ display), period ("MONTHLY"|"ANNUAL") and an
  // optional couponCode. All three are OPTIONAL on the backend contract
  // (defaults INR/MONTHLY) - an older core that doesn't know these fields
  // just ignores the extra JSON keys, so this stays backward-compatible.
  initiateTenantUpgrade: (
    targetPlan: string,
    opts?: { currency?: "INR" | "USD"; period?: "MONTHLY" | "ANNUAL"; couponCode?: string }
  ) =>
    apiFetch(`/api/tenant/upgrade`, {
      method: "POST",
      ...jsonBody({
        targetPlan,
        ...(opts?.currency ? { currency: opts.currency } : {}),
        ...(opts?.period ? { period: opts.period } : {}),
        ...(opts?.couponCode ? { couponCode: opts.couponCode } : {}),
      }),
    }).then(
      toJson<{
        targetPlan: string;
        gatewaySubscriptionId: string | null;
        gatewayPublicKey: string | null;
        checkoutUrl: string | null;
      }>
    ),

  // Event-triggered 7-day Premium trial (audit §5.4) - backed by the trial
  // endpoints being added in a parallel core session. Both calls are coded
  // defensively: the TrialOfferButton caller treats ANY failure on status
  // (incl. a 404 from a core that doesn't have the route yet) as "don't show
  // the offer", so this degrades to a no-op against an older backend.
  getTrialStatus: (): Promise<{ eligible: boolean; claimed: boolean; expiresAt?: string }> =>
    apiFetch(`/api/tenant/trial/status`).then(toJson<{ eligible: boolean; claimed: boolean; expiresAt?: string }>),
  claimTrial: (trigger: string): Promise<{ granted: boolean; plan?: string; expiresAt?: string }> =>
    apiFetch(`/api/tenant/trial/claim`, {
      method: "POST",
      ...jsonBody({ trigger }),
    }).then(toJson<{ granted: boolean; plan?: string; expiresAt?: string }>),

  // Wave-2 monetization surfaces (audit D3/D5/D6) - see the interface block
  // near the top of this file for the contract + the defensive-coding rule.
  // Every read here is quiet on 403/404/405 so the billing page can hide a
  // section against an older core without a global toast; the throw still
  // carries .status for the caller's catch.
  getTenantReferral: (): Promise<TenantReferralResponse> =>
    apiFetch(`/api/tenant/referral`).then(toJsonQuietOn([403, 404, 405])),
  // Redeem is a user-initiated action, so failures DO surface - but the 409
  // ("already redeemed" / "own code" / "not eligible") body carries its
  // detail in `reason` per the contract, not the GlobalExceptionHandler's
  // usual `error` key, so toJson's body.error read would drop it. Handled
  // inline: reason -> error -> status fallback, no global toast (the billing
  // page shows its own toast with the reason text).
  redeemReferralCode: (code: string): Promise<{ granted: boolean; plan?: string; expiresAt?: string }> =>
    apiFetch(`/api/tenant/referral/redeem`, {
      method: "POST",
      ...jsonBody({ code }),
    }).then(async (res) => {
      if (!res.ok) {
        let body: { reason?: string; error?: string } = {};
        try {
          body = (await res.json()) ?? {};
        } catch {
          /* non-JSON body - fall through to the status fallback */
        }
        const err: ApiError = new Error(body.reason || body.error || friendlyStatusFallback(res.status));
        err.status = res.status;
        throw err;
      }
      const text = await res.text();
      return text ? JSON.parse(text) : null;
    }),
  getTenantSubscription: (): Promise<TenantSubscriptionResponse> =>
    apiFetch(`/api/tenant/subscription`).then(toJsonQuietOn([403, 404, 405])),
  // Schedules a cancel at the end of the current cycle (data kept, plan
  // reverts to Free) - never an immediate teardown. Loud on failure (toJson):
  // the user explicitly clicked Confirm in the dialog.
  cancelTenantSubscription: () => apiFetch(`/api/tenant/subscription/cancel`, { method: "POST" }).then(toJson),
  getTenantInvoices: (): Promise<TenantInvoiceSummary[] | { invoices?: TenantInvoiceSummary[] }> =>
    apiFetch(`/api/tenant/invoices`).then(toJsonQuietOn([403, 404, 405])),
  // The invoice render endpoint returns text/html, not JSON - fetched here
  // (so the bearer token rides along; a plain window.open() would arrive
  // unauthenticated) and opened by the caller as a blob URL in a new tab.
  getTenantInvoiceHtml: async (invoiceId: string): Promise<string> => {
    const res = await apiFetch(`/api/tenant/invoices/${encodeURIComponent(invoiceId)}/html`);
    if (!res.ok) {
      const err: ApiError = new Error(friendlyStatusFallback(res.status));
      err.status = res.status;
      throw err;
    }
    return res.text();
  },
  updateTenantBillingProfile: (profile: TenantBillingProfileUpdate) =>
    apiFetch(`/api/tenant/billing-profile`, {
      method: "PUT",
      ...jsonBody(profile),
    }).then(toJson),
};

// Unit coverage for the Agentic UPI Payments API surface (Build Specs §E1):
// the helpers the console leans on (paise <-> rupee, cap-meter tone) and the
// request shapes each client method sends to PaymentController. apiFetch is
// mocked at the seam - no network.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock("./core", () => ({
  apiFetch: apiFetchMock,
  toJson: async (res: any) => res.json(),
  toJsonQuietOn: () => async (res: any) => res.json(),
  jsonBody: (body: any) => ({
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }),
}));

import { fmtPaise, paymentsApi, rupeesToPaise, spendTone } from "./payments";
import { paymentContext } from "../../components/ApprovalsPanel";

function okJson(body: any) {
  return Promise.resolve({ ok: true, json: async () => body, text: async () => JSON.stringify(body) } as any);
}

describe("rupeesToPaise", () => {
  it("converts decimal rupees to integer paise", () => {
    expect(rupeesToPaise("500")).toBe(50000);
    expect(rupeesToPaise("500.50")).toBe(50050);
    expect(rupeesToPaise("0.01")).toBe(1);
  });

  it("rejects blank, non-numeric and non-positive input", () => {
    expect(rupeesToPaise("")).toBeNull();
    expect(rupeesToPaise("   ")).toBeNull();
    expect(rupeesToPaise("abc")).toBeNull();
    expect(rupeesToPaise("0")).toBeNull();
    expect(rupeesToPaise("-5")).toBeNull();
  });
});

describe("spendTone", () => {
  it("warns at 80% and goes down at the cap", () => {
    expect(spendTone(0, 1000)).toBe("ok");
    expect(spendTone(799, 1000)).toBe("ok");
    expect(spendTone(800, 1000)).toBe("warn");
    expect(spendTone(1000, 1000)).toBe("down");
    expect(spendTone(1500, 1000)).toBe("down");
  });

  it("treats a zero/absent cap as ok rather than dividing by zero", () => {
    expect(spendTone(100, 0)).toBe("ok");
  });
});

describe("fmtPaise", () => {
  it("renders paise as INR and an em-dash for absent data", () => {
    expect(fmtPaise(500050)).toContain("5,000.50");
    expect(fmtPaise(null)).toBe("—");
    expect(fmtPaise(undefined)).toBe("—");
  });
});

describe("paymentsApi request shapes", () => {
  beforeEach(() => apiFetchMock.mockReset());

  const req = {
    agentId: "agent-1",
    scope: "BOTH" as const,
    maxPerTxnPaise: 50000,
    maxDailyPaise: 500000,
    maxMonthlyPaise: 5000000,
    allowedVpas: ["vendor@bank"],
    allowedMerchantCategories: null as string[] | null,
    approvalPolicy: "ABOVE_THRESHOLD" as const,
    approvalThresholdPaise: 100000,
    validFrom: "2026-09-26T00:00:00Z",
    validUntil: "2027-09-26T00:00:00Z",
  };

  it("previewMandate POSTs to /api/payments/mandates/preview", async () => {
    apiFetchMock.mockReturnValue(okJson({ canonicalJson: "{}", canonicalHashSha256: "ab" }));
    await paymentsApi.previewMandate(req);
    const [url, init] = apiFetchMock.mock.calls[0];
    expect(url).toBe("/api/payments/mandates/preview");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body).agentId).toBe("agent-1");
    expect(JSON.parse(init.body).approvalThresholdPaise).toBe(100000);
  });

  it("grantMandate POSTs the same body to /api/payments/mandates", async () => {
    apiFetchMock.mockReturnValue(okJson({}));
    await paymentsApi.grantMandate(req);
    const [url, init] = apiFetchMock.mock.calls[0];
    expect(url).toBe("/api/payments/mandates");
    expect(init.method).toBe("POST");
  });

  it("listMandates always carries agentId", async () => {
    apiFetchMock.mockReturnValue(okJson([]));
    await paymentsApi.listMandates("agent-9");
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/payments/mandates?agentId=agent-9");
  });

  it("revokeMandate POSTs a reason body to the :revoke subresource", async () => {
    apiFetchMock.mockReturnValue(okJson({}));
    await paymentsApi.revokeMandate("m-1", "no longer needed");
    const [url, init] = apiFetchMock.mock.calls[0];
    expect(url).toBe("/api/payments/mandates/m-1/revoke");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body).reason).toBe("no longer needed");
  });

  it("listPaymentTransactions builds agentId + optional mandateId + capped limit", async () => {
    apiFetchMock.mockReturnValue(okJson([]));
    await paymentsApi.listPaymentTransactions("agent-1", { mandateId: "m-7", limit: 25 });
    expect(apiFetchMock.mock.calls[0][0]).toBe(
      "/api/payments/transactions?agentId=agent-1&mandateId=m-7&limit=25");
    await paymentsApi.listPaymentTransactions("agent-1");
    expect(apiFetchMock.mock.calls[1][0]).toBe("/api/payments/transactions?agentId=agent-1&limit=50");
  });
});

describe("paymentContext (approvals card)", () => {
  const real = JSON.stringify({
    transactionId: "t-1",
    mandateId: "m-1",
    agentId: "a-1",
    type: "UPI_COLLECT",
    amountPaise: 500000,
    currency: "INR",
    payeeVpa: "vendor@bank",
    mandate: { scope: "BOTH", maxPerTxnPaise: 50000, maxDailyPaise: 500000, maxMonthlyPaise: 5000000 },
  });

  it("recognises a real upi.mandate.execute payload", () => {
    const ctx = paymentContext(real);
    expect(ctx.amountPaise).toBe(500000);
    expect(ctx.payeeVpa).toBe("vendor@bank");
    expect(ctx.mandate.scope).toBe("BOTH");
  });

  it("ignores non-payment payloads and malformed JSON", () => {
    expect(paymentContext(null)).toBeNull();
    expect(paymentContext("not json")).toBeNull();
    expect(paymentContext(JSON.stringify({ action: "x" }))).toBeNull();
    expect(paymentContext(JSON.stringify({ amountPaise: "not-a-number", mandate: {} }))).toBeNull();
  });
});

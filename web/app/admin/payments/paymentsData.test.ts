import { describe, expect, it, vi, beforeEach } from "vitest";

const apiFetchMock = vi.fn();
const getSpend = vi.fn();
vi.mock("../../lib/api/core", () => ({
  apiFetch: (...a: unknown[]) => apiFetchMock(...a),
  toJsonQuietOn: () => async (res: Response) => {
    if (!res.ok) throw Object.assign(new Error("nope"), { status: res.status });
    return res.json();
  },
}));
vi.mock("../../lib/api", () => ({ api: { getMandateSpend: (...a: unknown[]) => getSpend(...a) } }));

import { loadMandateSpends } from "./paymentsData";

const m = (id: string, status = "ACTIVE") => ({ id, status }) as never;
const spend = (id: string) => ({ mandateId: id, dailySpendPaise: 1 }) as never;

describe("loadMandateSpends", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    getSpend.mockReset();
  });

  it("uses one batch call when the core has it", async () => {
    apiFetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => [spend("a"), spend("b")] });
    const out = await loadMandateSpends("agent", [m("a"), m("b"), m("c", "REVOKED")]);
    expect(Object.keys(out)).toEqual(["a", "b"]);
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/payments/mandates/spend?agentId=agent");
    expect(getSpend).not.toHaveBeenCalled();
  });

  it("falls back to per-mandate calls on an older core", async () => {
    apiFetchMock.mockResolvedValue({ ok: false, status: 404, json: async () => ({}) });
    getSpend.mockImplementation(async (id: string) => spend(id));
    const out = await loadMandateSpends("agent", [m("a"), m("b")]);
    expect(Object.keys(out).sort()).toEqual(["a", "b"]);
    expect(getSpend).toHaveBeenCalledTimes(2);
  });
});

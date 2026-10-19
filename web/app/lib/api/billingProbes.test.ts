import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
const toastError = vi.fn();
vi.mock("./core", async () => {
  const actual = await vi.importActual<typeof import("./core")>("./core");
  return { ...actual, apiFetch: (...a: unknown[]) => apiFetchMock(...a) };
});
vi.mock("../toastBus", () => ({ toast: { error: (...a: unknown[]) => toastError(...a), success: vi.fn(), info: vi.fn() } }));

import { billingApi } from "./billing";

const res = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("optional billing probes", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    toastError.mockReset();
  });

  it("an older core without the route (404) fails quietly - no 'No endpoint matches this request.' toast", async () => {
    apiFetchMock.mockResolvedValue(res(404, { error: "No endpoint matches this request." }));
    await expect(billingApi.getUpgradeOptions()).rejects.toMatchObject({ status: 404 });
    await expect(billingApi.listPaymentGateways()).rejects.toMatchObject({ status: 404 });
    expect(toastError).not.toHaveBeenCalled();
  });

  it("a forbidden member (403) is quiet too", async () => {
    apiFetchMock.mockResolvedValue(res(403, { error: "Forbidden" }));
    await expect(billingApi.getUpgradeOptions()).rejects.toMatchObject({ status: 403 });
    expect(toastError).not.toHaveBeenCalled();
  });

  it("a real server error is still reported", async () => {
    apiFetchMock.mockResolvedValue(res(500, { error: "boom" }));
    await expect(billingApi.getUpgradeOptions()).rejects.toMatchObject({ status: 500 });
    expect(toastError).toHaveBeenCalled();
  });

  it("returns the payload on success", async () => {
    apiFetchMock.mockResolvedValue(res(200, { currencies: ["INR"] }));
    await expect(billingApi.getUpgradeOptions()).resolves.toEqual({ currencies: ["INR"] });
  });
});

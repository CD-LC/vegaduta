import { beforeEach, describe, expect, it, vi } from "vitest";

const getEffective = vi.fn();
vi.mock("./settings", () => ({ settingsApi: { getEffective: (...a: unknown[]) => getEffective(...a) } }));

import { APPROVAL_EXPIRY_KEY, approvalsQueueApi } from "./approvalsQueue";

beforeEach(() => {
  getEffective.mockReset();
  approvalsQueueApi.resetExpiryTtlCache();
});

describe("approvalsQueueApi.getExpiryTtlSeconds", () => {
  it("reads the effective approvals.expiry.ttl through the settings client, once", async () => {
    getEffective.mockResolvedValue({ values: { [APPROVAL_EXPIRY_KEY]: { value: "PT24H", source: "tenant", locked: false } } });
    expect(await approvalsQueueApi.getExpiryTtlSeconds()).toBe(86_400);
    expect(await approvalsQueueApi.getExpiryTtlSeconds()).toBe(86_400);
    expect(getEffective).toHaveBeenCalledTimes(1);
  });

  it("is 0 (never expires) for a zero TTL", async () => {
    getEffective.mockResolvedValue({ values: { [APPROVAL_EXPIRY_KEY]: { value: "PT0S", source: "default", locked: false } } });
    expect(await approvalsQueueApi.getExpiryTtlSeconds()).toBe(0);
  });

  it("is unknown (null), and asked again next time, when the catalog is missing or the read fails", async () => {
    getEffective.mockImplementationOnce(() => Promise.reject(new Error("404")));
    expect(await approvalsQueueApi.getExpiryTtlSeconds()).toBeNull();
    await Promise.resolve();
    getEffective.mockResolvedValueOnce({ values: {} });
    expect(await approvalsQueueApi.getExpiryTtlSeconds()).toBeNull();
    expect(getEffective).toHaveBeenCalledTimes(2);
  });
});

import { afterEach, describe, expect, it } from "vitest";
import { resetSharedPollsForTests, sharedFetch } from "./sharedPoll";

afterEach(() => resetSharedPollsForTests());

describe("sharedFetch", () => {
  it("shares one in-flight request between concurrent callers", async () => {
    let calls = 0;
    const fn = async () => {
      calls++;
      return "v";
    };
    const [a, b, c] = await Promise.all([sharedFetch("k", fn, 1000), sharedFetch("k", fn, 1000), sharedFetch("k", fn, 1000)]);
    expect([a, b, c]).toEqual(["v", "v", "v"]);
    expect(calls).toBe(1);
  });

  it("serves the finished value inside the ttl and refetches when forced", async () => {
    let calls = 0;
    const fn = async () => ++calls;
    expect(await sharedFetch("k", fn, 60_000)).toBe(1);
    expect(await sharedFetch("k", fn, 60_000)).toBe(1);
    expect(await sharedFetch("k", fn, 60_000, { force: true })).toBe(2);
  });

  it("refetches once the ttl has passed", async () => {
    let calls = 0;
    const fn = async () => ++calls;
    expect(await sharedFetch("k", fn, 0)).toBe(1);
    expect(await sharedFetch("k", fn, 0)).toBe(2);
  });

  it("does not cache a rejection", async () => {
    let calls = 0;
    const fn = async () => {
      calls++;
      if (calls === 1) throw new Error("boom");
      return "ok";
    };
    await expect(sharedFetch("k", fn, 60_000)).rejects.toThrow("boom");
    expect(await sharedFetch("k", fn, 60_000)).toBe("ok");
  });

  it("keys are independent", async () => {
    let calls = 0;
    const fn = async () => ++calls;
    await sharedFetch("a", fn, 60_000);
    await sharedFetch("b", fn, 60_000);
    expect(calls).toBe(2);
  });
});

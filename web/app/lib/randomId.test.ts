import { afterEach, describe, expect, it, vi } from "vitest";
import { randomUuid } from "./randomId";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("randomUuid", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("returns a v4 UUID when crypto.randomUUID is missing (plain-http origin)", () => {
    vi.stubGlobal("crypto", { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) });
    expect(randomUuid()).toMatch(UUID);
  });

  it("still returns a v4 UUID when crypto is entirely unavailable", () => {
    vi.stubGlobal("crypto", undefined);
    const a = randomUuid();
    expect(a).toMatch(UUID);
    expect(randomUuid()).not.toBe(a);
  });
});

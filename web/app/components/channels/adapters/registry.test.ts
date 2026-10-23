import { describe, expect, it } from "vitest";
import { CHANNEL_ORDER } from "../ChannelInspectorCards";
import { CHANNEL_ADAPTERS, adapterFor } from "./index";

describe("channel adapter registry", () => {
  it("has an inspector for every channel tile, sms and mobile-sdk", () => {
    for (const key of [...CHANNEL_ORDER, "sms", "mobile-sdk"]) {
      expect(adapterFor(key), key).toBeDefined();
    }
  });

  it("keeps an adapter for the channels a personal install hides (they are hidden at render, not unregistered)", () => {
    for (const key of ["slack", "discord", "teams", "x"]) {
      expect(CHANNEL_ORDER, key).toContain(key);
      expect(adapterFor(key), key).toBeDefined();
    }
  });

  it("registers each key once", () => {
    const keys = CHANNEL_ADAPTERS.flatMap((a) => a.keys);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it("returns undefined for an unknown channel", () => {
    expect(adapterFor("nope")).toBeUndefined();
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { CHANNELS, offeredChannels } from "./inboxFormat";

afterEach(() => vi.unstubAllEnvs());

describe("offeredChannels (the Human Inbox channel filter)", () => {
  it("lists every channel on lite, full and an undeclared install", () => {
    expect(offeredChannels()).toEqual(CHANNELS);
    for (const e of ["lite", "full"]) {
      vi.stubEnv("NEXT_PUBLIC_EDITION", e);
      expect(offeredChannels()).toEqual(CHANNELS);
    }
  });

  it("drops Slack, Discord, X and Teams on a personal install and keeps the rest", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const values = offeredChannels().map((c) => c.value);
    for (const c of ["SLACK", "DISCORD", "X", "TEAMS"]) expect(values).not.toContain(c);
    for (const c of ["WHATSAPP", "TELEGRAM", "EMAIL", "SIGNAL", "SMS", "VOICE"]) expect(values).toContain(c);
  });
});

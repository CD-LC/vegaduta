import { afterEach, describe, expect, it, vi } from "vitest";

// The channel lists below are module constants (read once at load, which is safe for a public-env key: see
// lib/env.ts), so each case stubs the edition and imports a fresh copy of the module.
async function load<T>(edition: string | null, mod: () => Promise<T>): Promise<T> {
  vi.resetModules();
  vi.unstubAllEnvs();
  if (edition) vi.stubEnv("NEXT_PUBLIC_EDITION", edition);
  return mod();
}

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
  vi.resetModules();
});

describe("scheduled-task delivery channels", () => {
  it("drops slack on a personal install and keeps the rest", async () => {
    const { DELIVERY_CHANNELS } = await load("personal", () => import("./scheduledTasks"));
    expect(DELIVERY_CHANNELS).not.toContain("slack");
    for (const c of ["in_app", "email", "whatsapp", "telegram", "signal", "matrix", "imessage", "a2a", "voice"]) {
      expect(DELIVERY_CHANNELS, c).toContain(c);
    }
  });

  it("is unchanged on lite, full and an undeclared install", async () => {
    for (const edition of ["lite", "full", null]) {
      const { DELIVERY_CHANNELS } = await load(edition, () => import("./scheduledTasks"));
      expect(DELIVERY_CHANNELS, String(edition)).toContain("slack");
      expect(DELIVERY_CHANNELS, String(edition)).toHaveLength(10);
    }
  });

  it("still validates a slack target, so an existing task keeps opening", async () => {
    const { normalizeDeliveryTarget } = await load("personal", () => import("./scheduledTasks"));
    expect(normalizeDeliveryTarget("slack", "C0123ABC")).toBe("C0123ABC");
  });
});

describe("approval policy action types", () => {
  const values = (list: { value: string }[]) => list.map((a) => a.value);

  it("drops the slack, discord and x send actions on a personal install", async () => {
    const { CHANNEL_ACTION_TYPES } = await load("personal", () => import("../components/approvals/approvalsModel"));
    expect(values(CHANNEL_ACTION_TYPES)).toEqual([
      "channel.*.send",
      "channel.whatsapp.send",
      "channel.instagram.send",
      "channel.messenger.send",
    ]);
  });

  it("lists all seven otherwise", async () => {
    for (const edition of ["lite", "full", null]) {
      const { CHANNEL_ACTION_TYPES } = await load(edition, () => import("../components/approvals/approvalsModel"));
      expect(CHANNEL_ACTION_TYPES, String(edition)).toHaveLength(7);
    }
  });
});

describe("contacts channel picker", () => {
  it("offers no slack, discord or x on a personal install but still labels them", async () => {
    const { CHANNEL_ORDER, CHANNEL_META } = await load("personal", () => import("../components/contacts/shared"));
    for (const k of ["slack", "discord", "x"]) {
      expect(CHANNEL_ORDER, k).not.toContain(k);
      expect(CHANNEL_META[k], k).toBeDefined();
    }
    for (const k of ["whatsapp", "telegram", "email", "signal", "voice"]) expect(CHANNEL_ORDER, k).toContain(k);
  });

  it("offers all of them otherwise", async () => {
    const { CHANNEL_ORDER, CHANNEL_META } = await load("full", () => import("../components/contacts/shared"));
    expect(CHANNEL_ORDER).toEqual(Object.keys(CHANNEL_META));
  });
});

describe("workflow trigger channels", () => {
  it("offers webhook, whatsapp, sms, call, messenger, instagram and email but not slack, discord or x on a personal install", async () => {
    const { triggerChannelOffered } = await load("personal", () => import("../components/workflow/nodeDefs"));
    for (const c of ["WEBHOOK", "WHATSAPP", "SMS", "CALL", "MESSENGER", "INSTAGRAM", "EMAIL"]) {
      expect(triggerChannelOffered(c), c).toBe(true);
    }
    for (const c of ["SLACK", "DISCORD", "X"]) expect(triggerChannelOffered(c), c).toBe(false);
  });

  it("offers every trigger channel elsewhere", async () => {
    const { triggerChannelOffered } = await load("full", () => import("../components/workflow/nodeDefs"));
    for (const c of ["SLACK", "DISCORD", "X"]) expect(triggerChannelOffered(c), c).toBe(true);
  });
});

describe("dashboard platform checks", () => {
  const keys = (list: { key: string }[]) => list.map((p) => p.key);

  it("leaves out slack, discord and x on a personal install", async () => {
    const { PLATFORM_CHECKS } = await load("personal", () => import("../admin/_overview/model"));
    expect(keys(PLATFORM_CHECKS)).toEqual(["twilio", "meta-whatsapp", "meta-social", "google"]);
  });

  it("keeps all seven otherwise", async () => {
    for (const edition of ["lite", "full", null]) {
      const { PLATFORM_CHECKS } = await load(edition, () => import("../admin/_overview/model"));
      expect(keys(PLATFORM_CHECKS), String(edition)).toHaveLength(7);
    }
  });

  it("drops the invite-a-teammate setup step on a personal install only", async () => {
    const t = (k: string) => k;
    const stats = { agents: 0, channelConnected: false, users: 1, providers: 0, onPlatformLlm: null } as never;
    const personal = await load("personal", () => import("../admin/_overview/model"));
    expect(personal.buildSetupSteps(stats, t).map((s) => s.key)).toEqual(["agent", "channel"]);
    const full = await load("full", () => import("../admin/_overview/model"));
    expect(full.buildSetupSteps(stats, t).map((s) => s.key)).toEqual(["agent", "channel", "user"]);
  });
});

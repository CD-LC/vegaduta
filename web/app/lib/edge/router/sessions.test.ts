import { describe, expect, it, vi } from "vitest";
import {
  dropTier0Session,
  dropTier1Session,
  dropTier2Session,
  resetEdgeSession,
  tier0Sessions,
  tier1Sessions,
  tier2Sessions,
} from "./sessions";
import { subscribeEdgeRecovery } from "./circuit";
import type { Tier0Session } from "../tier0";
import type { Tier1Session } from "../tier1";
import type { Tier2Session } from "../tier2";

const stub = <T,>() => ({ destroy: vi.fn() }) as unknown as T & { destroy: ReturnType<typeof vi.fn> };

describe("router session caches", () => {
  it("resetEdgeSession destroys the session in every tier and uses the default key", () => {
    const a = stub<Tier0Session>();
    const b = stub<Tier1Session>();
    const c = stub<Tier2Session>();
    tier0Sessions.set("__default__", a);
    tier1Sessions.set("__default__", b);
    tier2Sessions.set("__default__", c);
    resetEdgeSession();
    expect(a.destroy).toHaveBeenCalled();
    expect(b.destroy).toHaveBeenCalled();
    expect(c.destroy).toHaveBeenCalled();
    expect(tier0Sessions.size + tier1Sessions.size + tier2Sessions.size).toBe(0);
  });

  it("a throwing destroy never escapes and the entry is still dropped", () => {
    const s = stub<Tier1Session>();
    s.destroy.mockImplementation(() => {
      throw new Error("boom");
    });
    tier1Sessions.set("k", s);
    expect(() => dropTier1Session("k")).not.toThrow();
    expect(tier1Sessions.has("k")).toBe(false);
  });

  it("dropping an unknown key is a no-op for every tier", () => {
    expect(() => {
      dropTier0Session("nope");
      dropTier1Session("nope");
      dropTier2Session("nope");
      resetEdgeSession("nope");
    }).not.toThrow();
  });
});

describe("recovery subscription", () => {
  it("returns an unsubscribe function", () => {
    const off = subscribeEdgeRecovery(() => {});
    expect(typeof off).toBe("function");
    off();
  });
});

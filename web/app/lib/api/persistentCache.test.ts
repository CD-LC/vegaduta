import { describe, it, expect } from "vitest";
import { isPersistablePath, readPersisted, writePersisted, clearPersisted } from "./persistentCache";

describe("isPersistablePath", () => {
  it("allows plain tenant list reads", () => {
    for (const p of ["/api/agents", "/api/workflows", "/api/knowledge/collections", "/api/tenant/settings", "/api/skills?x=1"]) {
      expect(isPersistablePath(p)).toBe(true);
    }
  });

  it("never persists credential-bearing or personal reads", () => {
    for (const p of [
      "/api/providers",
      "/api/secrets",
      "/api/channels/slack/credential",
      "/api/account",
      "/api/admin/users",
      "/api/system/status",
      "/api/agents/abc/memories",
    ]) {
      expect(isPersistablePath(p)).toBe(false);
    }
  });
});

describe("without IndexedDB (jsdom / SSR / private mode)", () => {
  it("degrades to a no-op instead of throwing", async () => {
    await expect(writePersisted("u1", { path: "/api/agents", fetchedAt: Date.now(), status: 200, contentType: null, body: "[]" })).resolves.toBeUndefined();
    await expect(readPersisted("u1")).resolves.toEqual([]);
    await expect(clearPersisted()).resolves.toBeUndefined();
  });
});

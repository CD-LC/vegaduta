import { afterEach, describe, expect, it, vi } from "vitest";
import fs from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";
import {
  DISMISS_KEY_PREFIX,
  announcementActive,
  editionMismatch,
  dismissKey,
  getPlatformStatusState,
  hashMessage,
  isMaintenanceResponse,
  maintenanceActive,
  normalizePublicStatus,
  readDismissed,
  refreshPlatformStatus,
  resetPlatformStatusForTests,
  signalMaintenance,
  writeDismissed,
  type PublicAnnouncement,
} from "./platformStatus";

const ANN: PublicAnnouncement = {
  enabled: true,
  message: "New models are live",
  level: "info",
  linkUrl: null,
  expiresAt: null,
};

function fakeFetch(body: unknown, ok = true): typeof fetch {
  return (async () => ({ ok, json: async () => body })) as unknown as typeof fetch;
}

afterEach(() => {
  resetPlatformStatusForTests();
  try {
    window.localStorage.clear();
  } catch {
    /* ignore */
  }
});

describe("normalizePublicStatus", () => {
  it("parses the contract shape", () => {
    const s = normalizePublicStatus({
      maintenance: { enabled: true, message: "Upgrading", endsAt: "2026-10-09T14:00:00Z" },
      announcement: { ...ANN, level: "warning", linkUrl: "https://vegaduta.ai/changelog" },
    });
    expect(s?.maintenance).toEqual({ enabled: true, message: "Upgrading", endsAt: "2026-10-09T14:00:00Z" });
    expect(s?.announcement?.level).toBe("warning");
    expect(s?.announcement?.linkUrl).toBe("https://vegaduta.ai/changelog");
  });

  it("is tolerant: missing parts mean off, unsafe links are dropped, unknown level is info", () => {
    const s = normalizePublicStatus({ announcement: { ...ANN, level: "loud", linkUrl: "javascript:alert(1)" } });
    expect(s?.maintenance.enabled).toBe(false);
    expect(s?.announcement?.level).toBe("info");
    expect(s?.announcement?.linkUrl).toBeNull();
    expect(normalizePublicStatus({ maintenance: { enabled: "yes" } })?.maintenance.enabled).toBe(false);
    expect(normalizePublicStatus(null)).toBeNull();
    expect(normalizePublicStatus("x")).toBeNull();
  });
});

const EDITION_BODY = {
  tier: "lite",
  billingEnabled: true,
  authMode: "keycloak",
  features: { "billing-payments": true, "social-channels": true, bogus: "yes" },
};

describe("edition in the public status", () => {
  it("parses the edition block tolerantly", () => {
    const s = normalizePublicStatus({ edition: EDITION_BODY });
    expect(s?.edition).toEqual({
      tier: "lite",
      billingEnabled: true,
      authMode: "keycloak",
      features: { "billing-payments": true, "social-channels": true },
    });
  });

  it("is null from a core without the contract or with an unusable tier", () => {
    expect(normalizePublicStatus({})?.edition).toBeNull();
    expect(normalizePublicStatus({ edition: { tier: "enterprise" } })?.edition).toBeNull();
    expect(normalizePublicStatus({ edition: "personal" })?.edition).toBeNull();
  });

  it("reports a mismatch only when both sides know their edition and they differ", () => {
    const server = normalizePublicStatus({ edition: EDITION_BODY });
    expect(editionMismatch(server, "personal")).toEqual({ seed: "personal", server: "lite" });
    expect(editionMismatch(server, "lite")).toBeNull();
    expect(editionMismatch(server, "full")).toBeNull(); // lite and full render the same, so they never warn
    expect(editionMismatch(server, null)).toBeNull(); // an undeclared build has nothing to contradict
    expect(editionMismatch(normalizePublicStatus({}), "personal")).toBeNull(); // an older core
    expect(editionMismatch(null, "personal")).toBeNull();
  });

  it("compares personal against not-personal only: a lite console on a default (full) core is not a mismatch", () => {
    const tier = (t: string) => normalizePublicStatus({ edition: { ...EDITION_BODY, tier: t } });
    expect(editionMismatch(tier("full"), "lite")).toBeNull();
    expect(editionMismatch(tier("lite"), "full")).toBeNull();
    expect(editionMismatch(tier("full"), "personal")).toEqual({ seed: "personal", server: "full" });
    expect(editionMismatch(tier("personal"), "full")).toEqual({ seed: "full", server: "personal" });
    expect(editionMismatch(tier("personal"), "lite")).toEqual({ seed: "lite", server: "personal" });
    expect(editionMismatch(tier("personal"), "personal")).toBeNull();
  });

  it("raises no banner for a lite console in front of a core that reports full", async () => {
    vi.stubEnv("NEXT_PUBLIC_DEPLOY_ENV", "lite");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      await refreshPlatformStatus(fakeFetch({ edition: { ...EDITION_BODY, tier: "full" } }), { force: true });
      expect(getPlatformStatusState().status?.announcement).toBeNull();
      expect(warn).not.toHaveBeenCalled();
    } finally {
      warn.mockRestore();
      vi.unstubAllEnvs();
    }
  });

  it("warns once and shows the banner on a mismatch, without touching the rest of the status", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      await refreshPlatformStatus(fakeFetch({ maintenance: { enabled: false }, edition: EDITION_BODY }), { force: true });
      const a = getPlatformStatusState().status?.announcement;
      expect(a?.level).toBe("warning");
      expect(a?.message).toContain("personal");
      expect(a?.message).toContain("lite");
      expect(announcementActive(a)).toBe(true);
      expect(getPlatformStatusState().status?.maintenance.enabled).toBe(false);
      await refreshPlatformStatus(fakeFetch({ edition: EDITION_BODY }), { force: true });
      expect(warn).toHaveBeenCalledTimes(1);
    } finally {
      warn.mockRestore();
      vi.unstubAllEnvs();
    }
  });

  it("keeps an announcement core sent, and stays silent when the editions agree", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      await refreshPlatformStatus(fakeFetch({ announcement: ANN, edition: EDITION_BODY }), { force: true });
      expect(getPlatformStatusState().status?.announcement?.message).toBe(ANN.message);
      resetPlatformStatusForTests();
      await refreshPlatformStatus(fakeFetch({ edition: { ...EDITION_BODY, tier: "personal" } }), { force: true });
      expect(getPlatformStatusState().status?.announcement).toBeNull();
      expect(warn).toHaveBeenCalledTimes(1); // the first, mismatching poll only
    } finally {
      warn.mockRestore();
      vi.unstubAllEnvs();
    }
  });
});

describe("announcement visibility", () => {
  const now = Date.parse("2026-10-09T00:00:00Z");
  it("needs enabled + text + not expired", () => {
    expect(announcementActive(ANN, now)).toBe(true);
    expect(announcementActive({ ...ANN, enabled: false }, now)).toBe(false);
    expect(announcementActive({ ...ANN, message: "  " }, now)).toBe(false);
    expect(announcementActive({ ...ANN, expiresAt: "2026-10-08T00:00:00Z" }, now)).toBe(false);
    expect(announcementActive({ ...ANN, expiresAt: "2026-10-10T00:00:00Z" }, now)).toBe(true);
    expect(announcementActive(null, now)).toBe(false);
  });

  it("dismiss key is stable per message and changes when the message changes", () => {
    expect(dismissKey(ANN)).toBe(dismissKey({ ...ANN }));
    expect(dismissKey(ANN)).not.toBe(dismissKey({ ...ANN, message: "Something else" }));
    expect(dismissKey(ANN)).not.toBe(dismissKey({ ...ANN, level: "critical" }));
    expect(dismissKey(ANN).startsWith(DISMISS_KEY_PREFIX)).toBe(true);
    expect(hashMessage("a")).not.toBe(hashMessage("b"));
  });

  it("dismissal round-trips through localStorage", () => {
    const key = dismissKey(ANN);
    expect(readDismissed(key)).toBe(false);
    writeDismissed(key);
    expect(readDismissed(key)).toBe(true);
  });
});

describe("maintenance detection", () => {
  it("recognises only core's maintenance 503", () => {
    expect(isMaintenanceResponse(503, { error: "maintenance" })).toBe(true);
    expect(isMaintenanceResponse(503, { error: "Service unavailable" })).toBe(false);
    expect(isMaintenanceResponse(500, { error: "maintenance" })).toBe(false);
    expect(isMaintenanceResponse(503, null)).toBe(false);
  });

  it("a 503 signal turns maintenance on until the poll says it is off", async () => {
    expect(maintenanceActive(getPlatformStatusState())).toBe(false);
    signalMaintenance();
    expect(maintenanceActive(getPlatformStatusState())).toBe(true);
    await refreshPlatformStatus(fakeFetch({ maintenance: { enabled: true, message: null, endsAt: null } }));
    expect(maintenanceActive(getPlatformStatusState())).toBe(true);
    await refreshPlatformStatus(fakeFetch({ maintenance: { enabled: false }, announcement: null }), { force: true });
    expect(maintenanceActive(getPlatformStatusState())).toBe(false);
  });

  it("refresh fails silent and keeps the last state", async () => {
    await refreshPlatformStatus(fakeFetch({ maintenance: { enabled: false }, announcement: ANN }));
    const before = getPlatformStatusState();
    await refreshPlatformStatus(fakeFetch({}, false), { force: true });
    await refreshPlatformStatus(
      (async () => {
        throw new TypeError("Failed to fetch");
      }) as unknown as typeof fetch,
      { force: true },
    );
    expect(getPlatformStatusState()).toBe(before);
  });

  it("coalesces concurrent callers and throttles automatic refetches", async () => {
    let calls = 0;
    const counting = (async () => {
      calls++;
      return { ok: true, json: async () => ({ maintenance: { enabled: false }, announcement: null as unknown }) };
    }) as unknown as typeof fetch;
    await Promise.all([refreshPlatformStatus(counting), refreshPlatformStatus(counting)]);
    expect(calls).toBe(1);
    await refreshPlatformStatus(counting); // inside MIN_REFETCH_MS
    expect(calls).toBe(1);
    await refreshPlatformStatus(counting, { force: true });
    expect(calls).toBe(2);
  });

  it("does not hammer after a failure, but a forced retry still goes out", async () => {
    let calls = 0;
    const failing = (async () => {
      calls++;
      throw new TypeError("Failed to fetch");
    }) as unknown as typeof fetch;
    await refreshPlatformStatus(failing);
    await refreshPlatformStatus(failing);
    expect(calls).toBe(1);
    await refreshPlatformStatus(failing, { force: true });
    expect(calls).toBe(2);
  });
});

describe("platformStatus i18n keys", () => {
  const catalog = (en as unknown as Record<string, Record<string, string>>).platformStatus;
  const KEY_RE = /\bt\(\s*"([A-Za-z0-9_]+)"/g;
  for (const file of ["app/components/PlatformStatusBanner.tsx", "app/components/MaintenanceScreen.tsx"]) {
    it(`${file} only uses keys that exist`, () => {
      const src = fs.readFileSync(path.resolve(process.cwd(), file), "utf8");
      const keys = [...src.matchAll(KEY_RE)].map((m) => m[1]);
      expect(keys.length).toBeGreaterThan(0);
      for (const k of keys) expect(catalog[k], `platformStatus.${k}`).toBeTypeOf("string");
    });
  }
});

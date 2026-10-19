// The failure taxonomy's two promises (2026-09-19):
//   1. every reason — including one nobody anticipated — yields a usable
//      sentence and exactly ONE action (a surface can never render nothing);
//   2. classification prefers explicit signals over sniffing library text.

import { describe, expect, it } from "vitest";
import {
  HOSTED_FALLBACK_LABEL,
  classifyDownloadFailure,
  describeDownloadFailure,
  downloadFailureOneLine,
  formatBytesPlain,
  isTransientDownloadFailure,
  type EdgeDownloadFailureReason,
} from "./failures";

const ALL_REASONS: EdgeDownloadFailureReason[] = [
  "edge-disabled",
  "no-webgpu",
  "no-wasm",
  "no-cache-storage",
  "offline",
  "host-unreachable",
  "quota-too-small",
  "storage-write-failed",
  "network-interrupted",
  "stalled",
  "timed-out",
  "integrity-failed",
  "incomplete",
  "version-mismatch",
  "cache-unloadable",
  "device-memory",
  "model-unavailable",
  "cancelled",
  "unknown",
];

describe("describeDownloadFailure", () => {
  it("is total: every reason has a title, a sentence, one action and a label", () => {
    for (const reason of ALL_REASONS) {
      const d = describeDownloadFailure(reason);
      expect(d.reason).toBe(reason);
      expect(d.title.length).toBeGreaterThan(3);
      expect(d.message.length).toBeGreaterThan(10);
      expect(d.actionLabel.length).toBeGreaterThan(2);
      expect(["retry", "pick-smaller-model", "free-space", "redownload", "use-hosted"]).toContain(d.action);
    }
  });

  it("never leaks a raw code or 'undefined' into user-facing copy", () => {
    for (const reason of ALL_REASONS) {
      for (const ctx of [{}, { neededBytes: 2_000_000_000 }, { smallerModelAvailable: true }]) {
        const d = describeDownloadFailure(reason, ctx);
        const text = `${d.title} ${d.message} ${d.actionLabel}`;
        expect(text).not.toMatch(/undefined|null|NaN|\[object/);
      }
    }
  });

  it("an unrecognised reason degrades to 'unknown', not to an empty render", () => {
    const d = describeDownloadFailure("something-new-from-a-future-build");
    expect(d.reason).toBe("unknown");
    expect(d.action).toBe("retry");
    expect(describeDownloadFailure(undefined).reason).toBe("unknown");
  });

  it("capability failures do not offer a retry that can only fail again", () => {
    for (const reason of ["no-webgpu", "no-wasm", "no-cache-storage", "edge-disabled"] as const) {
      const d = describeDownloadFailure(reason);
      expect(d.retryable).toBe(false);
      expect(d.action).toBe("use-hosted");
      expect(d.actionLabel).toBe(HOSTED_FALLBACK_LABEL);
    }
  });

  it("quota-too-small states both numbers and picks the action by what is possible", () => {
    const withSmaller = describeDownloadFailure("quota-too-small", {
      neededBytes: 2_200_000_000,
      freeBytes: 500_000_000,
      smallerModelAvailable: true,
    });
    expect(withSmaller.message).toContain("2.2 GB");
    expect(withSmaller.message).toContain("500 MB");
    expect(withSmaller.message).toContain("wasn't started");
    expect(withSmaller.action).toBe("pick-smaller-model");

    const noSmaller = describeDownloadFailure("quota-too-small", { neededBytes: 2_200_000_000 });
    expect(noSmaller.action).toBe("free-space");
    expect(noSmaller.message).toContain("2.2 GB");
  });

  it("host-unreachable names the host when known", () => {
    expect(describeDownloadFailure("host-unreachable", { host: "huggingface.co" }).message).toContain(
      "huggingface.co"
    );
    expect(describeDownloadFailure("host-unreachable").message).toContain("The model host");
  });

  it("version-mismatch offers a re-download, not a retry of the same load", () => {
    expect(describeDownloadFailure("version-mismatch").action).toBe("redownload");
  });

  it("every reason whose action evicts the model says so, with the size, and never promises a partial fetch", () => {
    for (const reason of ["version-mismatch", "cache-unloadable"] as const) {
      const d = describeDownloadFailure(reason, { neededBytes: 2_200_000_000 });
      expect(d.action).toBe("redownload");
      expect(d.message).toContain("deletes the stored files");
      expect(d.message).toContain("2.2 GB");
      expect(d.message).not.toMatch(/only what'?s missing/i);
    }
    // ...and only "incomplete" (whose retry does NOT evict) makes the partial-fetch promise.
    expect(describeDownloadFailure("incomplete").action).toBe("retry");
  });

  it("'stalled' says we stopped waiting — it never claims the download itself was stopped", () => {
    const d = describeDownloadFailure("stalled");
    expect(d.message).toMatch(/stopped waiting/i);
    expect(d.message).not.toMatch(/it was stopped|was stopped rather/i);
  });

  it("the one-line form is the title", () => {
    expect(downloadFailureOneLine("stalled")).toBe(describeDownloadFailure("stalled").title);
  });
});

describe("classifyDownloadFailure", () => {
  const named = (name: string, message = "x") => Object.assign(new Error(message), { name });

  it("explicit signals win over error text", () => {
    const fetchErr = new TypeError("Failed to fetch");
    expect(classifyDownloadFailure({ error: fetchErr, userCancelled: true })).toBe("cancelled");
    expect(classifyDownloadFailure({ error: fetchErr, watchdog: "stalled" })).toBe("stalled");
    expect(classifyDownloadFailure({ error: fetchErr, watchdog: "timed-out" })).toBe("timed-out");
    expect(classifyDownloadFailure({ error: fetchErr, online: false })).toBe("offline");
  });

  it("recognises each browser's fetch failure wording as a network interruption", () => {
    expect(classifyDownloadFailure({ error: new TypeError("Failed to fetch"), online: true })).toBe(
      "network-interrupted"
    );
    expect(
      classifyDownloadFailure({ error: new TypeError("NetworkError when attempting to fetch resource.") })
    ).toBe("network-interrupted");
    expect(classifyDownloadFailure({ error: new TypeError("Load failed") })).toBe("network-interrupted");
  });

  it("reads a Tier1Error's preserved library message from `detail`", () => {
    const tier1 = Object.assign(new Error("The on-device model could not be loaded."), {
      name: "Tier1Error",
      code: "engine-failed",
      detail: "TypeError: Failed to fetch",
    });
    expect(classifyDownloadFailure({ error: tier1, online: true })).toBe("network-interrupted");
  });

  it("storage and integrity are told apart from the network", () => {
    expect(classifyDownloadFailure({ error: named("QuotaExceededError") })).toBe("storage-write-failed");
    expect(classifyDownloadFailure({ error: new Error("shard write failed (storage)") })).toBe(
      "storage-write-failed"
    );
    expect(classifyDownloadFailure({ error: named("VerificationError") })).toBe("integrity-failed");
    // A full disk while offline is still a full disk.
    expect(classifyDownloadFailure({ error: named("QuotaExceededError"), online: false })).toBe(
      "storage-write-failed"
    );
  });

  it("GPU loss is device-memory", () => {
    expect(classifyDownloadFailure({ error: named("DeviceLostError") })).toBe("device-memory");
  });

  it("HTTP status: gone vs blocked/unwell", () => {
    expect(classifyDownloadFailure({ error: new Error("x"), httpStatus: 404 })).toBe("model-unavailable");
    for (const status of [401, 403, 407, 429, 500, 502, 503]) {
      expect(classifyDownloadFailure({ error: new Error("x"), httpStatus: status })).toBe("host-unreachable");
    }
  });

  it("anything unrecognised is honestly unknown", () => {
    expect(classifyDownloadFailure({ error: new Error("kaboom") })).toBe("unknown");
    expect(classifyDownloadFailure({})).toBe("unknown");
    expect(classifyDownloadFailure({ error: "a string" })).toBe("unknown");
  });
});

describe("isTransientDownloadFailure", () => {
  it("only network-shaped reasons are retried automatically", () => {
    const transient = ALL_REASONS.filter(isTransientDownloadFailure);
    // "offline" is in: a wifi roam / lid close rejects the fetch with
    // navigator.onLine false, and that is the commonest real drop. Callers
    // pair it with waitForOnline so they never spin while the link is down.
    expect(transient.sort()).toEqual(["host-unreachable", "network-interrupted", "offline"]);
  });
});

describe("formatBytesPlain", () => {
  it("formats decimal units and survives junk", () => {
    expect(formatBytesPlain(1_500_000_000)).toBe("1.5 GB");
    expect(formatBytesPlain(250_000_000)).toBe("250 MB");
    expect(formatBytesPlain(10)).toBe("1 MB");
    expect(formatBytesPlain(0)).toBe("0 MB");
    expect(formatBytesPlain(Number.NaN)).toBe("0 MB");
  });
});

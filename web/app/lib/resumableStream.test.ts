import { describe, expect, it } from "vitest";
import {
  PENDING_TURN_MAX_AGE_MS,
  RESUME_MAX_ATTEMPTS,
  backoffDelayMs,
  clearPendingTurn,
  createEventTracker,
  decideResume,
  loadPendingTurn,
  parseTurnEnd,
  pendingTurnKey,
  resumeStreamPath,
  savePendingTurn,
  shouldProbeOnVisible,
  turnSnapshotPath,
} from "./resumableStream";

// A Storage stand-in: the real one is unavailable in some private windows, so the
// helpers take it as a parameter and every access is guarded.
function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (k: string) => (map.has(k) ? (map.get(k) as string) : null),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    removeItem: (k: string) => {
      map.delete(k);
    },
    setItem: (k: string, v: string) => {
      map.set(k, String(v));
    },
  } as Storage;
}

function throwingStorage(): Storage {
  const boom = () => {
    throw new Error("SecurityError");
  };
  return { getItem: boom, setItem: boom, removeItem: boom, clear: boom, key: boom, length: 0 } as unknown as Storage;
}

describe("backoffDelayMs", () => {
  it("doubles from the base and caps", () => {
    const opts = { baseMs: 500, maxMs: 10_000, jitter: 0 };
    expect([1, 2, 3, 4, 5, 6].map((n) => backoffDelayMs(n, opts))).toEqual([500, 1000, 2000, 4000, 8000, 10_000]);
    expect(backoffDelayMs(20, opts)).toBe(10_000);
  });

  it("treats attempt 0 and negatives like the first attempt", () => {
    expect(backoffDelayMs(0, { baseMs: 400, jitter: 0 })).toBe(400);
    expect(backoffDelayMs(-3, { baseMs: 400, jitter: 0 })).toBe(400);
  });

  it("jitters within +/- the fraction and never above the cap or below zero", () => {
    const low = backoffDelayMs(3, { baseMs: 1000, maxMs: 60_000, jitter: 0.2, random: () => 0 });
    const high = backoffDelayMs(3, { baseMs: 1000, maxMs: 60_000, jitter: 0.2, random: () => 1 });
    expect(low).toBe(3200); // 4000 * 0.8
    expect(high).toBe(4800); // 4000 * 1.2
    expect(backoffDelayMs(8, { baseMs: 1000, maxMs: 5000, jitter: 0.5, random: () => 1 })).toBe(5000);
    expect(backoffDelayMs(1, { baseMs: 0, jitter: 0.5, random: () => 0 })).toBe(0);
  });
});

describe("createEventTracker (dedupe by event id)", () => {
  it("delivers each id once, in order", () => {
    const t = createEventTracker();
    expect(t.accept("1")).toBe(true);
    expect(t.accept("2")).toBe(true);
    expect(t.lastId).toBe(2);
  });

  it("drops a replayed id and anything older than the cursor", () => {
    const t = createEventTracker();
    for (const id of ["1", "2", "3"]) t.accept(id);
    expect(t.accept("3")).toBe(false);
    expect(t.accept("1")).toBe(false);
    expect(t.accept("4")).toBe(true);
    expect(t.lastId).toBe(4);
  });

  it("tolerates a gap (the server may skip) but still never goes backwards", () => {
    const t = createEventTracker();
    expect(t.accept("5")).toBe(true);
    expect(t.accept("9")).toBe(true);
    expect(t.accept("7")).toBe(false);
    expect(t.lastId).toBe(9);
  });

  it("always delivers events without an id and does not track them (flag-off streams)", () => {
    const t = createEventTracker();
    expect(t.accept(null)).toBe(true);
    expect(t.accept(undefined)).toBe(true);
    expect(t.accept("")).toBe(true);
    expect(t.accept("not-a-number")).toBe(true);
    expect(t.lastId).toBe(0);
  });

  it("can start from an existing cursor", () => {
    const t = createEventTracker(10);
    expect(t.accept("10")).toBe(false);
    expect(t.accept("11")).toBe(true);
  });

  it("rebuilds the exact text from an interleaved replay without duplicating tokens", () => {
    // first connection delivered 1..3, then dropped; the resume replays from 3 (an overlap) to the end
    const t = createEventTracker();
    const out: string[] = [];
    const deliver = (id: string, chunk: string) => {
      if (t.accept(id)) out.push(chunk);
    };
    deliver("1", "Hel");
    deliver("2", "lo ");
    deliver("3", "wor");
    deliver("3", "wor");
    deliver("4", "ld");
    deliver("2", "lo ");
    expect(out.join("")).toBe("Hello world");
  });
});

describe("parseTurnEnd", () => {
  it("reads the closing frame core writes", () => {
    expect(parseTurnEnd('{"status":"complete","lastEventId":12}')).toEqual({ status: "complete", lastEventId: 12 });
    expect(parseTurnEnd('{"status":"detach-timeout"}')).toEqual({ status: "detach-timeout", lastEventId: null });
  });

  it("returns null for anything else", () => {
    expect(parseTurnEnd("not json")).toBeNull();
    expect(parseTurnEnd("{}")).toBeNull();
    expect(parseTurnEnd("null")).toBeNull();
  });
});

describe("decideResume", () => {
  const base = { turnId: "turn-1", turnEnded: false, attempt: 0, failureClass: "stream-error" as string | null };

  it("resumes a dropped connection with a backoff delay", () => {
    const d = decideResume({ ...base, baseDelayMs: 500, random: () => 0.5 });
    expect(d).toEqual({ resume: true, reason: "resume", delayMs: 500 });
  });

  it("grows the delay with each attempt", () => {
    const first = decideResume({ ...base, attempt: 0, baseDelayMs: 500, random: () => 0.5 });
    const third = decideResume({ ...base, attempt: 2, baseDelayMs: 500, random: () => 0.5 });
    expect(third.delayMs).toBeGreaterThan(first.delayMs);
  });

  it("resumes idle timeouts, absolute timeouts and unknown failures too", () => {
    for (const failureClass of ["stream-idle-timeout", "absolute-timeout", "connection-refused", "unknown", "aborted"]) {
      expect(decideResume({ ...base, failureClass }).resume).toBe(true);
    }
  });

  it("does not resume without a turn id (flag off / older core)", () => {
    expect(decideResume({ ...base, turnId: null })).toMatchObject({ resume: false, reason: "no-turn" });
  });

  it("does not resume a turn that already ended", () => {
    expect(decideResume({ ...base, turnEnded: true })).toMatchObject({ resume: false, reason: "turn-ended" });
  });

  it("does not resume after the user pressed Stop or the page's own signal fired", () => {
    expect(decideResume({ ...base, userStopped: true })).toMatchObject({ resume: false, reason: "user-stopped" });
    expect(decideResume({ ...base, externalAborted: true })).toMatchObject({ resume: false, reason: "external-abort" });
  });

  it("does not resume a failure the server reported in-band", () => {
    expect(decideResume({ ...base, markerError: true })).toMatchObject({ resume: false, reason: "server-error" });
  });

  it("gives up on definitive HTTP answers but retries transient ones", () => {
    for (const httpStatus of [400, 401, 403, 404, 410]) {
      expect(decideResume({ ...base, httpStatus })).toMatchObject({ resume: false, reason: "http-error" });
    }
    for (const httpStatus of [408, 429, 500, 502, 503, 504]) {
      expect(decideResume({ ...base, httpStatus }).resume).toBe(true);
    }
  });

  it("stops when the breaker is open", () => {
    expect(decideResume({ ...base, failureClass: "circuit-open" })).toMatchObject({ resume: false, reason: "circuit-open" });
  });

  it("stops after the configured number of attempts", () => {
    expect(decideResume({ ...base, attempt: RESUME_MAX_ATTEMPTS - 1 }).resume).toBe(true);
    expect(decideResume({ ...base, attempt: RESUME_MAX_ATTEMPTS })).toMatchObject({ resume: false, reason: "exhausted" });
    expect(decideResume({ ...base, attempt: 2, maxAttempts: 2 })).toMatchObject({ resume: false, reason: "exhausted" });
  });

  it("flag off identity: a stream with no turn id is never resumed whatever failed", () => {
    for (const failureClass of ["stream-error", "stream-idle-timeout", "connection-refused", null]) {
      expect(decideResume({ turnId: null, turnEnded: false, attempt: 0, failureClass }).resume).toBe(false);
    }
  });
});

describe("shouldProbeOnVisible", () => {
  const base = { visibilityState: "visible", inFlight: true, turnId: "t", lastActivityAt: 0, now: 25_000 };

  it("probes a visible tab whose stream has been silent too long", () => {
    expect(shouldProbeOnVisible(base)).toBe(true);
  });

  it("does nothing while hidden, idle, without a turn, or when the stream is fresh", () => {
    expect(shouldProbeOnVisible({ ...base, visibilityState: "hidden" })).toBe(false);
    expect(shouldProbeOnVisible({ ...base, inFlight: false })).toBe(false);
    expect(shouldProbeOnVisible({ ...base, turnId: null })).toBe(false);
    expect(shouldProbeOnVisible({ ...base, lastActivityAt: 20_000 })).toBe(false);
  });
});

describe("pending turn storage", () => {
  const turn = { turnId: "turn-1", agentId: "agent-1", sessionId: "s-1", userMessage: "hi there", startedAt: 1_000 };

  it("round-trips a fresh record", () => {
    const s = memoryStorage();
    savePendingTurn(turn, s);
    expect(loadPendingTurn("agent-1", 2_000, s)).toEqual(turn);
  });

  it("is scoped per agent", () => {
    const s = memoryStorage();
    savePendingTurn(turn, s);
    expect(loadPendingTurn("agent-2", 2_000, s)).toBeNull();
  });

  it("drops (and removes) a record older than the buffer lifetime", () => {
    const s = memoryStorage();
    savePendingTurn(turn, s);
    expect(loadPendingTurn("agent-1", turn.startedAt + PENDING_TURN_MAX_AGE_MS + 1, s)).toBeNull();
    expect(s.getItem(pendingTurnKey("agent-1"))).toBeNull();
  });

  it("clears on request", () => {
    const s = memoryStorage();
    savePendingTurn(turn, s);
    clearPendingTurn("agent-1", s);
    expect(loadPendingTurn("agent-1", 2_000, s)).toBeNull();
  });

  it("ignores and removes a malformed record", () => {
    const s = memoryStorage();
    s.setItem(pendingTurnKey("agent-1"), "{not json");
    expect(loadPendingTurn("agent-1", 2_000, s)).toBeNull();
    s.setItem(pendingTurnKey("agent-1"), JSON.stringify({ turnId: "", agentId: "agent-1", startedAt: 1 }));
    expect(loadPendingTurn("agent-1", 2_000, s)).toBeNull();
    expect(s.getItem(pendingTurnKey("agent-1"))).toBeNull();
  });

  it("never throws when storage is blocked or missing", () => {
    const blocked = throwingStorage();
    expect(() => savePendingTurn(turn, blocked)).not.toThrow();
    expect(() => clearPendingTurn("agent-1", blocked)).not.toThrow();
    expect(loadPendingTurn("agent-1", 2_000, blocked)).toBeNull();
    expect(() => savePendingTurn(turn, null)).not.toThrow();
    expect(loadPendingTurn("agent-1", 2_000, null)).toBeNull();
  });

  it("caps the stored message", () => {
    const s = memoryStorage();
    savePendingTurn({ ...turn, userMessage: "x".repeat(20_000) }, s);
    expect(loadPendingTurn("agent-1", 2_000, s)?.userMessage.length).toBe(8000);
  });
});

describe("paths", () => {
  it("builds the resume and snapshot URLs", () => {
    expect(resumeStreamPath("abc-123", 7)).toBe("/api/chat/turns/abc-123/stream?after=7");
    expect(resumeStreamPath("a/b", -4)).toBe("/api/chat/turns/a%2Fb/stream?after=0");
    expect(turnSnapshotPath("abc-123")).toBe("/api/chat/turns/abc-123");
  });
});

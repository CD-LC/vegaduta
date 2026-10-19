// Backoff, bounded retry, stall/overall watchdogs, bounded concurrency
// (2026-09-19). All timing is injected or faked — nothing here sleeps for real.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ConcurrencyGate,
  DownloadWatchdogError,
  backoffDelayMs,
  runWithWatchdogs,
  waitForOnline,
  withRetry,
} from "./retry";

describe("backoffDelayMs", () => {
  const mid = () => 0.5; // zero jitter offset

  it("grows exponentially and caps", () => {
    expect(backoffDelayMs(1, {}, mid)).toBe(1_500);
    expect(backoffDelayMs(2, {}, mid)).toBe(3_000);
    expect(backoffDelayMs(3, {}, mid)).toBe(6_000);
    expect(backoffDelayMs(10, {}, mid)).toBe(20_000);
  });

  it("jitter stays within its band and never exceeds the cap or goes negative", () => {
    expect(backoffDelayMs(1, {}, () => 0)).toBe(1_125);
    expect(backoffDelayMs(1, {}, () => 0.999999)).toBeLessThanOrEqual(1_875);
    expect(backoffDelayMs(10, {}, () => 0.999999)).toBe(20_000);
    expect(backoffDelayMs(1, { jitter: 5 }, () => 0)).toBe(0);
  });

  it("tolerates nonsense attempt numbers", () => {
    expect(backoffDelayMs(0, {}, mid)).toBe(1_500);
    expect(backoffDelayMs(-3, {}, mid)).toBe(1_500);
  });
});

describe("withRetry", () => {
  const sleep = vi.fn(async (_ms: number): Promise<void> => {});
  beforeEach(() => sleep.mockClear());

  it("returns the first success without sleeping", async () => {
    await expect(withRetry(async () => 7, { maxAttempts: 3, shouldRetry: () => true, sleep })).resolves.toBe(7);
    expect(sleep).not.toHaveBeenCalled();
  });

  it("retries transient failures, then succeeds", async () => {
    let calls = 0;
    const onRetry = vi.fn();
    const result = await withRetry(
      async () => {
        calls += 1;
        if (calls < 3) throw new Error("drop");
        return "done";
      },
      { maxAttempts: 3, shouldRetry: () => true, sleep, onRetry, random: () => 0.5 }
    );
    expect(result).toBe("done");
    expect(calls).toBe(3);
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([1_500, 3_000]);
    expect(onRetry).toHaveBeenCalledTimes(2);
  });

  it("gives up at maxAttempts and rethrows the LAST error", async () => {
    let calls = 0;
    await expect(
      withRetry(
        async () => {
          calls += 1;
          throw new Error(`fail ${calls}`);
        },
        { maxAttempts: 3, shouldRetry: () => true, sleep }
      )
    ).rejects.toThrow("fail 3");
    expect(calls).toBe(3);
  });

  it("does not retry what shouldRetry refuses", async () => {
    let calls = 0;
    await expect(
      withRetry(
        async () => {
          calls += 1;
          throw new Error("disk full");
        },
        { maxAttempts: 5, shouldRetry: () => false, sleep }
      )
    ).rejects.toThrow("disk full");
    expect(calls).toBe(1);
  });

  it("an aborted signal stops further attempts", async () => {
    const controller = new AbortController();
    let calls = 0;
    await expect(
      withRetry(
        async () => {
          calls += 1;
          controller.abort();
          throw new Error("x");
        },
        { maxAttempts: 5, shouldRetry: () => true, sleep, signal: controller.signal }
      )
    ).rejects.toThrow("x");
    expect(calls).toBe(1);
  });

  it("a throwing onRetry observer does not break the loop", async () => {
    let calls = 0;
    await expect(
      withRetry(
        async () => {
          calls += 1;
          if (calls === 1) throw new Error("once");
          return 1;
        },
        {
          maxAttempts: 2,
          shouldRetry: () => true,
          sleep,
          onRetry: () => {
            throw new Error("observer bug");
          },
        }
      )
    ).resolves.toBe(1);
  });
});

describe("runWithWatchdogs", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("resolves when the work resolves, and leaves no timer behind", async () => {
    const p = runWithWatchdogs(async () => "ok", { stallMs: 1_000, overallMs: 10_000 });
    await expect(p).resolves.toBe("ok");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("rejects 'stalled' when touch() is never called", async () => {
    const p = runWithWatchdogs(() => new Promise<never>(() => {}), { stallMs: 1_000, overallMs: 60_000 });
    const assertion = expect(p).rejects.toMatchObject({ name: "DownloadWatchdogError", kind: "stalled" });
    await vi.advanceTimersByTimeAsync(1_001);
    await assertion;
    expect(vi.getTimerCount()).toBe(0);
  });

  it("progress keeps a slow download alive past the stall window", async () => {
    let touch: () => void = () => {};
    let finish: (v: string) => void = () => {};
    const p = runWithWatchdogs(
      (t) => {
        touch = t;
        return new Promise<string>((resolve) => {
          finish = resolve;
        });
      },
      { stallMs: 1_000, overallMs: 60_000 }
    );
    for (let i = 0; i < 5; i += 1) {
      await vi.advanceTimersByTimeAsync(900);
      touch();
    }
    finish("slow but done");
    await expect(p).resolves.toBe("slow but done");
  });

  it("the overall ceiling fires even while progress continues", async () => {
    let touch: () => void = () => {};
    const p = runWithWatchdogs(
      (t) => {
        touch = t;
        return new Promise<never>(() => {});
      },
      { stallMs: 1_000, overallMs: 3_000 }
    );
    const assertion = expect(p).rejects.toMatchObject({ kind: "timed-out" });
    for (let i = 0; i < 4; i += 1) {
      await vi.advanceTimersByTimeAsync(900);
      touch();
    }
    await assertion;
  });

  it("passes through the work's own rejection, including a synchronous throw", async () => {
    await expect(
      runWithWatchdogs(async () => {
        throw new Error("engine failed");
      }, { stallMs: 1_000, overallMs: 10_000 })
    ).rejects.toThrow("engine failed");
    await expect(
      runWithWatchdogs(() => {
        throw new Error("sync");
      }, { stallMs: 1_000, overallMs: 10_000 })
    ).rejects.toThrow("sync");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("DownloadWatchdogError carries its kind", () => {
    expect(new DownloadWatchdogError("stalled").kind).toBe("stalled");
    expect(new DownloadWatchdogError("timed-out").name).toBe("DownloadWatchdogError");
  });
});

describe("ConcurrencyGate", () => {
  it("admits up to the limit, queues the rest FIFO, and release is idempotent", async () => {
    const gate = new ConcurrencyGate(2);
    const r1 = await gate.acquire();
    const r2 = await gate.acquire();
    expect(gate.inFlight).toBe(2);
    const order: string[] = [];
    const third = gate.acquire().then((r) => {
      order.push("third");
      return r;
    });
    const fourth = gate.acquire().then((r) => {
      order.push("fourth");
      return r;
    });
    await Promise.resolve();
    expect(gate.waiting).toBe(2);
    r1();
    r1(); // double release must not free a second slot
    const r3 = await third;
    expect(gate.inFlight).toBe(2);
    expect(gate.waiting).toBe(1);
    r2();
    const r4 = await fourth;
    expect(order).toEqual(["third", "fourth"]);
    r3();
    r4();
    expect(gate.inFlight).toBe(0);
  });

  it("a limit below 1 still admits one", async () => {
    const gate = new ConcurrencyGate(0);
    const release = await gate.acquire();
    expect(gate.inFlight).toBe(1);
    release();
  });
});

describe("withRetry.beforeNextAttempt", () => {
  const opts = { maxAttempts: 3, shouldRetry: () => true, sleep: async () => {} };

  it("runs the gate between attempts and retries when it says go", async () => {
    const gate = vi.fn(async () => true);
    const task = vi.fn().mockRejectedValueOnce(new Error("drop")).mockResolvedValueOnce("ok");
    await expect(withRetry(task, { ...opts, beforeNextAttempt: gate })).resolves.toBe("ok");
    expect(gate).toHaveBeenCalledTimes(1);
  });

  it("a gate that resolves false ends the retries with the pending error", async () => {
    const task = vi.fn().mockRejectedValue(new Error("drop"));
    await expect(withRetry(task, { ...opts, beforeNextAttempt: async () => false })).rejects.toThrow("drop");
    expect(task).toHaveBeenCalledTimes(1);
  });

  it("a gate that throws is not allowed to end the retries", async () => {
    const task = vi.fn().mockRejectedValueOnce(new Error("drop")).mockResolvedValueOnce("ok");
    const gate = async () => {
      throw new Error("broken gate");
    };
    await expect(withRetry(task, { ...opts, beforeNextAttempt: gate })).resolves.toBe("ok");
  });
});

describe("waitForOnline", () => {
  function fakeTarget() {
    const listeners = new Set<() => void>();
    return {
      listeners,
      addEventListener: (_t: string, fn: () => void): void => {
        listeners.add(fn);
      },
      removeEventListener: (_t: string, fn: () => void): void => {
        listeners.delete(fn);
      },
      fire: () => [...listeners].forEach((fn) => fn()),
    };
  }

  it("resolves true immediately when online or when the browser cannot say", async () => {
    await expect(waitForOnline({ isOnline: () => true, target: null })).resolves.toBe(true);
    await expect(waitForOnline({ isOnline: () => undefined, target: null })).resolves.toBe(true);
  });

  it("resolves true on the online event and removes its listener", async () => {
    const target = fakeTarget();
    const waiting = waitForOnline({ isOnline: () => false, target, maxMs: 60_000 });
    expect(target.listeners.size).toBe(1);
    target.fire();
    await expect(waiting).resolves.toBe(true);
    expect(target.listeners.size).toBe(0);
  });

  it("gives up with false when the link stays down for the whole window", async () => {
    vi.useFakeTimers();
    try {
      const waiting = waitForOnline({ isOnline: () => false, target: fakeTarget(), maxMs: 5_000 });
      await vi.advanceTimersByTimeAsync(5_000);
      await expect(waiting).resolves.toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it("an abort ends the wait with false", async () => {
    const controller = new AbortController();
    const waiting = waitForOnline({ isOnline: () => false, target: fakeTarget(), signal: controller.signal, maxMs: 60_000 });
    controller.abort();
    await expect(waiting).resolves.toBe(false);
  });
});

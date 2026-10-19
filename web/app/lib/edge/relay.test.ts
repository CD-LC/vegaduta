import { describe, expect, it, vi } from "vitest";
import { buildRelayInput, getEdgeRelayState, startEdgeRelay, type RelayDeps } from "./relay";
import type { RelayJob } from "../api/edgeRelay";

function job(id: string, overrides: Partial<RelayJob> = {}): RelayJob {
  return {
    jobId: id,
    tenantId: "t",
    channel: "SMS",
    sessionId: "s1",
    agentId: "a1",
    externalIdMasked: "***1111",
    message: "what time do you open?",
    history: [
      { role: "user", text: "hello" },
      { role: "assistant", text: "Hi! How can I help?" },
    ],
    createdAtEpochMs: 1,
    status: "CLAIMED",
    claimedBy: "r1",
    ...overrides,
  };
}

function fakeDeps(jobs: (RelayJob | null)[], runTurn: RelayDeps["runTurn"]) {
  const queue = [...jobs];
  const calls = { reply: [] as unknown[][], fail: [] as unknown[][], unregister: 0, register: 0 };
  let release: (() => void) | null = null;
  const deps: RelayDeps = {
    api: {
      registerEdgeRelay: async () => {
        calls.register += 1;
        return { relayId: "r1", heartbeatSeconds: 1000, claimWaitSecondsMax: 25, replyWaitSeconds: 60 };
      },
      heartbeatEdgeRelay: async () => {},
      unregisterEdgeRelay: async () => {
        calls.unregister += 1;
      },
      nextEdgeRelayJob: async () => {
        if (queue.length > 0) return queue.shift() ?? null;
        // Nothing left: park until stop() releases us, like a long-poll with no traffic.
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return null;
      },
      replyEdgeRelayJob: async (...args) => {
        calls.reply.push(args);
        return true;
      },
      failEdgeRelayJob: async (...args) => {
        calls.fail.push(args);
      },
    },
    runTurn,
    loadAgents: async () => [{ id: "a1", name: "Shop", systemPrompt: "You are the shop." } as never],
    sleep: async () => {},
    pollSeconds: 1,
  };
  const unpark = () => release?.();
  return { deps, calls, unpark };
}

async function settle() {
  for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 0));
}

describe("edge relay loop", () => {
  it("answers a job with the local model and hands the text back", async () => {
    const runTurn = vi.fn(async (input: string, _options?: unknown) => ({
      handled: true as const,
      tier: 1 as const,
      text: `echo: ${input.slice(-22)}`,
    }));
    const { deps, calls, unpark } = fakeDeps([job("j1")], runTurn);

    const handle = startEdgeRelay(["SMS"], deps);
    await settle();

    expect(calls.reply).toHaveLength(1);
    expect(calls.reply[0][0]).toBe("j1");
    expect(calls.reply[0][1]).toBe("r1");
    expect(String(calls.reply[0][2])).toContain("what time do you open?");
    expect(runTurn).toHaveBeenCalledTimes(1);
    expect(runTurn.mock.calls[0][1]).toMatchObject({ sessionKey: "relay:s1", agentId: "a1", systemPrompt: "You are the shop." });
    expect(getEdgeRelayState()).toMatchObject({ status: "online", answered: 1, failed: 0 });

    unpark();
    await handle.stop();
    await settle();
    expect(calls.unregister).toBe(1);
    expect(getEdgeRelayState().status).toBe("stopped");
  });

  it("hands a job back when no local tier can answer, and stops itself after three in a row", async () => {
    const runTurn = vi.fn(async () => ({ handled: false as const, reason: "no-local-tier" as const }));
    const { deps, calls, unpark } = fakeDeps([job("j1"), job("j2"), job("j3")], runTurn);

    startEdgeRelay(["SMS"], deps);
    await settle();
    unpark();
    await settle();

    expect(calls.reply).toHaveLength(0);
    expect(calls.fail.map((c) => c[0])).toEqual(["j1", "j2", "j3"]);
    expect(calls.fail[0][2]).toBe("no-local-tier");
    expect(getEdgeRelayState()).toMatchObject({ status: "error", failed: 3, lastError: "no-local-model" });
    expect(calls.unregister).toBe(1);
  });

  it("gives the model the server-side history only the first time it sees a session", () => {
    const first = buildRelayInput(job("j1"), true);
    expect(first).toContain("Earlier in this conversation:");
    expect(first).toContain("User: hello");
    expect(first).toContain("Assistant: Hi! How can I help?");
    expect(first.endsWith("what time do you open?")).toBe(true);
    expect(buildRelayInput(job("j2"), false)).toBe("what time do you open?");
    expect(buildRelayInput(job("j3", { history: [] }), true)).toBe("what time do you open?");
  });
});

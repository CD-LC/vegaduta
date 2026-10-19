import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, clearApiGetCache } from "../api";
import { resetCircuitBreaker } from "../requestLifecycle";
import { PENDING_TURN_MAX_AGE_MS, loadPendingTurn, pendingTurnKey } from "../resumableStream";

// apiFetch calls auth.getToken() on every request; unmocked it would construct a real keycloak-js (see api.test.ts).
vi.mock("../auth", () => ({
  auth: { getToken: vi.fn().mockResolvedValue(null), refreshToken: vi.fn().mockResolvedValue(false) },
}));

const TURN = "turn-12345678";
const FAST = { maxAttempts: 4, baseDelayMs: 1, maxDelayMs: 5 };

type Part = string | Error | "hang";

// A streamed Response stand-in whose body yields `parts` in order: a string is bytes, an Error makes the read
// reject (a dropped connection), "hang" never resolves until the request is aborted (a stalled connection).
function streamResponse(parts: Part[], headers: Record<string, string> = {}, signal?: AbortSignal) {
  const enc = new TextEncoder();
  let i = 0;
  return {
    ok: true,
    status: 200,
    headers: new Headers(headers),
    body: {
      getReader: () => ({
        read: async () => {
          if (i >= parts.length) return { done: true, value: undefined };
          const part = parts[i++];
          if (part instanceof Error) throw part;
          if (part === "hang") {
            return new Promise<never>((_, reject) => {
              signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
            });
          }
          return { done: false, value: enc.encode(part) };
        },
      }),
    },
  };
}

function statusResponse(status: number, body: unknown = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ "Content-Type": "application/json" }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
}

const FIRST_HALF = "id:1\ndata:Hel\n\nid:2\ndata:lo \n\n";
const SECOND_HALF =
  'id:3\ndata:wor\n\nid:4\ndata:ld\n\nid:5\nevent:turn-end\ndata:{"status":"complete","lastEventId":5}\n\n';

function turnHeaders() {
  return { "X-Session-Id": "s1", "X-Turn-Id": TURN };
}

type Handler = (url: string, init: any) => any;

function stubFetch(handler: Handler) {
  const mock = vi.fn((input: any, init?: any) => Promise.resolve(handler(String(input), init)));
  vi.stubGlobal("fetch", mock);
  return mock;
}

const urls = (mock: ReturnType<typeof vi.fn>) => mock.mock.calls.map((c) => String(c[0]));

describe("streamChat resumable streams", () => {
  beforeEach(() => {
    resetCircuitBreaker("chat-stream:core");
    resetCircuitBreaker("chat-stream:resume");
    window.sessionStorage.clear();
  });

  afterEach(async () => {
    vi.unstubAllGlobals();
    await clearApiGetCache();
  });

  it("opts in with a header when the caller asks, and only a resumable-aware server's reply carries a turn id", async () => {
    const fetchMock = stubFetch(() => streamResponse([FIRST_HALF + SECOND_HALF], turnHeaders()));

    const chunks: string[] = [];
    const sessionId = await api.streamChat(
      "a1",
      "hi",
      null,
      (c: string) => chunks.push(c),
      undefined,
      undefined,
      undefined,
      undefined,
      {
        resumable: true,
      }
    );

    expect(sessionId).toBe("s1");
    expect(chunks.join("")).toBe("Hello world");
    expect(fetchMock.mock.calls[0][1].headers["X-Resumable-Stream"]).toBe("1");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not opt in unless the caller asks (voice, tutor and keyframe turns keep disconnect = stop)", async () => {
    const fetchMock = stubFetch(() => streamResponse(["data:ok\n\n"], { "X-Session-Id": "s1" }));

    await api.streamChat("a1", "hi", null, (): void => undefined);

    expect(fetchMock.mock.calls[0][1].headers["X-Resumable-Stream"]).toBeUndefined();
    // and no client-minted session id either: the server keeps generating it for such turns
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).sessionId).toBeUndefined();
  });

  it("resumes a dropped connection from the last event and never repeats a token", async () => {
    const fetchMock = stubFetch((url, init) => {
      if (url.endsWith("/api/agents/a1/chat")) {
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      }
      // The resume cursor overlaps (event 2 is replayed): the client must drop it.
      return streamResponse(
        ["id:2\ndata:lo \n\n" + SECOND_HALF],
        { "Content-Type": "text/event-stream" },
        init?.signal
      );
    });

    const chunks: string[] = [];
    const reconnecting: Array<{ attempt: number; max: number } | null> = [];
    const sessionId = await api.streamChat(
      "a1",
      "hi",
      null,
      (c: string) => chunks.push(c),
      undefined,
      undefined,
      undefined,
      undefined,
      {
        resumable: true,
        resume: FAST,
        onReconnecting: (s: any) => reconnecting.push(s ? { attempt: s.attempt, max: s.max } : null),
      }
    );

    expect(sessionId).toBe("s1");
    expect(chunks.join("")).toBe("Hello world");
    expect(urls(fetchMock)).toEqual([
      expect.stringMatching(/\/api\/agents\/a1\/chat$/),
      expect.stringMatching(/\/api\/chat\/turns\/turn-12345678\/stream\?after=2$/),
    ]);
    // told the host it was reconnecting, then that it was done
    expect(reconnecting[0]).toEqual({ attempt: 1, max: 4 });
    expect(reconnecting[reconnecting.length - 1]).toBeNull();
  });

  it("treats an end of stream without the closing frame as a cut connection", async () => {
    const fetchMock = stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat")) return streamResponse([FIRST_HALF], turnHeaders());
      return streamResponse([SECOND_HALF]);
    });

    const chunks: string[] = [];
    await api.streamChat("a1", "hi", null, (c: string) => chunks.push(c), undefined, undefined, undefined, undefined, {
      resumable: true,
      resume: FAST,
    });

    expect(chunks.join("")).toBe("Hello world");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("discards a half-received event instead of delivering a fragment under a real id", async () => {
    stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat")) {
        // the connection died in the middle of event 3's data line
        return streamResponse([FIRST_HALF + "id:3\ndata:wo"], turnHeaders());
      }
      return streamResponse([SECOND_HALF]);
    });

    const chunks: string[] = [];
    await api.streamChat("a1", "hi", null, (c: string) => chunks.push(c), undefined, undefined, undefined, undefined, {
      resumable: true,
      resume: FAST,
    });

    expect(chunks.join("")).toBe("Hello world");
  });

  it("gives up with the original failure once the server says the turn is gone", async () => {
    const fetchMock = stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat"))
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      return statusResponse(404, { error: "This reply is no longer available." });
    });

    await expect(
      api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
        resumable: true,
        resume: FAST,
      })
    ).rejects.toMatchObject({ status: 404 });
    // one POST + exactly one resume attempt: a definitive answer is not retried
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("retries a transient resume failure with backoff, up to the attempt limit", async () => {
    const fetchMock = stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat"))
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      return statusResponse(503, { error: "down" });
    });

    await expect(
      api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
        resumable: true,
        resume: { maxAttempts: 3, baseDelayMs: 1, maxDelayMs: 2 },
      })
    ).rejects.toMatchObject({ status: 503 });
    // 1 POST + 3 resume attempts
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("never resumes a failure the server reported in-band", async () => {
    const fetchMock = stubFetch(() =>
      streamResponse(["id:1\ndata:@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@The model is rate limited.\n\n"], turnHeaders())
    );

    await expect(
      api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
        resumable: true,
        resume: FAST,
      })
    ).rejects.toThrow("The model is rate limited.");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("flag-off identity: a dropped stream with no turn id fails exactly as before and is not resumed", async () => {
    const fetchMock = stubFetch(() =>
      streamResponse(["data:Hel\n\n", new TypeError("network error")], { "X-Session-Id": "s1" })
    );

    await expect(
      api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
        resumable: true,
        resume: FAST,
      })
    ).rejects.toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("a user Stop on a detached turn is sent to the server and not resumed", async () => {
    let controller: any = null;
    const fetchMock = stubFetch((url, init) => {
      if (url.endsWith("/api/agents/a1/chat")) return streamResponse([FIRST_HALF, "hang"], turnHeaders(), init?.signal);
      return statusResponse(202, { cancelRequested: true });
    });

    const promise = api.streamChat(
      "a1",
      "hi",
      null,
      (): void => undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {
        resumable: true,
        resume: FAST,
        onController: (c: any) => {
          controller = c;
        },
      }
    );
    await vi.waitFor(() => expect(controller).not.toBeNull());
    await vi.waitFor(() => expect(controller.getSnapshot().state).toBe("STREAMING"));
    controller.cancel("Cancelled by user");

    await expect(promise).rejects.toBeTruthy();
    await vi.waitFor(() =>
      expect(urls(fetchMock).some((u) => u.endsWith("/api/agents/a1/chat/sessions/s1/cancel"))).toBe(true)
    );
    expect(urls(fetchMock).some((u) => u.includes("/api/chat/turns/"))).toBe(false);
  });

  it("does not stop the server turn when the page's own signal fires (navigation)", async () => {
    const abort = new AbortController();
    const fetchMock = stubFetch((_url, init) => streamResponse([FIRST_HALF, "hang"], turnHeaders(), init?.signal));

    const promise = api.streamChat(
      "a1",
      "hi",
      null,
      (): void => undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {
        resumable: true,
        resume: FAST,
        signal: abort.signal,
      }
    );
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    abort.abort();

    await expect(promise).rejects.toBeTruthy();
    expect(urls(fetchMock).some((u) => u.includes("/cancel"))).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("reconciles a resumed reply with the server's copy when they differ", async () => {
    stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat"))
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      if (url.endsWith(`/api/chat/turns/${TURN}`))
        return statusResponse(200, { status: "complete", text: "Hello WORLD" });
      return streamResponse([SECOND_HALF]);
    });

    const reconciled: string[] = [];
    await api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
      resumable: true,
      resume: FAST,
      onReconcile: (text: string) => reconciled.push(text),
    });

    expect(reconciled).toEqual(["Hello WORLD"]);
  });

  it("leaves the reply alone when the server's copy matches", async () => {
    stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat"))
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      if (url.endsWith(`/api/chat/turns/${TURN}`))
        return statusResponse(200, { status: "complete", text: "Hello world" });
      return streamResponse([SECOND_HALF]);
    });

    const reconciled: string[] = [];
    await api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
      resumable: true,
      resume: FAST,
      onReconcile: (text: string) => reconciled.push(text),
    });

    expect(reconciled).toEqual([]);
  });

  it("falls back to the stored reply when the resume buffer dropped events (410)", async () => {
    stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat"))
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      if (url.endsWith(`/api/chat/turns/${TURN}/reply`))
        return statusResponse(200, { status: "complete", text: "Hello world" });
      return statusResponse(410, { error: "Part of this reply is no longer buffered." });
    });

    const reconciled: string[] = [];
    const sessionId = await api.streamChat(
      "a1",
      "hi",
      null,
      (): void => undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {
        resumable: true,
        resume: FAST,
        onReconcile: (text: string) => reconciled.push(text),
      }
    );

    expect(sessionId).toBe("s1");
    expect(reconciled).toEqual(["Hello world"]);
  });

  it("still fails with the resume status when there is no stored reply either", async () => {
    stubFetch((url) => {
      if (url.endsWith("/api/agents/a1/chat"))
        return streamResponse([FIRST_HALF, new TypeError("network error")], turnHeaders());
      if (url.endsWith(`/api/chat/turns/${TURN}/reply`)) return statusResponse(404, { error: "gone" });
      return statusResponse(404, { error: "gone" });
    });

    await expect(
      api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
        resumable: true,
        resume: FAST,
        onReconcile: (): void => undefined,
      })
    ).rejects.toMatchObject({ status: 404 });
  });

  it("remembers an in-flight turn for page-load restore, keeps it across a navigation abort, forgets it when done", async () => {
    const abort = new AbortController();
    stubFetch((_url, init) => streamResponse([FIRST_HALF, "hang"], turnHeaders(), init?.signal));

    const promise = api.streamChat(
      "a1",
      "hello there",
      null,
      (): void => undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {
        resumable: true,
        persistPending: true,
        signal: abort.signal,
        resume: FAST,
      }
    );
    await vi.waitFor(() => expect(loadPendingTurn("a1")).not.toBeNull());
    expect(loadPendingTurn("a1")).toMatchObject({
      turnId: TURN,
      agentId: "a1",
      sessionId: "s1",
      userMessage: "hello there",
    });

    // The page navigates away (its own signal): the turn is still running server-side, so the record stays.
    abort.abort();
    await expect(promise).rejects.toBeTruthy();
    expect(loadPendingTurn("a1")).not.toBeNull();
  });

  it("forgets the in-flight turn once it finished, and never records one unless asked", async () => {
    stubFetch(() => streamResponse([FIRST_HALF + SECOND_HALF], turnHeaders()));
    await api.streamChat("a1", "hi", null, (): void => undefined, undefined, undefined, undefined, undefined, {
      resumable: true,
      persistPending: true,
    });
    expect(loadPendingTurn("a1")).toBeNull();

    await api.streamChat("a1", "hi", null, (): void => undefined);
    expect(window.sessionStorage.getItem(pendingTurnKey("a1"))).toBeNull();
  });
});

describe("api.resumeChatTurn (page-load restore)", () => {
  beforeEach(() => {
    resetCircuitBreaker("chat-stream:resume");
  });

  afterEach(async () => {
    vi.unstubAllGlobals();
    await clearApiGetCache();
  });

  const pending = { turnId: TURN, agentId: "a1", sessionId: "s1", userMessage: "hi", startedAt: Date.now() };

  it("replays the whole turn from the first event and follows it to the end", async () => {
    const fetchMock = stubFetch(() => streamResponse([FIRST_HALF + SECOND_HALF]));

    const chunks: string[] = [];
    const sessionId = await api.resumeChatTurn("a1", pending, (c: string) => chunks.push(c));

    expect(sessionId).toBe("s1");
    expect(chunks.join("")).toBe("Hello world");
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/chat\/turns\/turn-12345678\/stream\?after=0$/);
  });

  it("rejects with the server's status when the turn is gone, so the host can say it could not be recovered", async () => {
    stubFetch(() => statusResponse(404, { error: "gone" }));

    await expect(api.resumeChatTurn("a1", pending, (): void => undefined)).rejects.toMatchObject({ status: 404 });
  });

  it("has a freshness limit matching the server buffer", () => {
    expect(PENDING_TURN_MAX_AGE_MS).toBe(600_000);
  });
});

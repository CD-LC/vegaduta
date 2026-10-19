// FE-030: the chat / voice SSE parsers now validate event payloads at the
// boundary (./schemas). These drive the real parsers end to end over a stubbed
// fetch body: valid events arrive as before, extra fields pass through, and a
// malformed event is skipped or degraded without killing the turn.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../auth", () => ({
  auth: { getToken: vi.fn().mockResolvedValue(null), refreshToken: vi.fn().mockResolvedValue(false) },
}));
const reportClientErrorMock = vi.hoisted(() => vi.fn());
vi.mock("../clientLogger", () => ({ reportClientError: reportClientErrorMock }));

import { chatApi } from "./chat";
import { STEP_EVENT_MARKER, clearApiGetCache, type AgentStepEvent } from "./core";
import { resetSchemaReportsForTests } from "./schemas";

function sseResponse(body: string, chunkSize = 11) {
  const bytes = new TextEncoder().encode(body);
  let offset = 0;
  return {
    ok: true,
    status: 200,
    headers: new Headers({ "X-Session-Id": "session-1" }),
    body: {
      getReader: () => ({
        read: async () => {
          if (offset >= bytes.length) return { done: true, value: undefined };
          const value = bytes.slice(offset, offset + chunkSize);
          offset += chunkSize;
          return { done: false, value };
        },
        cancel: async () => {},
      }),
    },
  };
}

function stubStream(wire: string) {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => Promise.resolve(sseResponse(wire)))
  );
}

const step = (json: string) => `data:${STEP_EVENT_MARKER}${json}\n\n`;

beforeEach(() => {
  reportClientErrorMock.mockReset();
  resetSchemaReportsForTests();
});

afterEach(async () => {
  vi.unstubAllGlobals();
  await clearApiGetCache();
});

describe("streamChat step events", () => {
  async function run(wire: string) {
    stubStream(wire);
    const chunks: string[] = [];
    const events: AgentStepEvent[] = [];
    const sessionId = await chatApi.streamChat(
      "agent-1",
      "hi",
      null,
      (c) => chunks.push(c),
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {
        onEvent: (e) => events.push(e),
      }
    );
    return { sessionId, chunks, events };
  }

  it("delivers valid step events and keeps them out of the text stream", async () => {
    const { sessionId, chunks, events } = await run(
      step('{"type":"tool_call","id":1,"tool":"web_search","args":"{\\"q\\":\\"x\\"}"}') +
        "data:Hello\n\n" +
        step('{"type":"tool_result","id":1,"tool":"web_search","preview":"3 results","ms":40}')
    );
    expect(sessionId).toBe("session-1");
    expect(chunks).toEqual(["Hello"]);
    expect(events).toEqual([
      { type: "tool_call", id: 1, tool: "web_search", args: '{"q":"x"}' },
      { type: "tool_result", id: 1, tool: "web_search", preview: "3 results", ms: 40 },
    ]);
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("passes unknown event types and extra fields through untouched", async () => {
    const { events } = await run(step('{"type":"plan","steps":["a","b"],"id":2}') + "data:ok\n\n");
    expect(events).toEqual([{ type: "plan", steps: ["a", "b"], id: 2 }]);
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("skips malformed step events without failing the turn", async () => {
    const { chunks, events } = await run(
      step("{broken") + step("[1,2]") + "data:still answering\n\n" + step('{"type":"heartbeat"}')
    );
    expect(chunks).toEqual(["still answering"]);
    expect(events).toEqual([{ type: "heartbeat" }]);
    expect(reportClientErrorMock).toHaveBeenCalled();
  });

  it("degrades a wrongly-typed field instead of dropping the whole event", async () => {
    const { events } = await run(step('{"type":"error","id":"x","message":"tool failed"}'));
    expect(events).toEqual([{ type: "error", message: "tool failed" }]);
    expect(reportClientErrorMock).toHaveBeenCalledTimes(1);
  });
});

describe("sendVoiceTurnStream events", () => {
  it("validates transcript / audio-chunk / done payloads, extras included", async () => {
    stubStream(
      'event:transcript\ndata:{"transcript":"hello","method":"sidecar","lang":"en"}\n\n' +
        "event:reply-delta\ndata:Hi\n\n" +
        'event:audio-chunk\ndata:{"seq":0,"textFragment":"Hi","audioBase64":"AAA=","method":"tts"}\n\n' +
        'event:done\ndata:{"sessionId":"s1","transcript":"hello","replyText":"Hi","speechText":"Hi","audioBase64":""}\n\n'
    );
    const onTranscript = vi.fn();
    const onAudioChunk = vi.fn();
    const onDone = vi.fn();
    const onReplyDelta = vi.fn();
    await chatApi.sendVoiceTurnStream("agent-1", new Blob(["a"], { type: "audio/webm" }), "s0", {
      onTranscript,
      onAudioChunk,
      onDone,
      onReplyDelta,
    });
    expect(onTranscript).toHaveBeenCalledWith({ transcript: "hello", method: "sidecar", lang: "en" });
    expect(onReplyDelta).toHaveBeenCalledWith("Hi");
    expect(onAudioChunk).toHaveBeenCalledWith({ seq: 0, textFragment: "Hi", audioBase64: "AAA=", method: "tts" });
    expect(onDone).toHaveBeenCalledWith({
      sessionId: "s1",
      transcript: "hello",
      replyText: "Hi",
      speechText: "Hi",
      audioBase64: "",
    });
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("logs and degrades a shape mismatch, and drops a non-object payload", async () => {
    stubStream(
      'event:transcript\ndata:{"transcript":42,"method":"sidecar"}\n\n' + 'event:audio-chunk\ndata:"just a string"\n\n'
    );
    const onTranscript = vi.fn();
    const onAudioChunk = vi.fn();
    await chatApi.sendVoiceTurnStream("agent-1", new Blob(["a"]), "s0", { onTranscript, onAudioChunk });
    expect(onTranscript).toHaveBeenCalledWith({ method: "sidecar" });
    expect(onAudioChunk).not.toHaveBeenCalled();
    expect(reportClientErrorMock).toHaveBeenCalledTimes(2);
  });
});

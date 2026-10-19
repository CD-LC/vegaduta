import { describe, it, expect, vi, afterEach } from "vitest";
import { voiceBlobExtension, api, clearApiGetCache } from "./api";

// apiFetch (api.ts) calls auth.getToken() on every request. Unmocked, that
// lazily constructs a real keycloak-js instance the first time anything in
// this file exercises a network call (getKeycloak() only short-circuits to
// null server-side, and jsdom's `window` is defined) - updateToken() then
// fails against a Keycloak that was never kc.init()'d, and the fallback
// kc.login() call itself throws (no realm/URL configured), which masks
// every assertion below the auth layer. Mocked out entirely rather than via
// auth.test.ts's KeycloakMock, since these tests don't exercise auth at all
// - a plain resolved null is exactly "no bearer token", the same as this
// endpoint being hit with the "security" profile off.
// refreshToken is part of the mocked surface because apiFetch now calls it on
// the one-shot 401 retry path (see api.ts). Resolving false = "the session is
// genuinely gone", which makes apiFetch return the original 401 response
// unchanged - i.e. exactly the pre-retry behaviour these tests assert against.
vi.mock("./auth", () => ({
  auth: { getToken: vi.fn().mockResolvedValue(null), refreshToken: vi.fn().mockResolvedValue(false) },
}));

// Regression coverage for the "Hold to talk sometimes doesn't work" bug:
// sendVoiceTurn used to pick the uploaded filename with a hardcoded
// `type.includes("wav") ? "turn.wav" : "turn.webm"` check, which silently
// mislabels every MediaRecorder output that is neither WAV nor WebM (most
// notably Safari/iOS, which ignores both requested mimeTypes CallPanel's
// startPushToTalk asks for and records "audio/mp4" instead). The mismatched
// filename/content-format then reaches the backend's format-sniffing STT
// tiers (sidecar's transcription.py picks its temp-file suffix from this
// same filename; SarvamSpeechToTextClient forwards it verbatim as the
// multipart part's filename) and can fail to decode - a failure that looks
// intermittent and browser-dependent rather than a hard, reproducible break.
describe("voiceBlobExtension", () => {
  it("maps a WAV blob to .wav", () => {
    expect(voiceBlobExtension("audio/wav")).toBe("wav");
  });

  it("maps a WebM blob (Chrome/Firefox's actual MediaRecorder output) to .webm", () => {
    expect(voiceBlobExtension("audio/webm")).toBe("webm");
    expect(voiceBlobExtension("audio/webm;codecs=opus")).toBe("webm");
  });

  it("maps Safari/iOS's actual MediaRecorder default (audio/mp4) to .mp4, not .webm", () => {
    expect(voiceBlobExtension("audio/mp4")).toBe("mp4");
    expect(voiceBlobExtension("audio/mp4;codecs=mp4a.40.2")).toBe("mp4");
  });

  it("maps an ogg/opus blob to .ogg", () => {
    expect(voiceBlobExtension("audio/ogg;codecs=opus")).toBe("ogg");
  });

  it("maps an mpeg/mp3 blob to .mp3", () => {
    expect(voiceBlobExtension("audio/mpeg")).toBe("mp3");
  });

  it("falls back to .webm for an unknown or empty mimeType", () => {
    expect(voiceBlobExtension("")).toBe("webm");
    expect(voiceBlobExtension("application/octet-stream")).toBe("webm");
  });
});

// sendVoiceTurnStream replaced the old single-JSON-response sendVoiceTurn
// with an SSE stream (see VoiceCallController#turn's javadoc for the event
// sequence). These tests mock fetch's Response with a text body - Response's
// own body getter turns that into the ReadableStream sendVoiceTurnStream
// reads via getReader(), same as a real streamed response would.
describe("api.sendVoiceTurnStream", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function sseResponse(body: string, status = 200) {
    return new Response(body, { status, headers: { "Content-Type": "text/event-stream" } });
  }

  it("dispatches transcript, reply-delta, audio-chunk, and done callbacks in order", async () => {
    const sse = [
      "event:transcript",
      'data:{"transcript":"hello world","method":"sarvam"}',
      "",
      "event:reply-delta",
      "data:Hi there",
      "",
      "event:reply-delta",
      "data:!",
      "",
      "event:audio-chunk",
      'data:{"seq":0,"textFragment":"Hi there!","audioBase64":"aGVsbG8=","method":"elevenlabs"}',
      "",
      "event:done",
      'data:{"sessionId":"sess-1","transcript":"hello world","replyText":"Hi there!","speechText":"Hi there!","audioBase64":""}',
      "",
    ].join("\n");
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(sseResponse(sse)))
    );

    const onTranscript = vi.fn();
    const onReplyDelta = vi.fn();
    const onAudioChunk = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();

    await api.sendVoiceTurnStream("agent-1", new Blob(["fake audio"], { type: "audio/webm" }), "sess-0", {
      onTranscript,
      onReplyDelta,
      onAudioChunk,
      onDone,
      onError,
    });

    expect(onTranscript).toHaveBeenCalledWith({ transcript: "hello world", method: "sarvam" });
    expect(onReplyDelta).toHaveBeenNthCalledWith(1, "Hi there");
    expect(onReplyDelta).toHaveBeenNthCalledWith(2, "!");
    expect(onAudioChunk).toHaveBeenCalledWith({
      seq: 0,
      textFragment: "Hi there!",
      audioBase64: "aGVsbG8=",
      method: "elevenlabs",
    });
    expect(onDone).toHaveBeenCalledWith({
      sessionId: "sess-1",
      transcript: "hello world",
      replyText: "Hi there!",
      speechText: "Hi there!",
      audioBase64: "",
    });
    expect(onError).not.toHaveBeenCalled();
  });

  it("reassembles a reply-delta payload split across multiple data: lines (an embedded newline in the token)", async () => {
    const sse = ["event:reply-delta", "data:line one", "data:line two", ""].join("\n");
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(sseResponse(sse)))
    );

    const onReplyDelta = vi.fn();
    await api.sendVoiceTurnStream("agent-1", new Blob(["fake audio"]), null, { onReplyDelta });

    // Consecutive data: lines within one event join with "\n" per the SSE spec.
    expect(onReplyDelta).toHaveBeenCalledWith("line one\nline two");
  });

  it("dispatches the error event instead of throwing when the stream fails mid-turn", async () => {
    const sse = [
      "event:transcript",
      'data:{"transcript":"hi","method":"sarvam"}',
      "",
      "event:error",
      "data:The voice reply was interrupted. Please try again.",
      "",
    ].join("\n");
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(sseResponse(sse)))
    );

    const onTranscript = vi.fn();
    const onError = vi.fn();
    await api.sendVoiceTurnStream("agent-1", new Blob(["fake audio"]), null, { onTranscript, onError });

    expect(onTranscript).toHaveBeenCalledWith({ transcript: "hi", method: "sarvam" });
    expect(onError).toHaveBeenCalledWith("The voice reply was interrupted. Please try again.");
  });

  it("throws with the backend's error message on a non-ok response (e.g. no speech detected)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response(JSON.stringify({ error: "No speech was detected." }), { status: 400 })))
    );

    await expect(api.sendVoiceTurnStream("agent-1", new Blob(["fake audio"]), null, {})).rejects.toThrow(
      "No speech was detected."
    );
  });
});

// Regression coverage for the reasoning-depth audit (2026-09-17): streamChat
// used to hand every "data:" LINE to onChunk as its own chunk. One backend
// chunk is one SSE EVENT, and Spring frames an event whose payload contains
// newlines as several data: lines - SseEmitter.SseEventBuilderImpl#data does
// StringUtils.replace(text, "\n", "\ndata:") - so every newline in an
// assistant reply was silently deleted by the client. It bites hardest on
// CAREFUL/RIGOROUS/PLANNED, where AgentChatService buffers the WHOLE answer
// and emits it as a single multi-line event: the entire reply's structure
// (paragraphs, lists, headings, code fences) vanished at once and the
// markdown renderer had nothing left to render.
describe("streamChat SSE framing", () => {
  function sseResponse(body: string, chunkSize = 7) {
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
        }),
      },
    };
  }

  afterEach(async () => {
    vi.unstubAllGlobals();
    // The module-level GET cache survives across tests - a case that cached
    // a success would otherwise feed every later case hitting the same path.
    await clearApiGetCache();
  });

  it("rejoins one buffered answer's data: lines, preserving every newline", async () => {
    // Exactly what Spring writes for the single chunk a buffered reasoning
    // depth emits: "# Heading\n\n- one\n- two".
    const wire = "data:# Heading\ndata:\ndata:- one\ndata:- two\n\n";
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(sseResponse(wire)))
    );

    const chunks: string[] = [];
    const sessionId = await api.streamChat("agent-1", "hi", null, (c: string) => chunks.push(c));

    expect(sessionId).toBe("session-1");
    expect(chunks).toEqual(["# Heading\n\n- one\n- two"]);
  });

  it("keeps one chunk per event on the FAST token stream, space-only tokens included", async () => {
    const wire = "data:Hello\n\ndata: \n\ndata:world\n\n";
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(sseResponse(wire)))
    );

    const chunks: string[] = [];
    await api.streamChat("agent-1", "hi", null, (c: string) => chunks.push(c));

    expect(chunks).toEqual(["Hello", " ", "world"]);
    expect(chunks.join("")).toBe("Hello world");
  });

  it("flushes a final event the server never terminated with a blank line", async () => {
    const wire = "data:only line";
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(sseResponse(wire)))
    );

    const chunks: string[] = [];
    await api.streamChat("agent-1", "hi", null, (c: string) => chunks.push(c));

    expect(chunks).toEqual(["only line"]);
  });
});

// Guardrails API surface. These five methods are the only way the console can
// reach core/guardrails' GuardrailRuleController and core/agents'
// AgentGuardrailController - endpoints that existed for months with zero
// callers anywhere in web/. What is pinned here is the shape a UI depends on
// and a refactor could silently break: the exact paths, the HTTP verbs (the
// rules resource is POST/PUT/DELETE, not PATCH), the fact that the agent PATCH
// sends the complete desired SET rather than a delta, and that DELETE does not
// try to parse a body - a 204 through toJson() would throw.
describe("guardrails API", () => {
  // The fetch mocks below declare fetch's own (url, init) parameters. An
  // untyped `vi.fn(() => ...)` infers a zero-length call tuple, so reading
  // `mock.calls[0]` as [url, init] is a type error - one `next build` started
  // enforcing in 16.3 (it failed the production image build, 2026-09-18).
  function jsonResponse(payload: unknown, status = 200) {
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: new Headers({ "Content-Type": "application/json" }),
      json: async () => payload,
      text: async () => JSON.stringify(payload),
    };
  }

  function noContentResponse() {
    return {
      ok: true,
      status: 204,
      headers: new Headers(),
      json: async (): Promise<undefined> => undefined,
      text: async () => "",
    };
  }

  afterEach(async () => {
    vi.unstubAllGlobals();
    await clearApiGetCache();
  });

  it("reads an agent's guardrails from the per-agent endpoint", async () => {
    const fetchMock = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse({ enabledGuardrails: ["pii"] }))
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await api.getAgentGuardrails("agent-1");

    expect(result.enabledGuardrails).toEqual(["pii"]);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/api/agents/agent-1/guardrails");
    expect(init?.method ?? "GET").toBe("GET");
  });

  it("PATCHes the complete guardrail set, so an empty array clears every one", async () => {
    const fetchMock = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse({ enabledGuardrails: [] }))
    );
    vi.stubGlobal("fetch", fetchMock);

    await api.setAgentGuardrails("agent-1", []);

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/api/agents/agent-1/guardrails");
    expect(init?.method).toBe("PATCH");
    expect(JSON.parse(String(init?.body))).toEqual({ guardrails: [] });
  });

  it("creates a rule with POST and sends the pattern and action verbatim", async () => {
    const fetchMock = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse({ id: "rule-1" }, 201))
    );
    vi.stubGlobal("fetch", fetchMock);

    await api.createGuardrailRule({ name: "Codenames", pattern: "bluejay", action: "BLOCK", enabled: true });

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/api/guardrails/rules");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toMatchObject({ pattern: "bluejay", action: "BLOCK" });
  });

  it("updates a rule with PUT against its id", async () => {
    const fetchMock = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse({ id: "rule-1" }))
    );
    vi.stubGlobal("fetch", fetchMock);

    await api.updateGuardrailRule("rule-1", {
      name: "Codenames",
      pattern: "bluejay",
      action: "REDACT",
      enabled: false,
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/api/guardrails/rules/rule-1");
    expect(init?.method).toBe("PUT");
  });

  it("deletes a rule without trying to parse the 204 body", async () => {
    const fetchMock = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) => Promise.resolve(noContentResponse()));
    vi.stubGlobal("fetch", fetchMock);

    await expect(api.deleteGuardrailRule("rule-1")).resolves.toBeDefined();

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/api/guardrails/rules/rule-1");
    expect(init?.method).toBe("DELETE");
  });
});

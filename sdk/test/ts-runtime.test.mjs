// Tests for the hand-written TypeScript runtime (sdk/typescript/src/runtime.ts).
// They import the .ts file directly, which needs a Node with TypeScript type stripping enabled
// (Node 22.18+/24). On older Node the tests are skipped; compile with tsc to verify there.
import assert from "node:assert/strict";
import { test } from "node:test";

let rt = null;
try {
  rt = await import("../typescript/src/runtime.ts");
} catch (err) {
  // Skip only when this Node cannot load .ts files at all; a real syntax/type-stripping
  // error in runtime.ts must fail the run instead of hiding as a skip.
  const unsupported =
    err &&
    (err.code === "ERR_UNKNOWN_FILE_EXTENSION" ||
      err.code === "ERR_UNSUPPORTED_NODE_MODULES_TYPE_STRIPPING");
  if (!unsupported) throw err;
  rt = null;
}
const skip = rt ? false : "needs Node with TypeScript type stripping (Node 22.18+ / 24)";

const enc = new TextEncoder();

function sseResponse(text) {
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(enc.encode(text));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
}

async function collect(iterable) {
  const out = [];
  for await (const item of iterable) out.push(item);
  return out;
}

test("SseDecoder reassembles fragmented events and keeps a single-space chunk", { skip }, () => {
  const d = new rt.SseDecoder();
  assert.deepEqual(d.push("data:Hel"), []);
  assert.deepEqual(d.push("lo\n\ndata: \n"), ["Hello"]);
  assert.deepEqual(d.push("\n"), [" "]);
  assert.deepEqual(d.push("data:a\ndata:b\n\ndata:tail"), ["a\nb"]);
  assert.deepEqual(d.flush(), ["tail"]);
});

test("HttpClient builds the URL, sends the bearer token and parses JSON", { skip }, async () => {
  const calls = [];
  const client = new rt.HttpClient({
    baseUrl: "https://x.test/",
    token: async () => "tok",
    headers: { "X-Default": "1" },
    fetch: async (url, init) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "content-type": "application/json" } });
    },
  });
  const out = await client.request({
    method: "POST",
    path: "/api/a",
    query: { limit: 2, tag: ["a", "b c"], skip: undefined },
    headers: { "X-Trace": "t" },
    body: { hello: "world" },
  });
  assert.deepEqual(out, { ok: true });
  assert.equal(calls[0].url, "https://x.test/api/a?limit=2&tag=a&tag=b%20c");
  assert.equal(calls[0].init.method, "POST");
  assert.equal(calls[0].init.headers.get("Authorization"), "Bearer tok");
  assert.equal(calls[0].init.headers.get("X-Trace"), "t");
  assert.equal(calls[0].init.headers.get("X-Default"), "1");
  assert.equal(calls[0].init.headers.get("Content-Type"), "application/json");
  assert.equal(calls[0].init.body, '{"hello":"world"}');
});

test("non-2xx responses become ApiError with status and parsed body", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async () =>
      new Response(JSON.stringify({ status: 404, error: "nope" }), { status: 404, headers: { "content-type": "application/json" } }),
  });
  await assert.rejects(
    () => client.request({ method: "GET", path: "/missing" }),
    (err) => err instanceof rt.ApiError && err.status === 404 && err.message === "nope" && err.body.error === "nope",
  );
});

test("void and text responses", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async (url) => (String(url).endsWith("/t") ? new Response("hello", { status: 200 }) : new Response(null, { status: 204 })),
  });
  assert.equal(await client.request({ method: "DELETE", path: "/d", response: "void" }), undefined);
  assert.equal(await client.request({ method: "GET", path: "/t", response: "text" }), "hello");
});

test("stream yields event payloads", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async (url, init) => {
      assert.equal(init.headers.get("Accept"), "text/event-stream");
      return sseResponse("data:Hel\n\ndata:lo\n\ndata: \n\ndata:l1\ndata:l2\n\n");
    },
  });
  const events = await collect(client.stream({ method: "POST", path: "/chat", body: { message: "hi" } }));
  assert.deepEqual(events, ["Hel", "lo", " ", "l1\nl2"]);
});

test("stream error marker raises StreamInterruptedError", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async () => sseResponse(`data:partial\n\ndata:${rt.STREAM_ERROR_MARKER}boom\n\n`),
  });
  const seen = [];
  await assert.rejects(
    async () => {
      for await (const e of client.stream({ method: "GET", path: "/chat" })) seen.push(e);
    },
    (err) => err instanceof rt.StreamInterruptedError && err.message === "boom",
  );
  assert.deepEqual(seen, ["partial"]);
});

test("HttpClient has no built-in deployment: baseUrl or VEGADUTA_BASE_URL is required", { skip }, () => {
  const saved = process.env.VEGADUTA_BASE_URL;
  delete process.env.VEGADUTA_BASE_URL;
  try {
    assert.throws(() => new rt.HttpClient({}), /VEGADUTA_BASE_URL/);
    process.env.VEGADUTA_BASE_URL = "https://api.example.test/";
    assert.doesNotThrow(() => new rt.HttpClient({}));
  } finally {
    if (saved === undefined) delete process.env.VEGADUTA_BASE_URL;
    else process.env.VEGADUTA_BASE_URL = saved;
  }
});

test("GET retries a 429 honouring Retry-After, then succeeds", { skip }, async () => {
  let calls = 0;
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async () => {
      calls += 1;
      if (calls === 1) {
        return new Response(JSON.stringify({ status: 429, error: "slow" }), {
          status: 429,
          headers: { "retry-after": "0" },
        });
      }
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    },
  });
  assert.deepEqual(await client.request({ method: "GET", path: "/f" }), { ok: true });
  assert.equal(calls, 2);
});

test("POST is never retried", { skip }, async () => {
  let calls = 0;
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async () => {
      calls += 1;
      return new Response(JSON.stringify({ status: 503, error: "down" }), { status: 503 });
    },
  });
  await assert.rejects(() => client.request({ method: "POST", path: "/x", body: {} }), rt.ApiError);
  assert.equal(calls, 1);
});

test("ApiError carries headers, code and retryAfterMs", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    maxRetries: 0,
    fetch: async () =>
      new Response(JSON.stringify({ status: 429, error: "slow", code: "RATE_LIMITED" }), {
        status: 429,
        headers: { "retry-after": "1.5", "x-request-id": "req-9" },
      }),
  });
  await assert.rejects(
    () => client.request({ method: "GET", path: "/x" }),
    (err) =>
      err instanceof rt.ApiError &&
      err.status === 429 &&
      err.code === "RATE_LIMITED" &&
      err.retryAfterMs === 1500 &&
      err.headers.get("x-request-id") === "req-9",
  );
});

test("an unparseable 200 raises ApiError, not a raw SyntaxError", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async () => new Response("<html>oops</html>", { status: 200 }),
  });
  await assert.rejects(
    () => client.request({ method: "GET", path: "/x" }),
    (err) => err instanceof rt.ApiError && err.message.includes("unparseable"),
  );
});

test("a failed fetch becomes NetworkError (POST, unretried)", { skip }, async () => {
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    fetch: async () => {
      throw new TypeError("fetch failed");
    },
  });
  await assert.rejects(
    () => client.request({ method: "POST", path: "/x", body: {} }),
    (err) => err instanceof rt.NetworkError && err.cause instanceof TypeError,
  );
});

test("a callable token is re-resolved once on 401", { skip }, async () => {
  const tokens = ["stale", "fresh"];
  const seen = [];
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    token: () => tokens.shift(),
    fetch: async (url, init) => {
      seen.push(init.headers.get("Authorization"));
      if (seen.length === 1) return new Response(JSON.stringify({ error: "expired" }), { status: 401 });
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    },
  });
  assert.deepEqual(await client.request({ method: "GET", path: "/x" }), { ok: true });
  assert.deepEqual(seen, ["Bearer stale", "Bearer fresh"]);
});

test("requests carry the SDK User-Agent", { skip }, async () => {
  let ua = null;
  const client = new rt.HttpClient({
    baseUrl: "https://x.test",
    userAgent: "my-app/9",
    fetch: async (url, init) => {
      ua = init.headers.get("User-Agent");
      return new Response("{}", { status: 200 });
    },
  });
  await client.request({ method: "GET", path: "/x" });
  assert.equal(ua, "vegaduta-sdk-ts my-app/9");
});

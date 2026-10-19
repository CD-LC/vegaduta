// Mock Ollama-compatible model server for platform-overhead load tests and the stage e2e suite.
//
// It answers the three calls the platform makes to a tenant-hosted Ollama
// (GET /api/tags for validation, POST /v1/chat/completions for a turn, plus native /api/chat)
// with a FIXED, known latency, so that
//     platform overhead = measured time-to-first-token - FIRST_TOKEN_MS
// and no real model, key or cost is involved. Dependency-free (Node 18+).
//
//   FIRST_TOKEN_MS  delay before the first streamed token      (default 50)
//   TOKEN_MS        delay between later tokens                  (default 5)
//   TOKENS          number of tokens in the reply               (default 20)
//   PORT                                                        (default 11434)
//
// DEFAULT BEHAVIOUR IS UNCHANGED for model "mock:latest" and any prompt without a trigger below, so the 32-user
// benchmark numbers stay comparable: a reply of TOKENS x "ok" at the timing above.
//
// Scriptable behaviour (all opt-in, driven by the request, so a load test never hits it by accident):
//   slow profile     a model whose name starts with "mock-slow" (e.g. mock-slow:latest) streams with
//                    SLOW_FIRST_TOKEN_MS (300), SLOW_TOKEN_MS (300), SLOW_TOKENS (60): ~18 s per reply, long enough to
//                    stop mid-stream or reload and resume.
//   persona marker   a system prompt containing "MOCK_MARKER:<word>" makes the reply "MOCK_MARKER:<word> ok ok ...",
//                    so a test can prove the agent's system prompt / persona reached the model.
//   tool call        a last user message containing "mock:call_tool [name] [{json args}]" makes the reply a tool call
//                    (OpenAI /v1/chat/completions only) when the request offers tools; without a name it calls the
//                    first offered tool. When the last message is the tool result the reply is
//                    "mock: tool result received: <first 200 chars>", so the agent loop ends.
//   judge prompts    an LLM-as-judge prompt ("SCORE: <number from 0.0 to 1.0>" format, or the calibration
//                    "VERDICT: 1 or VERDICT: 2" format) is answered with a well-formed verdict, so eval runs finish.
"use strict";
const http = require("node:http");

const num = (v, d) => (Number.isFinite(Number(v)) && v !== undefined && v !== "" ? Number(v) : d);
const FIRST_TOKEN_MS = num(process.env.FIRST_TOKEN_MS, 50);
const TOKEN_MS = num(process.env.TOKEN_MS, 5);
const TOKENS = num(process.env.TOKENS, 20);
const SLOW_FIRST_TOKEN_MS = num(process.env.SLOW_FIRST_TOKEN_MS, 300);
const SLOW_TOKEN_MS = num(process.env.SLOW_TOKEN_MS, 300);
const SLOW_TOKENS = num(process.env.SLOW_TOKENS, 60);
const PORT = num(process.env.PORT, 11434);
const MODELS = ["mock:latest", "mock-slow:latest"];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const json = (res, code, body) => {
  res.writeHead(code, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
};

const textOf = (m) => {
  if (!m) return "";
  if (typeof m.content === "string") return m.content;
  if (Array.isArray(m.content)) return m.content.map((p) => (typeof p === "string" ? p : (p && p.text) || "")).join("");
  return "";
};

const profileFor = (model) =>
  String(model || "").startsWith("mock-slow")
    ? { first: SLOW_FIRST_TOKEN_MS, token: SLOW_TOKEN_MS, tokens: SLOW_TOKENS }
    : { first: FIRST_TOKEN_MS, token: TOKEN_MS, tokens: TOKENS };

// What to answer: { pieces: [...text chunks] } or { toolCall: { name, args } }. The default is `ok ok ok ...`.
function decide(body, profile) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const all = messages.map(textOf).join("\n");
  const last = messages.length ? messages[messages.length - 1] : null;
  const okPieces = (n) => Array.from({ length: n }, (_, i) => (i === 0 ? "ok" : " ok"));
  const words = (t) => t.split(/(?= )/);

  if (/VERDICT:\s*1\s+or\s+VERDICT:\s*2/.test(all)) return { pieces: ["VERDICT: 1"] };
  if (/SCORE:\s*<number from 0\.0 to 1\.0>/.test(all)) {
    const lines = ["SCORE: 0.9"];
    if (/UNSUPPORTED:/.test(all)) lines.push("UNSUPPORTED: NONE");
    lines.push("FEEDBACK: mock judge, fixed verdict.");
    return { pieces: words(lines.join("\n")) };
  }
  if (last && last.role === "tool") {
    return { pieces: words("mock: tool result received: " + textOf(last).slice(0, 200)) };
  }
  const lastUser = [...messages].reverse().find((m) => m.role === "user");
  const trigger = /mock:call_tool(?:\s+([A-Za-z_][\w.-]*))?(?:\s+(\{[\s\S]*\}))?/.exec(textOf(lastUser));
  if (trigger) {
    const offered = (Array.isArray(body.tools) ? body.tools : []).map((t) => t && t.function && t.function.name).filter(Boolean);
    const name = trigger[1] || offered[0];
    if (name && offered.includes(name)) {
      let args = {};
      try { if (trigger[2]) args = JSON.parse(trigger[2]); } catch (_) { /* keep {} */ }
      return { toolCall: { name, args } };
    }
    return { pieces: words("mock: no matching tool offered" + (name ? ": " + name : "")) };
  }
  const marker = /MOCK_MARKER:(\S+)/.exec(messages.filter((m) => m.role === "system").map(textOf).join("\n"));
  const pieces = okPieces(profile.tokens);
  if (marker) pieces[0] = "MOCK_MARKER:" + marker[1] + " ok";
  return { pieces };
}

async function chat(req, res, body) {
  const model = body.model || "mock";
  const p = profileFor(model);
  const plan = decide(body, p);
  const pieces = plan.pieces || ["ok"]; // native /api/chat has no tool-call support in the mock
  const now = () => new Date().toISOString();
  if (body.stream === false) {
    await sleep(p.first + p.token * pieces.length);
    return json(res, 200, {
      model, created_at: now(), done: true, done_reason: "stop",
      message: { role: "assistant", content: pieces.join("") },
      prompt_eval_count: 10, eval_count: pieces.length,
    });
  }
  res.writeHead(200, { "Content-Type": "application/x-ndjson" });
  await sleep(p.first);
  for (let i = 0; i < pieces.length; i++) {
    res.write(JSON.stringify({
      model, created_at: now(), done: false,
      message: { role: "assistant", content: pieces[i] },
    }) + "\n");
    if (i < pieces.length - 1) await sleep(p.token);
  }
  res.end(JSON.stringify({
    model, created_at: now(), done: true, done_reason: "stop",
    message: { role: "assistant", content: "" },
    prompt_eval_count: 10, eval_count: pieces.length,
  }) + "\n");
}

// OpenAI-compatible endpoint (what the platform's Ollama client really uses): SSE chunks, then [DONE].
async function completions(req, res, body) {
  const model = body.model || "mock";
  const p = profileFor(model);
  const plan = decide(body, p);
  const id = "chatcmpl-mock";
  const created = Math.floor(Date.now() / 1000);
  const base = { id, object: "chat.completion.chunk", created, model };
  const toolCalls = plan.toolCall
    ? [{ index: 0, id: "call_mock_1", type: "function", function: { name: plan.toolCall.name, arguments: JSON.stringify(plan.toolCall.args) } }]
    : null;
  const pieces = plan.pieces || [];
  const used = toolCalls ? 5 : pieces.length;
  if (body.stream !== true) {
    await sleep(p.first + p.token * used);
    const message = toolCalls
      ? { role: "assistant", content: null, tool_calls: toolCalls.map(({ index, ...c }) => c) }
      : { role: "assistant", content: pieces.join("") };
    return json(res, 200, {
      id, object: "chat.completion", created, model,
      choices: [{ index: 0, message, finish_reason: toolCalls ? "tool_calls" : "stop" }],
      usage: { prompt_tokens: 10, completion_tokens: used, total_tokens: 10 + used },
    });
  }
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
  const send = (o) => res.write("data: " + JSON.stringify(o) + "\n\n");
  await sleep(p.first);
  send({ ...base, choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] });
  if (toolCalls) {
    send({ ...base, choices: [{ index: 0, delta: { tool_calls: toolCalls }, finish_reason: null }] });
    send({ ...base, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] });
  } else {
    for (let i = 0; i < pieces.length; i++) {
      send({ ...base, choices: [{ index: 0, delta: { content: pieces[i] }, finish_reason: null }] });
      if (i < pieces.length - 1) await sleep(p.token);
    }
    send({ ...base, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] });
  }
  if (body.stream_options && body.stream_options.include_usage) {
    send({ ...base, choices: [], usage: { prompt_tokens: 10, completion_tokens: used, total_tokens: 10 + used } });
  }
  res.end("data: [DONE]\n\n");
}

http.createServer((req, res) => {
  const url = req.url.split("?")[0];
  if (req.method === "GET" && url === "/healthz") return json(res, 200, { ok: true });
  if (req.method === "GET" && url === "/api/tags") {
    return json(res, 200, { models: MODELS.map((name) => ({ name, model: name, size: 1 })) });
  }
  if (req.method === "GET" && url === "/v1/models") return json(res, 200, { object: "list", data: MODELS.map((id) => ({ id, object: "model" })) });
  if (req.method === "GET" && url === "/api/version") return json(res, 200, { version: "0.0.0-mock" });
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch (_) { /* tolerate */ }
    if (req.method === "POST" && url === "/v1/chat/completions") return completions(req, res, body).catch(() => res.end());
    if (req.method === "POST" && url === "/api/chat") return chat(req, res, body).catch(() => res.end());
    if (req.method === "POST" && url === "/api/show") {
      return json(res, 200, { details: { family: "mock" }, model_info: {}, capabilities: ["completion", "tools"] });
    }
    json(res, 404, { error: "not found: " + url });
  });
}).listen(PORT, () => console.log(`mock-llm on :${PORT} first_token=${FIRST_TOKEN_MS}ms token=${TOKEN_MS}ms tokens=${TOKENS} slow=${SLOW_FIRST_TOKEN_MS}/${SLOW_TOKEN_MS}ms x${SLOW_TOKENS}`));

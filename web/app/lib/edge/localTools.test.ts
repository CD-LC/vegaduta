// E-2c — the local (no-account, no-network, no-spend) tool registry.
//
// Two kinds of test here, deliberately:
//
//  * BEHAVIOUR — each tool's happy path, its refusal path, and a malformed-args
//    path, plus proof that a handler which throws, hangs or returns the wrong
//    type comes back as a typed EdgeToolExecuteResult rather than an exception.
//  * PROPERTY — the four claims the module's header makes. The no-network one
//    is checked twice over, structurally (the source has no value import and no
//    network identifier outside comments) and dynamically (every tool runs with
//    throwing stubs over fetch/XHR/WebSocket/EventSource/sendBeacon). A comment
//    promising "this cannot reach the network" is worth nothing on its own;
//    these two together are what make the promise falsifiable.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  LOCAL_TOOL_PREFIX,
  LOCAL_TOOL_TIMEOUT_MS,
  MAX_RESULT_CHARS,
  TOOL_ADVERTISEMENT_CHAR_BUDGET,
  findLocalTool,
  listLocalTools,
  runLocalTool,
  toolAdvertisementBudgetFor,
  type LocalTool,
} from "./localTools";
import { WEBLLM_BASELINE_CONTEXT_WINDOW, type EdgeCapabilities } from "./capabilities";

// listLocalTools takes EdgeCapabilities; none of the phase-1 four reads it, so
// the tests pass both a realistic object and null (the "detection hasn't
// finished" case the function documents).
const CAPS = {
  version: 1,
  detectedAt: 1,
  webgpu: { available: true, maxBufferSize: 1, maxStorageBufferBindingSize: 1, shaderF16: true },
  promptApi: { shape: "none", availability: "unavailable" },
  deviceMemoryGB: 8,
  hardwareConcurrency: 8,
  connection: { saveData: false, effectiveType: "4g" },
} as unknown as EdgeCapabilities;

/** strict:false — only the explicit literal comparison narrows the
 * EdgeToolExecuteResult union (the same gotcha toolBridge.ts documents). */
async function ok(name: string, args: unknown): Promise<string> {
  const tool = findLocalTool(name);
  expect(tool).not.toBeNull();
  const result = await runLocalTool(tool as LocalTool, args);
  if (result.ok === true) return result.result;
  throw new Error(`expected ok, got ${result.code}: ${result.detail}`);
}

async function notOk(name: string, args: unknown): Promise<{ code: string; detail: string }> {
  const tool = findLocalTool(name);
  expect(tool).not.toBeNull();
  const result = await runLocalTool(tool as LocalTool, args);
  if (result.ok === false) return { code: result.code, detail: result.detail };
  throw new Error(`expected a failure, got ok: ${result.result}`);
}

// ---------------------------------------------------------------------------
// Registry shape
// ---------------------------------------------------------------------------

describe("registry", () => {
  it("advertises the pure-compute and folder tools, all namespaced and all local", () => {
    const tools = listLocalTools(CAPS);
    // The network set is absent because its preference defaults OFF - asserted
    // as its own test below, since that default is a security decision and a
    // regression in it must fail loudly rather than widen this list quietly.
    // The exact set now depends on the prompt budget, so this pins the
    // PRIORITY contract instead of a frozen list: the pure-compute core and
    // the folder tools come first and always fit.
    expect(tools.map((t) => t.name).slice(0, 7)).toEqual([
      "local_calculate",
      "local_datetime",
      "local_convert",
      "local_transform",
      "local_list_files",
      "local_read_file",
      "local_search_files",
    ]);
    for (const tool of tools) {
      expect(tool.name.startsWith(LOCAL_TOOL_PREFIX)).toBe(true);
      expect(tool.network).toBe("none");
      expect(tool.consequential).toBe(false);
      expect(typeof tool.run).toBe("function");
      expect(tool.description.length).toBeGreaterThan(0);
      // One line each — a wrapped paragraph in a description is paid for out
      // of the person's context window on every single turn.
      expect(tool.description).not.toContain("\n");
    }
  });

  it("returns the ungated tools when capability detection has not answered yet", () => {
    // Budget-trimmed, so assert the floor that matters: capability detection
    // failing must never cost the person their pure-compute tools.
    expect(listLocalTools(null as unknown as EdgeCapabilities).length).toBeGreaterThanOrEqual(7);
  });

  it("keeps third-party lookup tools out of the list until the person opts in", () => {
    // The default IS the security property: with folder reads live, an outbound
    // query tool turns a file's own text into an exfiltration channel (see
    // allLocalTools' comment). A tool that is not advertised is also not
    // executable - findLocalTool resolves through the same function.
    expect(listLocalTools(CAPS, 99_000).some((t) => t.network !== "none")).toBe(false);
    expect(findLocalTool("local_wikipedia")).toBeNull();

    localStorage.setItem("edge.networkTools", "1");
    try {
      // A generous budget: this test is about whether the preference
      // REGISTERS the network set, not about what fits in a small window.
      const opted = listLocalTools(CAPS, 99_000);
      expect(opted.some((t) => t.network !== "none")).toBe(true);
      // Anything reaching the network must SAY so, in the text the model
      // repeats to the person.
      for (const tool of opted.filter((t) => t.network !== "none")) {
        expect(tool.description.toLowerCase()).toMatch(/send/);
      }
      expect(findLocalTool("local_wikipedia")).not.toBeNull();
    } finally {
      localStorage.removeItem("edge.networkTools");
    }
  });

  it("keeps the advertised schema block inside the tier-1 context budget", () => {
    // What actually reaches the system prompt is {name, description,
    // inputSchema} per tool. Measured by this assertion at 1827 characters for
    // the four — ~522 tokens at tier1's chars/3.5 estimate, against the
    // ~2744-token whole-prompt budget its constants imply on a 4096 window.
    // Most of that is JSON Schema's own boilerplate, not prose. The cap is a
    // ratchet with ~10% headroom: a fifth tool is fine, a chatty schema is not.
    const total = listLocalTools(CAPS)
      .map((t) => JSON.stringify({ name: t.name, description: t.description, inputSchema: t.inputSchema }).length)
      .reduce((a, b) => a + b, 0);
    // The registry now TRIMS to TOOL_ADVERTISEMENT_CHAR_BUDGET rather than
    // advertising everything, so this asserts the budget is actually enforced
    // -- the failure this replaces (4,606 chars, ~48% of a 4096-window model's
    // whole prompt budget) is exactly what the trim exists to prevent.
    expect(total).toBeLessThanOrEqual(TOOL_ADVERTISEMENT_CHAR_BUDGET);
  });

  it("scales the advertisement budget with the model's context window", () => {
    // The 3,200-char baseline fits compute+folder only — media generation is
    // always trimmed away. A model with a bigger window must be offered the
    // whole registry, which is the "caller passes a larger budget" contract
    // the budget's doc names.
    expect(toolAdvertisementBudgetFor(WEBLLM_BASELINE_CONTEXT_WINDOW)).toBe(TOOL_ADVERTISEMENT_CHAR_BUDGET);
    expect(toolAdvertisementBudgetFor(8192)).toBe(TOOL_ADVERTISEMENT_CHAR_BUDGET * 2);
    // Absent/absurd input answers the baseline, never less.
    expect(toolAdvertisementBudgetFor(null)).toBe(TOOL_ADVERTISEMENT_CHAR_BUDGET);
    expect(toolAdvertisementBudgetFor(0)).toBe(TOOL_ADVERTISEMENT_CHAR_BUDGET);
    expect(toolAdvertisementBudgetFor(NaN)).toBe(TOOL_ADVERTISEMENT_CHAR_BUDGET);

    const names = (b: number) => listLocalTools(CAPS, b).map((t) => t.name);
    expect(names(toolAdvertisementBudgetFor(WEBLLM_BASELINE_CONTEXT_WINDOW))).not.toContain("local_generate_image");
    const bigger = names(toolAdvertisementBudgetFor(8192));
    expect(bigger).toContain("local_generate_image");
    expect(bigger).toContain("local_generate_audio");
  });

  it("hides image generation on adapters without shader-f16 — fp16 weights cannot run there", () => {
    // SD-Turbo's ONNX weights are fp16-only: an adapter that exposes WebGPU
    // without the shader-f16 feature would fail every session create, so the
    // tool is gated off rather than advertised into guaranteed refusal. Audio
    // stays — it falls back to q4 weights.
    const noF16 = {
      ...CAPS,
      webgpu: { ...CAPS.webgpu, shaderF16: false },
    } as unknown as EdgeCapabilities;
    const names = listLocalTools(noF16, 99_000).map((t) => t.name);
    expect(names).not.toContain("local_generate_image");
    expect(names).toContain("local_generate_audio");
  });

  it("resolves tools by exact name only, so a local tool cannot shadow a server tool", () => {
    expect(findLocalTool("local_calculate")).not.toBeNull();
    // The bare name is what the server registry would use; it must not resolve.
    expect(findLocalTool("calculate")).toBeNull();
    expect(findLocalTool("LOCAL_CALCULATE")).toBeNull();
    expect(findLocalTool("")).toBeNull();
    expect(findLocalTool(undefined as unknown as string)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// local_calculate
// ---------------------------------------------------------------------------

describe("local_calculate", () => {
  it("evaluates precedence, parentheses, unary minus and right-associative ^", async () => {
    expect(await ok("local_calculate", { expression: "2 + 3 * 4" })).toBe("2 + 3 * 4 = 14");
    expect(await ok("local_calculate", { expression: "(2+3)*4" })).toBe("(2+3)*4 = 20");
    expect(await ok("local_calculate", { expression: "2^3^2" })).toBe("2^3^2 = 512");
    expect(await ok("local_calculate", { expression: "-2^2" })).toBe("-2^2 = -4");
    expect(await ok("local_calculate", { expression: "7 % 4" })).toBe("7 % 4 = 3");
    expect(await ok("local_calculate", { expression: "max(3, 7) - min(3, 7)" })).toContain("= 4");
    expect(await ok("local_calculate", { expression: "sqrt(9)" })).toBe("sqrt(9) = 3");
  });

  it("kills float noise instead of reporting 0.30000000000000004", async () => {
    expect(await ok("local_calculate", { expression: "0.1 + 0.2" })).toBe("0.1 + 0.2 = 0.3");
  });

  it("refuses non-finite answers rather than telling the model Infinity", async () => {
    expect((await notOk("local_calculate", { expression: "1/0" })).code).toBe("rejected");
    expect((await notOk("local_calculate", { expression: "sqrt(0-1)" })).detail).toContain("not a number");
  });

  it("refuses unknown names and bad syntax, naming what it does support", async () => {
    const unknown = await notOk("local_calculate", { expression: "frobnicate(2)" });
    expect(unknown.code).toBe("rejected");
    expect(unknown.detail).toContain("sqrt");
    expect((await notOk("local_calculate", { expression: "(2+3" })).detail).toContain('Missing ")"');
    expect((await notOk("local_calculate", { expression: "2 + + " })).code).toBe("rejected");
    expect((await notOk("local_calculate", { expression: "2 3" })).detail).toContain("after a complete expression");
    // The token that would matter most if this were an evaluator.
    expect((await notOk("local_calculate", { expression: "process.exit(1)" })).code).toBe("rejected");
  });

  it("refuses malformed arguments", async () => {
    expect((await notOk("local_calculate", { expression: 42 })).detail).toContain("must be a string");
    expect((await notOk("local_calculate", {})).detail).toContain("must be a string");
    expect((await notOk("local_calculate", { expression: "" })).detail).toContain("empty");
    expect((await notOk("local_calculate", { expression: "1+".repeat(400) + "1" })).detail).toContain("at most 512");
    expect((await notOk("local_calculate", "2+2")).detail).toContain("expects a JSON object");
    expect((await notOk("local_calculate", [1, 2])).detail).toContain("an array");
  });

  it("never evaluates its input as code", () => {
    // Comment-stripped: the parser's own header says "NO eval, NO new
    // Function" in prose, and the scan must read the code, not the promise.
    const source = readSourceCode();
    expect(source).not.toMatch(/\beval\s*\(/);
    expect(source).not.toMatch(/new\s+Function\b/);
  });
});

// ---------------------------------------------------------------------------
// local_datetime
// ---------------------------------------------------------------------------

describe("local_datetime", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-12T09:00:05.000Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("reports the instant in a named IANA zone", async () => {
    const result = await ok("local_datetime", { timezone: "Asia/Kolkata" });
    expect(result).toContain("2026-09-12 14:30:05");
    expect(result).toContain("Asia/Kolkata");
    expect(result).toContain("Saturday");
    expect(result).toContain("UTC instant: 2026-09-12T09:00:05.000Z");
  });

  it("falls back to this device's zone when none is given", async () => {
    const result = await ok("local_datetime", {});
    expect(result).toContain("UTC instant: 2026-09-12T09:00:05.000Z");
  });

  it("shifts by an offset, and says the shift is elapsed time not calendar", async () => {
    const result = await ok("local_datetime", { timezone: "UTC", offset: 3, unit: "days" });
    expect(result).toContain("2026-09-15 09:00:05");
    expect(result).toContain("not calendar months");
    const back = await ok("local_datetime", { timezone: "UTC", offset: -90, unit: "minutes" });
    expect(back).toContain("2026-09-12 07:30:05");
  });

  it("refuses a zone it cannot verify instead of guessing one", async () => {
    // ICU accepts "IST" and resolves it to India on its own (verified on this
    // repo's Node 24 build); the tool must refuse rather than inherit that
    // guess. Ireland and Israel also call their zone IST.
    const bad = await notOk("local_datetime", { timezone: "IST" });
    expect(bad.code).toBe("rejected");
    expect(bad.detail).toContain("ambiguous");
    expect(bad.detail).toContain("Asia/Kolkata");
    expect((await notOk("local_datetime", { timezone: "EST" })).code).toBe("rejected");
    expect((await notOk("local_datetime", { timezone: "Mars/Olympus" })).code).toBe("rejected");
    // UTC is the one abbreviation that is unambiguous, so it must still work.
    expect(await ok("local_datetime", { timezone: "UTC" })).toContain("2026-09-12 09:00:05");
  });

  it("refuses malformed arguments", async () => {
    expect((await notOk("local_datetime", { timezone: 5 })).detail).toContain("must be a string");
    expect((await notOk("local_datetime", { offset: "three" })).detail).toContain("finite number");
    const unit = await notOk("local_datetime", { offset: 1, unit: "months" });
    expect(unit.detail).toContain("minutes, hours, days, weeks");
  });
});

// ---------------------------------------------------------------------------
// local_convert
// ---------------------------------------------------------------------------

describe("local_convert", () => {
  it("converts within each supported family", async () => {
    expect(await ok("local_convert", { value: 1, from: "km", to: "m" })).toBe("1 km = 1000 m");
    expect(await ok("local_convert", { value: 12, from: "inches", to: "cm" })).toBe("12 in = 30.48 cm");
    expect(await ok("local_convert", { value: 2, from: "kg", to: "lb" })).toContain("4.40924524");
    expect(await ok("local_convert", { value: 100, from: "celsius", to: "F" })).toBe("100 C = 212 F");
    expect(await ok("local_convert", { value: 1, from: "gib", to: "mib" })).toContain("= 1024 mib");
    expect(await ok("local_convert", { value: 90, from: "minutes", to: "h" })).toBe("90 min = 1.5 h");
  });

  it("says which data units are decimal and which are binary", async () => {
    expect(await ok("local_convert", { value: 1, from: "gb", to: "byte" })).toContain("decimal (1000)");
  });

  it("refuses an unknown unit by naming what it supports", async () => {
    const bad = await notOk("local_convert", { value: 1, from: "smoots", to: "m" });
    expect(bad.code).toBe("rejected");
    expect(bad.detail).toContain("length:");
    expect(bad.detail).toContain("temperature:");
    // The two deliberately-omitted ambiguous units must refuse, not guess.
    expect((await notOk("local_convert", { value: 1, from: "b", to: "byte" })).code).toBe("rejected");
    expect((await notOk("local_convert", { value: 1, from: "ton", to: "kg" })).code).toBe("rejected");
    // Volume is not a family here; asking for it must say so.
    expect((await notOk("local_convert", { value: 1, from: "litre", to: "ml" })).code).toBe("rejected");
  });

  it("refuses a cross-family conversion", async () => {
    const bad = await notOk("local_convert", { value: 1, from: "km", to: "kg" });
    expect(bad.detail).toContain("measure different things");
    expect((await notOk("local_convert", { value: 1, from: "c", to: "m" })).detail).toContain("different things");
  });

  it("refuses malformed arguments", async () => {
    expect((await notOk("local_convert", { value: "1", from: "km", to: "m" })).detail).toContain("finite number");
    expect((await notOk("local_convert", { value: 1, from: "km" })).detail).toContain('"to" must be a string');
    expect((await notOk("local_convert", null)).code).toBe("rejected");
  });
});

// ---------------------------------------------------------------------------
// local_transform
// ---------------------------------------------------------------------------

describe("local_transform", () => {
  it("counts with both character definitions spelled out", async () => {
    const result = await ok("local_transform", { op: "count", text: "hello wörld 🙂" });
    expect(result).toContain("words: 3");
    expect(result).toContain("characters (Unicode code points): 13");
    expect(result).toContain("characters (UTF-16 units, JS .length): 14");
    expect(result).toContain("bytes (UTF-8): 17");
  });

  it("sorts and dedupes lines without inventing a trailing blank", async () => {
    expect(await ok("local_transform", { op: "sort_lines", text: "pear\napple\nBanana\n" })).toBe(
      "apple\nBanana\npear"
    );
    expect(await ok("local_transform", { op: "sort_lines", text: "a\nb\nc", descending: true })).toBe("c\nb\na");
    const deduped = await ok("local_transform", { op: "dedupe_lines", text: "a\nb\na\nc\nb\n" });
    expect(deduped).toContain("a\nb\nc");
    expect(deduped).toContain("[5 lines in, 3 unique, 2 removed]");
  });

  it("pretty-prints and queries JSON", async () => {
    expect(await ok("local_transform", { op: "json_pretty", text: '{"a":1}' })).toBe('{\n  "a": 1\n}');
    const json = '{"user":{"addresses":[{"city":"Mysuru"},{"city":"Pune"}]}}';
    expect(await ok("local_transform", { op: "json_path", text: json, path: "user.addresses[1].city" })).toBe(
      "user.addresses[1].city = Pune"
    );
  });

  it("names the keys that ARE there when a path misses", async () => {
    const bad = await notOk("local_transform", {
      op: "json_path",
      text: '{"user":{"name":"A","email":"b@c"}}',
      path: "user.phone",
    });
    expect(bad.code).toBe("rejected");
    expect(bad.detail).toContain("Available keys: name, email");
    const oob = await notOk("local_transform", { op: "json_path", text: '{"a":[1]}', path: "a[5]" });
    expect(oob.detail).toContain("past the end");
  });

  it("round-trips base64 including non-ASCII, and refuses junk", async () => {
    const encoded = await ok("local_transform", { op: "base64_encode", text: "héllo 🙂" });
    expect(await ok("local_transform", { op: "base64_decode", text: encoded })).toBe("héllo 🙂");
    expect((await notOk("local_transform", { op: "base64_decode", text: "not base64!" })).detail).toContain(
      "not valid base64"
    );
  });

  it("refuses malformed arguments", async () => {
    expect((await notOk("local_transform", { op: "shout", text: "x" })).detail).toContain("must be one of");
    expect((await notOk("local_transform", { op: "count" })).detail).toContain('"text" must be a string');
    expect((await notOk("local_transform", { op: "json_pretty", text: "{oops" })).detail).toContain("not valid JSON");
    expect((await notOk("local_transform", { op: "json_path", text: "{}", path: "" })).code).toBe("rejected");
    expect((await notOk("local_transform", { op: "count", text: "x".repeat(20_001) })).detail).toContain(
      "at most 20000"
    );
  });

  it("truncates an oversized result and says so, rather than silently shortening it", async () => {
    const text = Array.from({ length: 900 }, (_, i) => `line-${i}`).join("\n");
    const result = await ok("local_transform", { op: "sort_lines", text });
    expect(result.length).toBeLessThanOrEqual(MAX_RESULT_CHARS);
    expect(result).toContain("[truncated: showing the first");
  });
});

// ---------------------------------------------------------------------------
// runLocalTool — the failure contract
// ---------------------------------------------------------------------------

describe("runLocalTool", () => {
  function fakeTool(run: LocalTool["run"], name = "local_fake"): LocalTool {
    return { name, description: "", inputSchema: {}, network: "none", consequential: false, run };
  }

  it("turns a throwing handler into a typed failure, not an exception", async () => {
    const result = await runLocalTool(
      fakeTool(() => {
        throw new Error("boom");
      }),
      {}
    );
    expect(result.ok).toBe(false);
    if (result.ok === false) {
      expect(result.code).toBe("failed");
      expect(result.detail).toContain("boom");
    }
  });

  it("turns a rejecting handler into a typed failure", async () => {
    const result = await runLocalTool(
      fakeTool(async () => Promise.reject(new Error("async boom"))),
      {}
    );
    if (result.ok === true) throw new Error("expected failure");
    expect(result.code).toBe("failed");
    expect(result.detail).toContain("async boom");
  });

  it("reports a handler that returns the wrong type instead of stringifying it", async () => {
    const result = await runLocalTool(fakeTool((async () => ({ a: 1 })) as unknown as LocalTool["run"]), {});
    if (result.ok === true) throw new Error("expected failure");
    expect(result.code).toBe("failed");
    expect(result.detail).toContain("instead of a string");
  });

  it("abandons a handler that never finishes", async () => {
    vi.useFakeTimers();
    try {
      const pending = runLocalTool(
        fakeTool(() => new Promise<string>(() => undefined)),
        {}
      );
      await vi.advanceTimersByTimeAsync(LOCAL_TOOL_TIMEOUT_MS + 10);
      const result = await pending;
      if (result.ok === true) throw new Error("expected failure");
      expect(result.code).toBe("failed");
      expect(result.detail).toContain(`${LOCAL_TOOL_TIMEOUT_MS}ms`);
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports something that is not a local tool as unavailable", async () => {
    expect(await runLocalTool(null as unknown as LocalTool, {})).toEqual({
      ok: false,
      code: "unavailable",
      detail: "no such local tool",
    });
    const unprefixed = await runLocalTool(
      fakeTool(async () => "x", "calculate"),
      {}
    );
    if (unprefixed.ok === true) throw new Error("expected failure");
    expect(unprefixed.code).toBe("unavailable");
  });

  it("treats missing arguments as an empty object rather than crashing", async () => {
    const result = await runLocalTool(
      fakeTool(async (args) => `keys:${Object.keys(args).length}`),
      undefined
    );
    if (result.ok === false) throw new Error(result.detail);
    expect(result.result).toBe("keys:0");
  });
});

// ---------------------------------------------------------------------------
// Property proofs
// ---------------------------------------------------------------------------

/** Resolved from the vitest root (web/) rather than import.meta.url: under the
 * jsdom environment import.meta.url is an http:// URL and fileURLToPath rejects
 * it. A wrong path throws ENOENT, so this cannot silently scan nothing. */
function readSource(): string {
  // The pure-compute handlers were split out of localTools.ts into tools/*.ts;
  // every property proof below must keep scanning ALL of them, so the scan
  // reads the registry file plus each split-out module.
  return [
    "app/lib/edge/localTools.ts",
    ...["common", "calc", "datetime", "units", "transform", "computeTools"].map(
      (name) => `app/lib/edge/tools/${name}.ts`
    ),
  ]
    .map((file) => readFileSync(resolve(process.cwd(), file), "utf8"))
    .join("\n");
}

/** Comments are stripped before the identifier scan below, because the module's
 * own header discusses fetch, WebSocket and the rest by name while promising it
 * never calls them — the scan must read the code, not the prose. */
function readSourceCode(): string {
  return readSource()
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");
}

describe("property: no spend, no download, no data leaves", () => {
  it("imports only the registry's three known tool sets at runtime, nothing else", () => {
    const importLines = readSourceCode()
      .split("\n")
      .filter((line) => /^\s*import\b/.test(line));
    expect(importLines.length).toBeGreaterThan(0);
    // This file was once import-type-only, which made NO SPEND a compile-time
    // fact. It is now the assembly point for three tool sets, so the proof
    // moves down a level: the pure-compute handlers still name no I/O API (next
    // test), each sibling set carries the same scan in its own suite, and this
    // allowlist means a NEW runtime import cannot appear without failing here.
    const allowedRuntimeImports = [
      "./capabilities",
      "./localToolsFiles",
      "./localToolsWeb",
      "./browserAi",
      "./deviceTools",
      "./localToolsMedia",
      // The split-out pure-compute handlers (tools/*.ts) and their siblings.
      "./tools/common",
      "./tools/computeTools",
      "./common",
      "./calc",
      "./datetime",
      "./units",
      "./transform",
    ];
    for (const line of importLines) {
      if (line.trim().startsWith("import type ")) continue;
      const from = /from\s+"([^"]+)"/.exec(line)?.[1];
      expect(allowedRuntimeImports).toContain(from);
    }
    // No runtime escape hatches around the static-import rule either.
    expect(readSourceCode()).not.toMatch(/\brequire\s*\(/);
    expect(readSourceCode()).not.toMatch(/\bimport\s*\(/);
  });

  it("names no network or model-download API anywhere in its code", () => {
    const code = readSourceCode();
    for (const forbidden of [
      /\bfetch\s*\(/,
      /XMLHttpRequest/,
      /WebSocket/,
      /EventSource/,
      /sendBeacon/,
      /navigator\.serviceWorker/,
      /createObjectURL/,
      /localStorage/,
      /sessionStorage/,
      /indexedDB/,
      // The tier-1 weights path. Nothing here may reach the download manager.
      /downloadManager/,
      /web-llm/,
    ]) {
      expect(code).not.toMatch(forbidden);
    }
  });

  it("touches no network API when every tool actually runs", async () => {
    const calls: string[] = [];
    const trap = (label: string) =>
      vi.fn(() => {
        calls.push(label);
        throw new Error(`${label} must never be called by a local tool`);
      });
    vi.stubGlobal("fetch", trap("fetch"));
    vi.stubGlobal("XMLHttpRequest", trap("XMLHttpRequest"));
    vi.stubGlobal("WebSocket", trap("WebSocket"));
    vi.stubGlobal("EventSource", trap("EventSource"));
    const beacon = trap("sendBeacon");
    Object.defineProperty(navigator, "sendBeacon", { configurable: true, value: beacon });
    try {
      const results = await Promise.all([
        ok("local_calculate", { expression: "6*7" }),
        ok("local_datetime", { timezone: "UTC" }),
        ok("local_convert", { value: 1, from: "km", to: "mi" }),
        ok("local_transform", { op: "count", text: "one two" }),
        ok("local_transform", { op: "base64_encode", text: "hi" }),
        ok("local_transform", { op: "json_path", text: '{"a":{"b":2}}', path: "a.b" }),
      ]);
      expect(results).toHaveLength(6);
      expect(calls).toEqual([]);
      expect(beacon).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("declares network:'none' on every advertised tool", () => {
    for (const tool of listLocalTools(CAPS)) expect(tool.network).toBe("none");
  });
});

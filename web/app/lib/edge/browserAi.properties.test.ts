// Track 2 — the browser's own built-in AI: the property proofs (no download
// from a tool, no exported download path, no spend or silent network, the tool
// contract). Same fake globals and harness as browserAi.test.ts (see its header
// and browserAi.testkit.ts).

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  BROWSER_AI_ASSET_IDS,
  BROWSER_AI_TOOLS,
  getBrowserAiCapabilities,
  openBrowserAiSession,
  startBrowserAiDownload,
} from "./browserAi";
import { LOCAL_TOOL_PREFIX, runLocalTool } from "./localTools";
import {
  BROWSER_AI_FAMILY,
  clearGlobals,
  makeModern,
  readFamilyFile,
  setGlobal,
  stripComments,
  tool,
} from "./browserAi.testkit";

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

beforeEach(() => {
  clearGlobals();
});

afterEach(() => {
  clearGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

// ---------------------------------------------------------------------------
// Property: no surprise download
// ---------------------------------------------------------------------------

describe("property: a tool can never start a model download", () => {
  const cases: Array<{ name: string; globalKey: string; methodName: string; args: Record<string, unknown> }> = [
    { name: "local_summarize", globalKey: "Summarizer", methodName: "summarize", args: { text: "x" } },
    {
      name: "local_translate",
      globalKey: "Translator",
      methodName: "translate",
      args: { text: "x", to: "es", from: "en" },
    },
    { name: "local_detect_language", globalKey: "LanguageDetector", methodName: "detect", args: { text: "x" } },
  ];

  for (const state of ["downloadable", "downloading", "unavailable", "unknown"]) {
    for (const testCase of cases) {
      it(`${testCase.name} does not call create() when availability is "${state}"`, async () => {
        const fake = makeModern(testCase.methodName, { availability: state, answer: "should never run" });
        setGlobal(testCase.globalKey, fake.global);
        const out = await tool(testCase.name).run(testCase.args);
        expect(fake.record.createCalls.length).toBe(0);
        expect(typeof out).toBe("string");
        expect(out.length).toBeGreaterThan(20);
      });
    }
  }

  it("tells the person how to get the model, without fetching it", async () => {
    const fake = makeModern("summarize", { availability: "downloadable" });
    setGlobal("Summarizer", fake.global);
    const out = await tool("local_summarize").run({ text: "x" });
    expect(out).toContain("has to fetch its own model first");
    expect(out).toContain("starts only when you ask for it");
    expect(fake.record.createCalls.length).toBe(0);
  });

  // The 12 cases above prove the gate FIRES when availability() says no. They
  // cannot prove it asked the right question: the fake's availability() ignores
  // its argument. These two do the other half — the options the probe is given
  // are the options create() is called with, for every kind, not only the
  // translator. A probe about the default configuration and a create() for a
  // requested one is how a browser answers "available" and then downloads.
  it("probes summarizer availability with the SAME options create() gets", async () => {
    const fake = makeModern("summarize", { answer: "ok" });
    setGlobal("Summarizer", fake.global);
    await tool("local_summarize").run({ text: "x", type: "tldr", length: "long", context: "for a child" });
    expect(fake.record.availabilityCalls.length).toBe(1);
    expect(fake.record.availabilityCalls[0]).toEqual(fake.record.createCalls[0]);
    expect(fake.record.availabilityCalls[0]).toMatchObject({
      type: "tldr",
      length: "long",
      format: "plain-text",
      sharedContext: "for a child",
    });
  });

  it("probes detector availability with the same (empty) options bag", async () => {
    const fake = makeModern("detect", { answer: [{ detectedLanguage: "en", confidence: 0.9 }] });
    setGlobal("LanguageDetector", fake.global);
    await tool("local_detect_language").run({ text: "hello there" });
    expect(fake.record.availabilityCalls[0]).toEqual(fake.record.createCalls[0]);
  });
});

// ---------------------------------------------------------------------------
// Property: the download gate is closed at the EXPORT boundary
//
// "No tool handler calls create()" is a fact about this file. What a reviewer
// has to be able to check is the stronger one: that no module OUTSIDE this file
// can reach create() either, however it calls what is exported.
// ---------------------------------------------------------------------------

describe("property: no exported function can ask for a download", () => {
  it("openBrowserAiSession has no download parameter to pass", async () => {
    const fake = makeModern("summarize", { availability: "downloadable" });
    setGlobal("Summarizer", fake.global);
    // A caller that TRIES anyway: the extra property is not in the parameter
    // type, so this needs a cast to compile at all — and it still does not
    // reach create().
    const opts = { allowDownload: true, userInitiated: true } as unknown as { signal?: AbortSignal };
    const opened = await openBrowserAiSession("summarizer", { type: "tldr" }, opts);
    expect(opened.ok).toBe(false);
    if (opened.ok === false) expect(opened.message).toContain("has to fetch its own model first");
    expect(fake.record.createCalls.length).toBe(0);
  });

  it("exports no other route to create(): the download opener is module-local", () => {
    const code = readSourceCode();
    // The download-permitting opener exists, and is not exported.
    expect(code).toContain("async function openSessionAllowingDownload(");
    expect(code).not.toContain("export async function openSessionAllowingDownload");
    expect(code).not.toContain("export async function openSession(");
    // The exported opener's own parameter list carries no download flag.
    const exported = code.slice(code.indexOf("export async function openBrowserAiSession("));
    const signature = exported.slice(0, exported.indexOf("): Promise"));
    expect(signature.length).toBeGreaterThan(40); // the slice actually found a signature
    expect(signature).toContain("opts: { signal?: AbortSignal }");
    expect(signature).not.toContain("allowDownload");
  });

  it("every exported function that can reach create() demands userInitiated: true", () => {
    const code = readSourceCode();
    // create() is invoked in exactly one place in the module.
    const createCalls = code.match(/create\.call\(/g) || [];
    expect(createCalls.length).toBe(1);
    // ...inside openSession, which only permits it on a non-null marker.
    expect(code).toContain("const allowDownload = opts.userInitiatedDownload?.userInitiated === true;");
  });
});

describe("startBrowserAiDownload is the only door, and it is an explicit one", () => {
  it("refuses without the userInitiated marker", async () => {
    setGlobal("Summarizer", makeModern("summarize", { availability: "downloadable" }).global);
    const result = await startBrowserAiDownload({
      kind: "summarizer",
      userInitiated: false as unknown as true,
    });
    expect(result.ok).toBe(false);
    if (result.ok === false) {
      expect(result.reason).toBe("refused");
      expect(result.message).toContain("explicit action");
    }
  });

  it("says there is nothing to download when the model is already there", async () => {
    setGlobal("Summarizer", makeModern("summarize", { availability: "available" }).global);
    const result = await startBrowserAiDownload({ kind: "summarizer", userInitiated: true });
    expect(result.ok).toBe(false);
    if (result.ok === false) expect(result.reason).toBe("not-needed");
  });

  it("refuses a translator download without a language pair", async () => {
    setGlobal("Translator", makeModern("translate", { availability: "downloadable" }).global);
    const result = await startBrowserAiDownload({ kind: "translator", userInitiated: true });
    expect(result.ok).toBe(false);
    if (result.ok === false) {
      expect(result.reason).toBe("refused");
      expect(result.message).toContain("per language pair");
    }
  });

  it("degrades honestly on a browser without the feature", async () => {
    const result = await startBrowserAiDownload({ kind: "summarizer", userInitiated: true });
    expect(result.ok).toBe(false);
    if (result.ok === false) {
      expect(result.reason).toBe("unsupported");
      expect(result.message).toContain("does not provide");
    }
  });

  it("puts the browser's own progress into the shared download manager", async () => {
    const fake = makeModern("summarize", {
      availability: "downloadable",
      progress: [{ loaded: 0.25 }, { loaded: 1 }],
    });
    setGlobal("Summarizer", fake.global);
    const result = await startBrowserAiDownload({ kind: "summarizer", userInitiated: true });
    expect(result.ok).toBe(true);
    expect(fake.record.createCalls.length).toBe(1);
    // The session was only the vehicle for the download.
    expect(fake.record.destroyCalls).toBe(1);

    const { downloadManager } = await import("./downloadManager");
    const asset = downloadManager.getAsset(BROWSER_AI_ASSET_IDS.summarizer);
    expect(asset).not.toBeNull();
    if (asset) expect(asset.state).toBe("ready");
  });

  it("reports a cancel as a cancel, not as an error", async () => {
    const controller = new AbortController();
    const abortError = new Error("cancelled");
    abortError.name = "AbortError";
    setGlobal("Summarizer", makeModern("summarize", { availability: "downloadable", createError: abortError }).global);
    controller.abort();
    const result = await startBrowserAiDownload({
      kind: "summarizer",
      userInitiated: true,
      signal: controller.signal,
    });
    expect(result.ok).toBe(false);
    if (result.ok === false) {
      expect(result.reason).toBe("cancelled");
      expect(result.message).toContain("Nothing was charged");
    }
  });
});

// ---------------------------------------------------------------------------
// Property: no spend, no silent network
// ---------------------------------------------------------------------------

/** The whole module family's code, comments stripped. */
function readSourceCode(): string {
  return BROWSER_AI_FAMILY.map((name) => stripComments(readFamilyFile(name))).join("\n");
}

describe("property: no spend, no silent network", () => {
  it("has no static value import beyond its own family — every other static import is erased", () => {
    let found = 0;
    for (const name of BROWSER_AI_FAMILY) {
      const code = stripComments(readFamilyFile(name));
      const staticImports = code.match(/^(?:import|export)\s[\s\S]*?from\s+"[^"]+";/gm) || [];
      found += staticImports.length;
      for (const statement of staticImports) {
        const target = /from\s+"([^"]+)"/.exec(statement)?.[1] ?? "";
        const sibling = /^\.\/browserAi(?:Surface|Session)?$/.test(target);
        const typeOnly = /^(?:import|export)\s+type\s/.test(statement);
        expect(typeOnly || sibling, `${name}: ${statement}`).toBe(true);
      }
    }
    expect(found).toBeGreaterThan(0); // the scan is actually finding imports
    expect(readFamilyFile("browserAi")).toMatch(/^import type \{ LocalTool \} from "\.\/localTools";/m);
  });

  it("reaches downloadManager only through a dynamic import", () => {
    const code = readSourceCode();
    expect(code).toContain('await import("./downloadManager")');
    expect(code).not.toMatch(/^import\s+\{[^}]*downloadManager[^}]*\}\s+from/m);
  });

  it("contains no network primitive and no URL", () => {
    const code = readSourceCode();
    for (const forbidden of [
      "fetch(",
      "XMLHttpRequest",
      "WebSocket",
      "EventSource",
      "sendBeacon",
      "navigator.connection",
      "http://",
      "https://",
      "/api/",
    ]) {
      expect(code).not.toContain(forbidden);
    }
  });

  it("every tool still works with the network primitives replaced by throwing stubs", async () => {
    const boom = (): never => {
      throw new Error("a browser-AI tool touched the network");
    };
    const host = globalThis as Record<string, unknown>;
    const saved: Record<string, unknown> = {};
    for (const key of ["fetch", "XMLHttpRequest", "WebSocket", "EventSource"]) {
      saved[key] = host[key];
      host[key] = boom;
    }
    const navigatorRef = globalThis.navigator as unknown as Record<string, unknown> | undefined;
    const savedBeacon = navigatorRef ? navigatorRef.sendBeacon : undefined;
    if (navigatorRef) navigatorRef.sendBeacon = boom;

    try {
      setGlobal("Summarizer", makeModern("summarize", { answer: "s" }).global);
      setGlobal("Translator", makeModern("translate", { answer: "t" }).global);
      setGlobal(
        "LanguageDetector",
        makeModern("detect", { answer: [{ detectedLanguage: "en", confidence: 0.9 }] }).global
      );

      expect(await tool("local_summarize").run({ text: "x" })).toContain("s");
      expect(await tool("local_translate").run({ text: "x", to: "es", from: "en" })).toContain("t");
      expect(await tool("local_detect_language").run({ text: "x" })).toContain("(en)");
      const summary = await getBrowserAiCapabilities();
      expect(summary.features.length).toBe(3);
    } finally {
      for (const key of ["fetch", "XMLHttpRequest", "WebSocket", "EventSource"]) {
        if (saved[key] === undefined) delete host[key];
        else host[key] = saved[key];
      }
      if (navigatorRef) {
        if (savedBeacon === undefined) delete navigatorRef.sendBeacon;
        else navigatorRef.sendBeacon = savedBeacon;
      }
    }
  });
});

// ---------------------------------------------------------------------------
// Property: the tool contract
// ---------------------------------------------------------------------------

describe("property: the tool contract", () => {
  it("every tool is local_-prefixed, network:none, non-consequential", () => {
    expect(BROWSER_AI_TOOLS.length).toBe(3);
    for (const t of BROWSER_AI_TOOLS) {
      expect(t.name.startsWith(LOCAL_TOOL_PREFIX)).toBe(true);
      expect(t.network).toBe("none");
      expect(t.consequential).toBe(false);
      expect(typeof t.run).toBe("function");
    }
  });

  it("every description says the BROWSER's own built-in model does the work", () => {
    for (const t of BROWSER_AI_TOOLS) {
      expect(t.description.toUpperCase()).toContain("BROWSER'S OWN");
      expect(t.description).toContain("built-in");
      expect(t.description).toContain("no account");
      // Scoped to THIS PAGE, which is the only thing this module can prove.
      // An unscoped "nothing leaves your machine" would be a claim about the
      // browser's own process — see property 3 in the module header.
      expect(t.description).toContain("this page sends nothing over the network");
      expect(t.description).not.toContain("leaves your machine");
    }
  });

  it("runs through runLocalTool as an ordinary local tool", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answer: "bridged" }).global);
    const result = await runLocalTool(tool("local_summarize"), { text: "x" });
    expect(result.ok).toBe(true);
    if (result.ok === true) expect(result.result).toContain("bridged");
  });

  it("an unsupported browser is an ok result with a sentence, not a failure code", async () => {
    const result = await runLocalTool(tool("local_summarize"), { text: "x" });
    expect(result.ok).toBe(true);
    if (result.ok === true) expect(result.result).toContain("does not provide");
  });

  // The provenance line is the disclosure. runLocalTool's capResult truncates
  // the TAIL, so a disclosure appended at the end is exactly what a long answer
  // loses. These two assert it is at the head and that it survives the caller's
  // own truncation.
  it("a session missing its own method is a sentence, not a 'failed' code", async () => {
    // A browser whose create() hands back an object without summarize(). That
    // is the BROWSER falling short, not a defect on our side, so it must not
    // arrive as code "failed" (= "our bug, do not retry").
    setGlobal("Summarizer", {
      availability: async (): Promise<string> => "available",
      create: async (): Promise<unknown> => ({ destroy: (): void => undefined }),
    });
    const result = await runLocalTool(tool("local_summarize"), { text: "x" });
    expect(result.ok).toBe(true);
    if (result.ok === true) {
      expect(result.result).toContain("no summarize() method");
      expect(result.result).toContain("not an error on this page");
    }
  });

  it("puts the provenance line first, before the content", async () => {
    setGlobal("Summarizer", makeModern("summarize", { answer: "the summary body" }).global);
    const out = await tool("local_summarize").run({ text: "x" });
    expect(out.startsWith("[done by your browser's own built-in model")).toBe(true);
    expect(out.indexOf("built-in model")).toBeLessThan(out.indexOf("the summary body"));
  });

  it("keeps the provenance line on a result long enough to be truncated", async () => {
    const long = "otters ".repeat(900); // 6300 chars, well over MAX_RESULT_CHARS
    setGlobal("Summarizer", makeModern("summarize", { answer: long }).global);
    const result = await runLocalTool(tool("local_summarize"), { text: "x" });
    expect(result.ok).toBe(true);
    if (result.ok === true) {
      expect(result.result).toContain("[truncated:"); // the caller really did cut it
      expect(result.result).toContain("your browser's own built-in model");
      expect(result.result.startsWith("[done by your browser's own built-in model")).toBe(true);
    }
  });

  it("every tool's disclosure survives truncation, not just the summarizer's", async () => {
    setGlobal("Translator", makeModern("translate", { answer: "hola ".repeat(700) }).global);
    const translated = await runLocalTool(tool("local_translate"), { text: "hello", to: "es", from: "en" });
    expect(translated.ok).toBe(true);
    if (translated.ok === true) {
      expect(translated.result).toContain("[truncated:");
      expect(translated.result.startsWith("[done by your browser's own built-in model")).toBe(true);
    }
  });
});

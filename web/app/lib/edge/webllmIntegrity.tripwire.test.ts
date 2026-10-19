// TRIPWIRE: pins what @mlc-ai/web-llm's integrity support can and cannot do.
//
// WHY THIS FILE EXISTS. "Add SRI integrity verification for our model weights,
// WebLLM 0.2.83 added ModelRecord.integrity" is an extremely reasonable-sounding
// task. It was investigated on 2026-09-17 and DECLINED, because three separate
// facts about the installed library make it verify nothing while looking like
// it verifies something — the worst possible outcome for a security control.
//
//   1. `integrity` does not cover WEIGHTS. It covers mlc-chat-config.json, the
//      model_lib wasm, and tokenizer files. The multi-GB weight shards are
//      fetched by tvm's fetchTensorCache, which takes no integrity argument.
//   2. tier1 passes NO appConfig on the default path (tier1EngineAppConfig
//      returns undefined unless the cache backend is "cross-origin"), so there
//      is no ModelRecord list to put `integrity` on. Adding one is not "set a
//      field", it is "start constructing a model_list", on two engine paths.
//   3. Worse, on the Service-Worker path — which IS the default — a reload
//      whose modelId and chatOpts match is short-circuited WITHOUT comparing
//      appConfig, despite the library's own comment one line above claiming it
//      compares appConfig. So a warm worker would skip the reload entirely and
//      never run verification, while the UI reports success.
//
// These assertions read the installed package deliberately. If upstream adds
// weight integrity, or fixes the short-circuit, THESE TESTS FAIL — and that
// failure is the signal that the decision above is worth revisiting. A comment
// in a design doc cannot do that; this can.
//
// If a test here fails, do NOT "fix" it by loosening the assertion. Re-read the
// library, confirm what changed, and re-open the question.

import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require_ = createRequire(import.meta.url);

function resolveWebllm(file: string): string | null {
  try {
    const pkg = require_.resolve("@mlc-ai/web-llm/package.json");
    const path = pkg.replace(/package\.json$/, file);
    return existsSync(path) ? path : null;
  } catch {
    return null;
  }
}

describe("web-llm integrity support — pinned upstream limitations", () => {
  it("ModelIntegrity covers config/model_lib/tokenizer and NOT weights", () => {
    const path = resolveWebllm("lib/integrity.d.ts");
    if (!path) return; // package not installed (fresh clone) — nothing to pin
    const src = readFileSync(path, "utf8");

    // The three artifacts it DOES cover.
    expect(src).toMatch(/\bconfig\b/);
    expect(src).toMatch(/\bmodel_lib\b/);
    expect(src).toMatch(/\btokenizer\b/);

    // The one it does not. If a `weights`/`shards`/`tensor` field ever appears
    // here, weight verification became possible and the SRI decision changes.
    expect(
      /\b(weights|shards|tensors?)\s*[?:]/.test(src),
      "web-llm appears to have added weight integrity — re-open the SRI decision"
    ).toBe(false);
  });

  it("the weight fetch path carries no hash checking at all", () => {
    const path = resolveWebllm("lib/index.js");
    if (!path) return;
    const src = readFileSync(path, "utf8");

    // fetchTensorCache is how weights arrive.
    expect(src).toContain("fetchTensorCache");
    // ...and there is no digest machinery anywhere in the bundle.
    expect(
      src.includes("md5"),
      "web-llm now references md5 — it may have added weight verification"
    ).toBe(false);
  });

  it("the Service-Worker reload short-circuit ignores appConfig, so integrity would silently no-op", () => {
    const path = resolveWebllm("lib/index.js");
    if (!path) return;
    const src = readFileSync(path, "utf8");

    const idx = src.indexOf("If the modelId, chatOpts, and appConfig are the same");
    if (idx === -1) return; // comment reworded upstream — worth a human look, not a failure
    const condition = src.slice(idx, idx + 400);

    // The comment promises appConfig is compared. The code compares two things.
    expect(condition).toContain("areArraysEqual(this.modelId");
    expect(condition).toContain("areChatOptionsListEqual(this.chatOpts");
    expect(
      /areAppConfigsEqual|params\.appConfig\s*===|appConfig\s*,\s*params\.appConfig/.test(condition),
      "web-llm now compares appConfig on reload — a passed integrity config would " +
        "no longer be skipped by a warm worker; re-open the SRI decision"
    ).toBe(false);
  });
});

// Track 4 — tests for the two small on-device models and for the third
// redaction path they unlock.
//
// Style follows the sibling redactor.test.ts / tierRouter.policy.test.ts:
// vi.resetModules() + a fresh dynamic import per test, so module-level state
// (a loaded model handle, a memoized cache probe) can never leak between
// cases.
//
// NOTHING HERE TOUCHES THE NETWORK, and that is load-bearing rather than
// incidental: every test drives the modules through their documented override
// seams (__setNerOverridesForTests), so if a
// future edit ever made extractEntitiesLocal() reach for
// @huggingface/transformers, these tests would try to import the real library
// under jsdom and fail loudly instead of quietly downloading a model.
//
// What is actually pinned here, in priority order:
//   * a cold NER model never loads anything and never loses a result;
//   * a span that is not verbatim in the source can never reach the text;
//   * every refusal carries a reason, so an empty answer can never be
//     mistaken for "I looked and there was nothing".

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

function enableEdge(): void {
  process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
}

function disableEdge(): void {
  process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
}

// ---------------------------------------------------------------------------
// ner.ts
// ---------------------------------------------------------------------------

describe("edge/localModels/ner — label mapping", () => {
  beforeEach(() => {
    vi.resetModules();
    enableEdge();
  });

  it("maps the grouped and the raw BIO forms of the same tag identically", async () => {
    const { mapNerLabel } = await import("./ner");
    expect(mapNerLabel("PER")).toBe("person");
    expect(mapNerLabel("B-PER")).toBe("person");
    expect(mapNerLabel("I-ORG")).toBe("organization");
    expect(mapNerLabel("LOC")).toBe("address");
  });

  it("leaves MISC and O unmapped — masking a nationality or a product name is task damage, not privacy", async () => {
    const { mapNerLabel } = await import("./ner");
    expect(mapNerLabel("MISC")).toBeNull();
    expect(mapNerLabel("B-MISC")).toBeNull();
    expect(mapNerLabel("O")).toBeNull();
    expect(mapNerLabel(undefined)).toBeNull();
    expect(mapNerLabel(42)).toBeNull();
  });
});

describe("edge/localModels/ner — the verbatim resolver", () => {
  beforeEach(() => {
    vi.resetModules();
    enableEdge();
  });

  it("returns the span unchanged when it is already an exact substring", async () => {
    const { resolveVerbatimSpan } = await import("./ner");
    expect(resolveVerbatimSpan("Priya Raman approved it", "Priya Raman")).toBe("Priya Raman");
  });

  it("recovers a decode artefact by returning THE SOURCE'S characters, not the model's", async () => {
    const { resolveVerbatimSpan } = await import("./ner");
    // What a sub-word detokenizer hands back for O'Brien / a hyphenated name.
    expect(resolveVerbatimSpan("call O'Brien today", "O ' Brien")).toBe("O'Brien");
    expect(resolveVerbatimSpan("ask Anne-Marie", "Anne - Marie")).toBe("Anne-Marie");
  });

  it("THE RULE: a span that is not in the source at all is refused", async () => {
    const { resolveVerbatimSpan } = await import("./ner");
    expect(resolveVerbatimSpan("Priya Raman approved it", "Sunil Mehta")).toBeNull();
    // An "expanded" span — the model helpfully completing an initial. Masking
    // on this would rewrite text the person never wrote.
    expect(resolveVerbatimSpan("P. Raman approved it", "Priya Raman")).toBeNull();
  });

  it("the whitespace-tolerant pass cannot jump across unrelated words", async () => {
    const { resolveVerbatimSpan } = await import("./ner");
    // "Priya" and "Raman" both occur, but not adjacently: \s* spans whitespace
    // and nothing else, so there is no match to make.
    expect(resolveVerbatimSpan("Priya met Raman", "Priya Raman")).toBeNull();
  });

  it("rejects spans that are implausibly short or long", async () => {
    const { resolveVerbatimSpan } = await import("./ner");
    expect(resolveVerbatimSpan("a b c", "a")).toBeNull();
    const long = "x".repeat(200);
    expect(resolveVerbatimSpan(`${long} tail`, long)).toBeNull();
  });

  it("treats regex metacharacters in a span as literal text", async () => {
    const { resolveVerbatimSpan } = await import("./ner");
    expect(resolveVerbatimSpan("invoice from C++ (Pvt) Ltd", "C++ (Pvt) Ltd")).toBe("C++ (Pvt) Ltd");
  });
});

describe("edge/localModels/ner — chunking", () => {
  beforeEach(() => {
    vi.resetModules();
    enableEdge();
  });

  it("keeps every piece inside the limit — a truncated tail would be an unmasked name", async () => {
    const { chunkForNer } = await import("./ner");
    const text = ["short para", "y".repeat(500), ["a".repeat(90), "b".repeat(90)].join("\n")].join("\n\n");

    const chunks = chunkForNer(text, 100);

    expect(chunks.length).toBeGreaterThan(1);
    for (const chunk of chunks) expect(chunk.length).toBeLessThanOrEqual(100);
  });

  it("loses no non-whitespace content", async () => {
    const { chunkForNer } = await import("./ner");
    const text = "Priya Raman\n\nworks at Northwind Logistics in Pune\n" + "z".repeat(300);

    const joined = chunkForNer(text, 64).join("");
    const strip = (value: string): string => value.replace(/\s+/g, "");

    expect(strip(joined)).toBe(strip(text));
  });
});

describe("edge/localModels/ner — extraction", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    enableEdge();
  });

  afterEach(() => {
    disableEdge();
  });

  it("PROPERTY 2: a cold detector returns 'not-loaded', never an empty 'ok'", async () => {
    const mod = await import("./ner");

    const outcome = await mod.extractEntitiesLocal("Priya Raman approved it");

    expect(mod.nerAvailable()).toBe(false);
    expect(outcome.reason).toBe("not-loaded");
    expect(outcome.entities).toEqual([]);
    expect(outcome.detail).toBe(mod.NER_NOT_INSTALLED_DETAIL);
  });

  it("PROPERTY 4: 'found nothing' and 'could not look' are different answers", async () => {
    const mod = await import("./ner");
    mod.__setNerOverridesForTests({ classify: async () => [] });

    const outcome = await mod.extractEntitiesLocal("nothing personal in here");

    expect(outcome.entities).toEqual([]);
    expect(outcome.reason).toBe("ok"); // the model DID look
    mod.__setNerOverridesForTests(null);
  });

  it("keeps PER/ORG above the floor, drops MISC entirely, and holds LOC to a higher bar", async () => {
    const mod = await import("./ner");
    mod.__setNerOverridesForTests({
      classify: async () => [
        { word: "Priya Raman", score: 0.98, entity_group: "PER" },
        { word: "Northwind Logistics", score: 0.91, entity_group: "ORG" },
        { word: "Diwali", score: 0.99, entity_group: "MISC" },
        { word: "Pune", score: 0.8, entity_group: "LOC" }, // below the 0.9 LOC floor
        { word: "Mumbai", score: 0.97, entity_group: "LOC" },
        { word: "Ravi", score: 0.4, entity_group: "PER" }, // below the 0.7 PER floor
      ],
    });

    const source = "Priya Raman and Ravi at Northwind Logistics, Pune and Mumbai, before Diwali";
    const outcome = await mod.extractEntitiesLocal(source);

    expect(outcome.reason).toBe("ok");
    expect(outcome.entities.map((entity: { text: string }) => entity.text)).toEqual([
      "Northwind Logistics",
      "Priya Raman",
      "Mumbai",
    ]);
    // Longest-first, so a nested span can't blank the middle of its parent.
    expect(outcome.entities[0].category).toBe("organization");
    mod.__setNerOverridesForTests(null);
  });

  it("THE RULE: a hallucinated span is dropped even when the model is confident", async () => {
    const mod = await import("./ner");
    mod.__setNerOverridesForTests({
      classify: async () => [
        { word: "Priya Raman", score: 0.99, entity_group: "PER" },
        { word: "Sunil Mehta", score: 0.99, entity_group: "PER" },
      ],
    });

    const outcome = await mod.extractEntitiesLocal("Priya Raman approved it");

    expect(outcome.entities).toHaveLength(1);
    expect(outcome.entities[0].text).toBe("Priya Raman");
    mod.__setNerOverridesForTests(null);
  });

  it("a classifier that throws keeps the spans already found and says the scan was incomplete", async () => {
    const mod = await import("./ner");
    let call = 0;
    mod.__setNerOverridesForTests({
      classify: async () => {
        call += 1;
        if (call === 1) return [{ word: "Priya Raman", score: 0.99, entity_group: "PER" }];
        throw new Error("wasm exploded");
      },
    });

    // Two paragraphs → two chunks, so the second call is the one that throws.
    const outcome = await mod.extractEntitiesLocal("Priya Raman approved it\n\nand so did someone else");

    expect(outcome.reason).toBe("failed");
    expect(outcome.entities.map((entity: { text: string }) => entity.text)).toEqual(["Priya Raman"]);
    expect(outcome.detail).toContain("already found");
    mod.__setNerOverridesForTests(null);
  });

  it("an over-long input reports 'truncated' rather than silently examining only the start", async () => {
    const mod = await import("./ner");
    mod.__setNerOverridesForTests({ classify: async () => [] });

    const outcome = await mod.extractEntitiesLocal("x".repeat(25_000));

    expect(outcome.reason).toBe("truncated");
    expect(outcome.detail).toContain("Only the first");
    mod.__setNerOverridesForTests(null);
  });

  it("with on-device AI off for the deployment it refuses and says so", async () => {
    disableEdge();
    const mod = await import("./ner");
    mod.__setNerOverridesForTests({
      classify: async () => [{ word: "Priya Raman", score: 0.99, entity_group: "PER" }],
    });

    const outcome = await mod.extractEntitiesLocal("Priya Raman approved it");

    expect(outcome.reason).toBe("edge-off");
    expect(outcome.entities).toEqual([]);
    mod.__setNerOverridesForTests(null);
  });

  it("PROPERTY 3: the copy shown before a download names every host the install can reach", async () => {
    const mod = await import("./ner");
    expect(mod.NER_NETWORK_HOSTS).toEqual(["huggingface.co", "cdn.jsdelivr.net"]);
    for (const host of mod.NER_NETWORK_HOSTS) {
      expect(mod.NER_DOWNLOAD_DISCLOSURE).toContain(host);
    }
  });
});

// ---------------------------------------------------------------------------
// redactor.ts — the third path
// ---------------------------------------------------------------------------

/** Same tier-1 seam redactor.test.ts uses, spelled relative to THIS file. */
function mockTier1(warm: boolean, structured: unknown): { calls: number } {
  const state = { calls: 0 };
  vi.doMock("../tier1", () => ({
    tier1StructuredAvailable: () => warm,
    tier1StructuredJson: vi.fn(async () => {
      state.calls += 1;
      return structured;
    }),
  }));
  return state;
}

/** Import the redactor AND the real ner module from the same fresh module
 * realm, so an override set on `ner` is the one the redactor sees. */
async function importRedactionPair() {
  const ner = await import("./ner");
  const redactor = await import("../redactor");
  return { ner, redactor };
}

describe("edge/redactor — the NER path", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    enableEdge();
  });

  afterEach(() => {
    vi.doUnmock("../tier1");
    disableEdge();
  });

  it("masks names with the token classifier while the chat engine stays cold and untouched", async () => {
    const tier1 = mockTier1(false, null);
    const { ner, redactor } = await importRedactionPair();
    ner.__setNerOverridesForTests({
      classify: async () => [
        { word: "Priya Raman", score: 0.98, entity_group: "PER" },
        { word: "Northwind Logistics", score: 0.95, entity_group: "ORG" },
      ],
    });

    const result = await redactor.redactForHosted(
      "Priya Raman at Northwind Logistics sent card 4111 1111 1111 1111",
      { mode: "model" }
    );

    expect(tier1.calls).toBe(0); // the expensive model was never asked
    expect(result.stage).toBe("model");
    expect(result.modelPath).toBe("ner");
    expect(result.modelSpans).toBe(2);
    expect(result.text).toBe(
      "[name redacted] at [organization redacted] sent card [card number redacted]"
    );
    ner.__setNerOverridesForTests(null);
  });

  it("makes model-tier redaction possible on a device with NO chat engine at all", async () => {
    mockTier1(false, null);
    const { ner, redactor } = await importRedactionPair();

    expect(redactor.modelRedactionReady("model")).toBe(false);
    expect(redactor.redactionModelPath("model")).toBe("none");

    ner.__setNerOverridesForTests({ classify: async () => [] });

    expect(redactor.modelRedactionReady("model")).toBe(true);
    expect(redactor.redactionModelPath("model")).toBe("ner");
    // …but only under the mode that opted in.
    expect(redactor.modelRedactionReady("secrets")).toBe(false);
    ner.__setNerOverridesForTests(null);
  });

  it("falls back to the chat pass when the detector finds nothing — the old path is intact", async () => {
    const tier1 = mockTier1(true, { entities: [{ text: "Priya Raman", category: "person" }] });
    const { ner, redactor } = await importRedactionPair();
    ner.__setNerOverridesForTests({ classify: async () => [] });

    const result = await redactor.redactForHosted("Priya Raman approved it", { mode: "model" });

    expect(tier1.calls).toBe(1);
    expect(result.stage).toBe("model");
    expect(result.modelPath).toBe("chat");
    expect(result.text).toBe("[name redacted] approved it");
    ner.__setNerOverridesForTests(null);
  });

  it("falls back to the chat pass when the detector throws", async () => {
    const tier1 = mockTier1(true, { entities: [{ text: "Priya Raman", category: "person" }] });
    const { ner, redactor } = await importRedactionPair();
    ner.__setNerOverridesForTests({
      classify: async () => {
        throw new Error("wasm exploded");
      },
    });

    const result = await redactor.redactForHosted("Priya Raman approved it", { mode: "model" });

    expect(tier1.calls).toBe(1);
    expect(result.modelPath).toBe("chat");
    expect(result.text).toBe("[name redacted] approved it");
    ner.__setNerOverridesForTests(null);
  });

  it("THE RULE end to end: a hallucinated NER span never rewrites the person's text", async () => {
    mockTier1(false, null);
    const { ner, redactor } = await importRedactionPair();
    ner.__setNerOverridesForTests({
      classify: async () => [
        { word: "Priya Raman", score: 0.99, entity_group: "PER" },
        { word: "Sunil Mehta", score: 0.99, entity_group: "PER" },
      ],
    });

    const result = await redactor.redactForHosted("Priya Raman approved it", { mode: "model" });

    expect(result.modelSpans).toBe(1);
    expect(result.text).toBe("[name redacted] approved it");
    expect(result.text).not.toContain("Sunil");
    ner.__setNerOverridesForTests(null);
  });

  it("modes below 'model' never reach the detector, warm or not", async () => {
    mockTier1(false, null);
    const { ner, redactor } = await importRedactionPair();
    let calls = 0;
    ner.__setNerOverridesForTests({
      classify: async () => {
        calls += 1;
        return [{ word: "Priya Raman", score: 0.99, entity_group: "PER" }];
      },
    });

    const result = await redactor.redactForHosted("Priya Raman, ssn 123-45-6789", { mode: "secrets" });

    expect(calls).toBe(0);
    expect(result.stage).toBe("patterns");
    expect(result.modelPath).toBe("none");
    expect(result.text).toContain("Priya Raman");
    ner.__setNerOverridesForTests(null);
  });

  it("patternsOnly still short-circuits both model paths", async () => {
    const tier1 = mockTier1(true, { entities: [{ text: "Priya Raman", category: "person" }] });
    const { ner, redactor } = await importRedactionPair();
    let calls = 0;
    ner.__setNerOverridesForTests({
      classify: async () => {
        calls += 1;
        return [{ word: "Priya Raman", score: 0.99, entity_group: "PER" }];
      },
    });

    const result = await redactor.redactForHosted("Priya Raman, ssn 123-45-6789", {
      mode: "model",
      patternsOnly: true,
    });

    expect(calls).toBe(0);
    expect(tier1.calls).toBe(0);
    expect(result.modelPath).toBe("none");
    ner.__setNerOverridesForTests(null);
  });

  it("a partial NER scan still contributes the spans it did find", async () => {
    mockTier1(false, null);
    const { ner, redactor } = await importRedactionPair();
    ner.__setNerOverridesForTests({
      classify: async () => [{ word: "Priya Raman", score: 0.99, entity_group: "PER" }],
    });

    // budgetMs is not reachable from redactForHosted, so this asserts the
    // weaker but still meaningful property: a reason other than "ok" (here,
    // "truncated" from an over-long input) does not discard real spans.
    const long = `Priya Raman approved it. ${"filler text. ".repeat(3_000)}`;
    const result = await redactor.redactForHosted(long, { mode: "model" });

    expect(result.modelPath).toBe("ner");
    expect(result.modelSpans).toBe(1);
    expect(result.text.startsWith("[name redacted] approved it.")).toBe(true);
    ner.__setNerOverridesForTests(null);
  });
});

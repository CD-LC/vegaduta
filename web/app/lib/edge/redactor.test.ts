// E-3 — the on-device redaction pre-flight (redactor.ts). Style follows the
// sibling tierRouter.policy.test.ts: vi.resetModules() + storage clears +
// vi.doMock + a fresh dynamic import per test, so the module-level preference
// reads and the tier-1 mock never leak between cases.
//
// What matters most here isn't that spans get masked — it's the three rules
// in redactor.ts's header: never warm a model, never block the turn, and
// never let a hallucinated span rewrite text the person actually wrote. Each
// gets its own test below.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** Mock tier1 with a controllable structured-JSON result. `warm: false`
 * reproduces the common case — no engine in memory — where the model pass
 * must not even be attempted. */
function mockTier1(warm: boolean, structured: unknown): { calls: number } {
  const state = { calls: 0 };
  vi.doMock("./tier1", () => ({
    tier1StructuredAvailable: () => warm,
    tier1StructuredJson: vi.fn(async () => {
      state.calls += 1;
      return structured;
    }),
  }));
  return state;
}

async function importRedactor() {
  return import("./redactor");
}

describe("edge/redactor — pattern tier", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
  });

  afterEach(() => {
    vi.doUnmock("./tier1");
  });

  it("defaults to 'secrets': masks a card number but leaves an email address alone", async () => {
    mockTier1(false, null);
    const { redactPatterns } = await importRedactor();

    const out = redactPatterns("card 4111 1111 1111 1111, mail me at jane@acme.com");

    expect(out).toContain("[card number redacted]");
    // The default must NOT break "draft a reply to jane@acme.com" style work.
    expect(out).toContain("jane@acme.com");
  });

  it("'contact' also masks email and phone", async () => {
    mockTier1(false, null);
    const { redactPatterns } = await importRedactor();

    const out = redactPatterns("mail jane@acme.com or call (415) 555-0142", "contact");

    expect(out).toContain("[email redacted]");
    expect(out).toContain("[phone redacted]");
  });

  it("'off' returns the input untouched — the pre-E-3 behavior, byte for byte", async () => {
    mockTier1(false, null);
    const { redactPatterns } = await importRedactor();

    const input = "ssn 123-45-6789 and card 4111 1111 1111 1111";
    expect(redactPatterns(input, "off")).toBe(input);
  });

  it("government ids are masked at the default level", async () => {
    mockTier1(false, null);
    const { redactPatterns } = await importRedactor();

    expect(redactPatterns("ssn 123-45-6789", "secrets")).toBe("ssn [ssn redacted]");
  });
});

describe("edge/redactor — model tier", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
  });

  afterEach(() => {
    vi.doUnmock("./tier1");
  });

  it("RULE 1: a cold engine never triggers the model pass — pattern result only", async () => {
    const tier1 = mockTier1(false, { entities: [{ text: "Priya", category: "person" }] });
    const { redactForHosted } = await importRedactor();

    const result = await redactForHosted("Priya sent the card 4111 1111 1111 1111", { mode: "model" });

    expect(tier1.calls).toBe(0); // nothing was asked of a model that isn't loaded
    expect(result.stage).toBe("patterns");
    expect(result.text).toContain("Priya"); // unmasked: only the model tier finds names
    expect(result.text).toContain("[card number redacted]");
  });

  it("masks the person spans a regex can't see when an engine IS warm", async () => {
    mockTier1(true, {
      entities: [
        { text: "Priya Raman", category: "person" },
        { text: "Northwind Logistics", category: "organization" },
      ],
    });
    const { redactForHosted } = await importRedactor();

    const result = await redactForHosted("Priya Raman at Northwind Logistics approved it", {
      mode: "model",
    });

    expect(result.stage).toBe("model");
    expect(result.modelSpans).toBe(2);
    expect(result.text).toBe("[name redacted] at [organization redacted] approved it");
  });

  it("RULE 3: a span that does not appear verbatim in the input is discarded", async () => {
    mockTier1(true, {
      entities: [
        { text: "Priya Raman", category: "person" },
        // Never written by the person — a model that "helpfully" expanded an
        // initial, or simply invented a name. Masking on this would rewrite
        // text that was never there.
        { text: "Sunil Mehta", category: "person" },
      ],
    });
    const { redactForHosted } = await importRedactor();

    const result = await redactForHosted("Priya Raman approved it", { mode: "model" });

    expect(result.modelSpans).toBe(1);
    expect(result.text).toBe("[name redacted] approved it");
  });

  it("RULE 2: a failing model pass degrades to the pattern result instead of throwing", async () => {
    vi.doMock("./tier1", () => ({
      tier1StructuredAvailable: () => true,
      tier1StructuredJson: vi.fn(async () => {
        throw new Error("engine exploded");
      }),
    }));
    const { redactForHosted } = await importRedactor();

    const result = await redactForHosted("card 4111 1111 1111 1111", { mode: "model" });

    expect(result.stage).toBe("patterns");
    expect(result.text).toContain("[card number redacted]");
  });

  it("an unknown category or an over-long span is ignored (constrained decoding fixes shape, not content)", async () => {
    const source = "Priya Raman and " + "x".repeat(200);
    mockTier1(true, {
      entities: [
        { text: "Priya Raman", category: "person" },
        { text: "Priya Raman", category: "wizard" }, // not in the closed set
        { text: "x".repeat(200), category: "person" }, // absurd span length
      ],
    });
    const { verifyEntities } = await importRedactor();

    const verified = verifyEntities(
      {
        entities: [
          { text: "Priya Raman", category: "person" },
          { text: "Priya Raman", category: "wizard" },
          { text: "x".repeat(200), category: "person" },
        ],
      },
      source
    );

    expect(verified).toHaveLength(1);
    expect(verified[0]).toEqual({ text: "Priya Raman", category: "person" });
  });

  it("longest span wins, so a nested name can't blank out the middle of its parent's mask", async () => {
    mockTier1(false, null);
    const { applyEntities } = await importRedactor();

    const applied = applyEntities("Acme Holdings and Acme", [
      { text: "Acme Holdings", category: "organization" },
      { text: "Acme", category: "organization" },
    ]);

    expect(applied.text).toBe("[organization redacted] and [organization redacted]");
    expect(applied.masked).toBe(2);
  });

  it("spans containing regex metacharacters are escaped, not interpreted", async () => {
    mockTier1(false, null);
    const { applyEntities } = await importRedactor();

    const applied = applyEntities("contact C++ (Pvt) Ltd today", [
      { text: "C++ (Pvt) Ltd", category: "organization" },
    ]);

    expect(applied.text).toBe("contact [organization redacted] today");
  });

  it("mode 'off' short-circuits before any pattern or model work", async () => {
    const tier1 = mockTier1(true, { entities: [{ text: "Priya", category: "person" }] });
    const { redactForHosted } = await importRedactor();

    const result = await redactForHosted("Priya, ssn 123-45-6789", { mode: "off" });

    expect(tier1.calls).toBe(0);
    expect(result.changed).toBe(false);
    expect(result.text).toBe("Priya, ssn 123-45-6789");
  });
});

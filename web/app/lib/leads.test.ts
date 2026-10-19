import { describe, expect, it } from "vitest";
import {
  ageBucket,
  draftFromLead,
  draftToPayload,
  emptyDraft,
  groupLeadsByStage,
  initials,
  isValidEmail,
  leadDisplayName,
  LEAD_STAGES,
  moveLeadLocally,
  normalizeTotals,
  openPipelineValue,
  parseTagsInput,
  scoreTone,
  validateDraft,
  type Lead,
} from "./leads";

const lead = (over: Partial<Lead> = {}): Lead => ({ id: "1", stage: "NEW", score: 0, ...over });

describe("parseTagsInput", () => {
  it("splits on comma, semicolon and newline, trims and de-dupes case-insensitively", () => {
    expect(parseTagsInput("vip, EU ;vip\n  eu ,, new ")).toEqual(["vip", "EU", "new"]);
  });
  it("returns [] for blank input", () => {
    expect(parseTagsInput("  ,; ")).toEqual([]);
  });
});

describe("validateDraft", () => {
  it("requires a name or an email", () => {
    expect(validateDraft(emptyDraft()).nameOrEmail).toBe(true);
    expect(validateDraft({ ...emptyDraft(), name: "Ann" })).toEqual({});
    expect(validateDraft({ ...emptyDraft(), email: "a@b.co" })).toEqual({});
  });
  it("flags a malformed email, out-of-range score and non-numeric value", () => {
    const errors = validateDraft({ ...emptyDraft(), name: "A", email: "nope", score: "101", value: "abc" });
    expect(errors).toEqual({ email: true, score: true, value: true });
  });
  it("accepts formatted currency values", () => {
    expect(validateDraft({ ...emptyDraft(), name: "A", value: "$1,500" })).toEqual({});
  });
});

describe("draftToPayload / draftFromLead", () => {
  it("round-trips a lead through the form", () => {
    const l = lead({ name: "Ann", email: "a@b.co", tags: ["x", "y"], value: 1200.5, score: 55, notes: "hi" });
    const payload = draftToPayload(draftFromLead(l));
    expect(payload).toMatchObject({ name: "Ann", email: "a@b.co", tags: ["x", "y"], value: 1200.5, score: 55, notes: "hi", stage: "NEW" });
  });
  it("sends an emptied value as null and blank text as empty strings", () => {
    const payload = draftToPayload({ ...emptyDraft(), name: "  Ann ", value: "" });
    expect(payload.value).toBeNull();
    expect(payload.name).toBe("Ann");
    expect(payload.phone).toBe("");
  });
  it("strips currency formatting from value", () => {
    expect(draftToPayload({ ...emptyDraft(), name: "A", value: "$2,000" }).value).toBe(2000);
  });
});

describe("groupLeadsByStage", () => {
  it("returns every stage, keeps order within a stage and tolerates unknown stages", () => {
    const grouped = groupLeadsByStage([
      lead({ id: "a", stage: "WON" }),
      lead({ id: "b", stage: "WON" }),
      lead({ id: "c", stage: "BOGUS" as never }),
    ]);
    expect(Object.keys(grouped)).toEqual([...LEAD_STAGES]);
    expect(grouped.WON.map((l) => l.id)).toEqual(["a", "b"]);
    expect(grouped.NEW.map((l) => l.id)).toEqual(["c"]);
    expect(grouped.LOST).toEqual([]);
  });
});

describe("moveLeadLocally", () => {
  it("changes only the targeted lead and does not mutate the input", () => {
    const input = [lead({ id: "a" }), lead({ id: "b" })];
    const out = moveLeadLocally(input, "b", "QUALIFIED");
    expect(out[1].stage).toBe("QUALIFIED");
    expect(out[0].stage).toBe("NEW");
    expect(input[1].stage).toBe("NEW");
  });
});

describe("pipeline totals", () => {
  it("zero-fills missing stages in board order", () => {
    const totals = normalizeTotals([{ stage: "QUALIFIED", count: 2, value: 300 }]);
    expect(totals.map((t) => t.stage)).toEqual([...LEAD_STAGES]);
    expect(totals.find((t) => t.stage === "QUALIFIED")).toEqual({ stage: "QUALIFIED", count: 2, value: 300 });
    expect(totals[0]).toEqual({ stage: "NEW", count: 0, value: 0 });
  });
  it("open pipeline excludes WON and LOST", () => {
    const totals = normalizeTotals([
      { stage: "NEW", count: 1, value: 100 },
      { stage: "PROPOSAL", count: 1, value: 50 },
      { stage: "WON", count: 1, value: 999 },
      { stage: "LOST", count: 1, value: 999 },
    ]);
    expect(openPipelineValue(totals)).toBe(150);
  });
  it("tolerates a null response", () => {
    expect(normalizeTotals(null)).toHaveLength(LEAD_STAGES.length);
  });
});

describe("display helpers", () => {
  it("prefers name, then email, then company", () => {
    expect(leadDisplayName({ name: " Ann ", email: "a@b.co" })).toBe("Ann");
    expect(leadDisplayName({ email: "a@b.co" })).toBe("a@b.co");
    expect(leadDisplayName({ company: "Acme" })).toBe("Acme");
    expect(leadDisplayName({})).toBe("-");
  });
  it("builds initials from two words or falls back to the first two letters", () => {
    expect(initials({ name: "Ann Lee" })).toBe("AL");
    expect(initials({ name: "Madonna" })).toBe("MA");
    expect(initials({ email: "jo.kim@x.com" })).toBe("JK");
  });
  it("buckets score tone", () => {
    expect([scoreTone(10), scoreTone(40), scoreTone(70)]).toEqual(["low", "mid", "high"]);
  });
  it("validates email shape", () => {
    expect(isValidEmail(" a@b.co ")).toBe(true);
    expect(isValidEmail("a@b")).toBe(false);
  });
});

describe("ageBucket", () => {
  const now = Date.parse("2026-09-25T12:00:00Z");
  it("buckets minutes, hours and days", () => {
    expect(ageBucket("2026-09-25T11:59:40Z", now)).toEqual({ unit: "now", count: 0 });
    expect(ageBucket("2026-09-25T11:30:00Z", now)).toEqual({ unit: "m", count: 30 });
    expect(ageBucket("2026-09-25T06:00:00Z", now)).toEqual({ unit: "h", count: 6 });
    expect(ageBucket("2026-09-22T12:00:00Z", now)).toEqual({ unit: "d", count: 3 });
  });
  it("returns null for missing or invalid dates", () => {
    expect(ageBucket(null, now)).toBeNull();
    expect(ageBucket("garbage", now)).toBeNull();
  });
});

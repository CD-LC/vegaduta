// Every key the pilot UI reads must exist in messages/en.json "roiPilot" (a typo would render the raw path), and no key
// may sit unused. Keys chosen at runtime are listed explicitly.
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";

const ns = (en as unknown as Record<string, Record<string, string>>).roiPilot;
const files = ["PilotSection.tsx", "DemoWatermark.tsx"].map((f) => readFileSync(path.resolve(__dirname, f), "utf8"));
const DYNAMIC = ["job_MISSED_CALL_RECOVERY", "job_APPOINTMENT_REMINDERS", "job_PAYMENT_FOLLOW_UP", "job_LEAD_FOLLOW_UP"];
const keys = [...new Set(files.flatMap((src) => [...src.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)].map((m) => m[1])))];

describe("roiPilot i18n", () => {
  it("defines every literal key and reads only its own namespace", () => {
    expect(keys.length).toBeGreaterThan(20);
    expect(keys.filter((k) => !(k in ns))).toEqual([]);
    for (const src of files) expect([...src.matchAll(/useTranslations\(\s*"([\w.-]+)"/g)].map((m) => m[1])).toEqual(["roiPilot"]);
  });
  it("defines the runtime job keys, one per job type, and has no unused key", () => {
    expect(DYNAMIC.filter((k) => !(k in ns))).toEqual([]);
    const used = new Set([...keys, ...DYNAMIC]);
    expect(Object.keys(ns).filter((k) => !used.has(k))).toEqual([]);
  });
});

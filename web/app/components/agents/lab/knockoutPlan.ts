// Agent Lab - Prompt Knockout's run plan: turns "these sections, these
// questions, this many baseline repeats" into the lab run request, and maps
// the run's cells back into the score table knockoutMath works on. Pure.
//
// KnockoutContext is what useLabRun stores next to the run id, so it must
// stay JSON-serialisable and hold everything needed to read a run back after
// the overlay was closed and reopened.
import { LAB_MAX_EXPECTED_CHARS, type LabCellRequest, type LabRun, type LabRunRequest } from "./labApi";
import { clampText } from "./labTurns";
import type { KnockoutScores } from "./knockoutMath";
import { promptWithout, type PromptSection } from "./promptSections";

export interface KnockoutItem { message: string; expected: string | null }
export interface KnockoutContext {
  sections: PromptSection[];
  testedSectionIds: string[];
  items: KnockoutItem[];
  repeats: number;
  prompt: string;
}

/** Baseline repeat `repeat` on item `item` (both 0-based). */
export const baselineKey = (repeat: number, item: number) => `b${repeat}:i${item}`;
/** The variant with `sectionId` ("s3") removed, on item `item`. */
export const sectionKey = (sectionId: string, item: number) => `${sectionId}:i${item}`;

/**
 * systemPrompts[0] is the baseline and systemPrompts[1 + j] the prompt
 * without tested section j. Cells go item by item - that item's baseline
 * repeats, then one cell per tested section - so a run that stops early
 * still holds complete items.
 */
export function buildKnockoutRequest(ctx: KnockoutContext): LabRunRequest {
  const systemPrompts = [
    promptWithout(ctx.sections, []),
    ...ctx.testedSectionIds.map((id) => promptWithout(ctx.sections, [id])),
  ];
  const cells: LabCellRequest[] = [];
  ctx.items.forEach((item, i) => {
    const expected = item.expected ? clampText(item.expected, LAB_MAX_EXPECTED_CHARS) : null;
    for (let r = 0; r < ctx.repeats; r++) {
      cells.push({ key: baselineKey(r, i), promptIndex: 0, message: item.message, expected });
    }
    ctx.testedSectionIds.forEach((id, j) => {
      cells.push({ key: sectionKey(id, i), promptIndex: j + 1, message: item.message, expected });
    });
  });
  return { kind: "KNOCKOUT", systemPrompts, cells, scoring: "JUDGE", similarity: "NONE" };
}

/** A cell that is missing, failed, or has no judge score yet reads as null. */
export function readKnockoutScores(ctx: KnockoutContext, run: LabRun): KnockoutScores {
  const byKey = new Map(run.cells.map((cell) => [cell.key, cell]));
  const scoreOf = (key: string): number | null => {
    const cell = byKey.get(key);
    if (!cell || cell.scoreStatus !== "SCORED") return null;
    return typeof cell.score === "number" && Number.isFinite(cell.score) ? cell.score : null;
  };
  const baseline = ctx.items.map((_, i) => Array.from({ length: ctx.repeats }, (__, r) => scoreOf(baselineKey(r, i))));
  const sections: Record<string, (number | null)[]> = {};
  for (const id of ctx.testedSectionIds) {
    sections[id] = ctx.items.map((_, i) => scoreOf(sectionKey(id, i)));
  }
  return { baseline, sections };
}

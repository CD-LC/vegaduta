// Agent Lab - Prompt Knockout's arithmetic. Pure functions from judge scores
// to a per-section "score lost when this section is removed" and a label.
//
// The question every label answers is "is this change bigger than what two
// runs of the SAME prompt would differ by?". The baseline prompt is therefore
// run several times per question, the spread between those identical runs is
// pooled into one variance, and a section is only labelled when its change
// clears a noise floor built from that variance - widened for the number of
// sections tested (each extra section is another chance of a false label) and
// for how few repeats the variance was estimated from.
//
// Two further guards keep a label from resting on almost nothing:
//  - no floor (so no label) until the baseline carries MIN_LABEL_DF degrees
//    of freedom and the section was measured on MIN_LABEL_ITEMS questions;
//  - the variance never counts as smaller than the judge's own resolution.
//    An LLM judge scores in steps of about 0.1, so a handful of repeats that
//    happen to land on the same step say "no noise" when the next answer can
//    still sit one step away.

export const MIN_NOISE_FLOOR = 0.05;
/** Baseline degrees of freedom (sum of repeats - 1 over questions) below which nothing is labelled. */
export const MIN_LABEL_DF = 4;
/** Questions a section must be measured on before it can be labelled. */
export const MIN_LABEL_ITEMS = 2;
/** The step an LLM judge normally scores in. */
export const JUDGE_SCORE_STEP = 0.1;
/** Variance of a value known only to the nearest judge step (uniform over one step: step^2 / 12). */
export const SCORE_RESOLUTION_VARIANCE = JUDGE_SCORE_STEP ** 2 / 12;
export interface KnockoutScores { baseline: (number | null)[][]; sections: Record<string, (number | null)[]> } // [item][repeat], id -> [item]
export type KnockoutLabel = "load-bearing" | "harmful" | "no-effect" | "not-measured";
export interface BaselineNoise { s2: number | null; df: number; itemMeans: (number | null)[]; itemRepeats: number[] }
export interface SectionAttribution { sectionId: string; delta: number | null; floor: number | null; label: KnockoutLabel; nItems: number; perItem: (number | null)[]; agreeCount: number }
export interface KnockoutResult { baselineMean: number | null; noiseFloor: number | null; df: number; nItems: number; repeats: number; testedSections: number; sections: SectionAttribution[] }

// Scores are sums and means of decimals; without this, 0.75 - 0.7 reads as
// "just over" a floor of exactly 0.05 and would be labelled.
const EPSILON = 1e-9;

const isScore = (value: number | null | undefined): value is number => typeof value === "number" && Number.isFinite(value);

function mean(values: number[]): number {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

/**
 * Pooled within-item variance of the baseline repeats. For item i,
 * `itemRepeats[i]` is how many repeats produced a score and `itemMeans[i]`
 * their mean (null when none did). `df` sums (R_i - 1) over items with at
 * least two scores; `s2` is null when that leaves fewer than 2 degrees of
 * freedom, because a variance from one difference is not an estimate.
 */
export function baselineNoise(baseline: (number | null)[][]): BaselineNoise {
  const itemMeans: (number | null)[] = [];
  const itemRepeats: number[] = [];
  let df = 0;
  let squares = 0;
  for (const row of baseline) {
    const scored = (row ?? []).filter(isScore);
    itemRepeats.push(scored.length);
    if (scored.length === 0) {
      itemMeans.push(null);
      continue;
    }
    const itemMean = mean(scored);
    itemMeans.push(itemMean);
    if (scored.length >= 2) {
      df += scored.length - 1;
      for (const score of scored) squares += (score - itemMean) ** 2;
    }
  }
  return { s2: df >= 2 ? squares / df : null, df, itemMeans, itemRepeats };
}

/** Two-sided normal quantile for a family error of 0.05 split across k sections. */
export function zForSections(k: number): number {
  if (k <= 1) return 1.96;
  if (k === 2) return 2.241;
  if (k === 3) return 2.394;
  if (k === 4) return 2.498;
  if (k <= 6) return 2.638;
  if (k <= 8) return 2.734;
  return 2.865;
}

/** Widens z for a variance estimated from few repeats (normal-to-t expansion in 1/df). */
export function tApprox(z: number, df: number): number {
  return z + (z ** 3 + z) / (4 * df) + (5 * z ** 5 + 16 * z ** 3 + 3 * z) / (96 * df * df);
}

/** Whether a plan of `items` questions with `repeats` baseline runs each can
 * produce a label at all. The form checks this before anything is spent. */
export function canEstimateFloor(items: number, repeats: number): boolean {
  return items >= MIN_LABEL_ITEMS && items * (repeats - 1) >= MIN_LABEL_DF;
}

/**
 * The noise floor for a mean difference taken over the items in
 * `itemIndices`: each item contributes the variance of its baseline mean
 * (s2 / R_i) plus that of the single variant score (s2). The variance used
 * is never below SCORE_RESOLUTION_VARIANCE and the floor never below
 * MIN_NOISE_FLOOR. Null - nothing may be labelled - when there is no variance
 * estimate, fewer than MIN_LABEL_DF degrees of freedom behind it, or fewer
 * than MIN_LABEL_ITEMS items.
 */
export function floorFor(noise: BaselineNoise, itemIndices: number[], testedSections: number): number | null {
  if (noise.s2 === null || noise.df < MIN_LABEL_DF || itemIndices.length < MIN_LABEL_ITEMS) return null;
  let weight = 0;
  for (const i of itemIndices) {
    const repeats = noise.itemRepeats[i];
    if (!(repeats >= 1)) return null;
    weight += 1 + 1 / repeats;
  }
  const s2 = Math.max(noise.s2, SCORE_RESOLUTION_VARIANCE);
  const se = Math.sqrt(s2 * weight) / itemIndices.length;
  return Math.max(MIN_NOISE_FLOOR, tApprox(zForSections(testedSections), noise.df) * se);
}

function labelFor(delta: number | null, floor: number | null): KnockoutLabel {
  if (delta === null || floor === null) return "not-measured";
  if (delta > floor + EPSILON) return "load-bearing";
  if (delta < -floor - EPSILON) return "harmful";
  return "no-effect";
}

/**
 * One attribution per tested section, in `sectionOrder`. A section's delta
 * is the mean, over the items that have both a baseline mean and a score for
 * that section, of (baseline mean - score without the section): positive
 * means the score dropped when the section was removed.
 */
export function attributeSections(scores: KnockoutScores, sectionOrder: string[]): KnockoutResult {
  const noise = baselineNoise(scores.baseline);
  const measured = noise.itemMeans.flatMap((itemMean, i) => (itemMean === null ? [] : [i]));
  const testedSections = sectionOrder.length;

  const sections = sectionOrder.map((sectionId): SectionAttribution => {
    const row = scores.sections[sectionId] ?? [];
    const perItem = noise.itemMeans.map((itemMean, i): number | null => {
      const score = row[i];
      return itemMean !== null && isScore(score) ? itemMean - score : null;
    });
    const used = perItem.flatMap((difference, i) => (difference === null ? [] : [i]));
    const delta = used.length > 0 ? mean(used.map((i) => perItem[i] as number)) : null;
    const floor = floorFor(noise, used, testedSections);
    const direction = delta === null ? 0 : Math.sign(delta);
    const agreeCount = delta === null ? 0 : used.filter((i) => Math.sign(perItem[i] as number) === direction).length;
    return { sectionId, delta, floor, label: labelFor(delta, floor), nItems: used.length, perItem, agreeCount };
  });

  return {
    baselineMean: measured.length > 0 ? mean(measured.map((i) => noise.itemMeans[i] as number)) : null,
    noiseFloor: floorFor(noise, measured, testedSections),
    df: noise.df,
    nItems: measured.length,
    repeats: noise.itemRepeats.reduce((most, count) => Math.max(most, count), 0),
    testedSections,
    sections,
  };
}

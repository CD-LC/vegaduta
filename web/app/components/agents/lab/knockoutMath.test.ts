// Prompt Knockout's arithmetic, checked against numbers worked out by hand,
// plus a seeded simulation of the case that matters most: a prompt where NO
// section does anything. The labels are only worth showing if that case
// almost always comes back "no measurable effect".

import { describe, expect, it } from "vitest";
import {
  MIN_LABEL_DF,
  MIN_LABEL_ITEMS,
  MIN_NOISE_FLOOR,
  SCORE_RESOLUTION_VARIANCE,
  attributeSections,
  baselineNoise,
  canEstimateFloor,
  floorFor,
  tApprox,
  zForSections,
  type KnockoutScores,
} from "./knockoutMath";

// 3 items x 3 baseline repeats.
//   item 0: 0.8 0.7 0.9  mean 0.8  squared deviations 0 + 0.01 + 0.01 = 0.02
//   item 1: 0.6 0.6 0.9  mean 0.7  squared deviations 0.01 + 0.01 + 0.04 = 0.06
//   item 2: 0.5 0.7 0.6  mean 0.6  squared deviations 0.01 + 0.01 + 0 = 0.02
// df = 3 * (3 - 1) = 6, s2 = 0.10 / 6 = 0.016667.
const BASELINE = [
  [0.8, 0.7, 0.9],
  [0.6, 0.6, 0.9],
  [0.5, 0.7, 0.6],
];

describe("baselineNoise", () => {
  it("pools the within-item variance of the baseline repeats", () => {
    const noise = baselineNoise(BASELINE);
    expect(noise.df).toBe(6);
    expect(noise.s2).toBeCloseTo(0.016667, 6);
    expect(noise.itemRepeats).toEqual([3, 3, 3]);
    expect(noise.itemMeans[0]).toBeCloseTo(0.8, 12);
    expect(noise.itemMeans[1]).toBeCloseTo(0.7, 12);
    expect(noise.itemMeans[2]).toBeCloseTo(0.6, 12);
  });

  it("skips missing repeats, and counts only items with two or more scores toward df", () => {
    const noise = baselineNoise([
      [0.8, null, 0.6],   // mean 0.7, contributes 1 df, squares 0.02
      [null, 0.5, null],  // mean 0.5, contributes nothing
      [null, null, null], // no mean
      [0.4, 0.6, 0.5],    // mean 0.5, contributes 2 df, squares 0.02
    ]);
    expect(noise.itemRepeats).toEqual([2, 1, 0, 3]);
    expect(noise.itemMeans[0]).toBeCloseTo(0.7, 12);
    expect(noise.itemMeans[1]).toBe(0.5);
    expect(noise.itemMeans[2]).toBeNull();
    expect(noise.df).toBe(3);
    expect(noise.s2).toBeCloseTo(0.04 / 3, 10);
  });

  it("has no variance estimate below 2 degrees of freedom", () => {
    expect(baselineNoise([[0.5], [0.7], [0.9]])).toMatchObject({ s2: null, df: 0 });
    expect(baselineNoise([[0.5, 0.7]])).toMatchObject({ s2: null, df: 1 });
    expect(baselineNoise([])).toEqual({ s2: null, df: 0, itemMeans: [], itemRepeats: [] });
  });
});

describe("zForSections", () => {
  it("matches the table", () => {
    expect([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 40].map(zForSections)).toEqual([
      1.96, 1.96, 2.241, 2.394, 2.498, 2.638, 2.638, 2.734, 2.734, 2.865, 2.865, 2.865,
    ]);
  });
});

describe("tApprox", () => {
  it("reproduces the t quantile for 20 degrees of freedom", () => {
    expect(Math.abs(tApprox(1.96, 20) - 2.086)).toBeLessThan(0.005);
  });

  it("is always wider than z and narrows toward it as df grows", () => {
    const widths = [2, 4, 8, 16, 64, 1024].map((df) => tApprox(1.96, df));
    for (let i = 1; i < widths.length; i++) expect(widths[i]).toBeLessThan(widths[i - 1]);
    expect(widths[widths.length - 1]).toBeGreaterThan(1.96);
    expect(widths[widths.length - 1] - 1.96).toBeLessThan(0.005);
  });
});

describe("floorFor", () => {
  it("matches the hand-computed floor", () => {
    // se = sqrt(s2 * 3 * (1 + 1/3)) / 3 = sqrt(0.066667) / 3 = 0.086066
    // tApprox(2.241, 6) = 2.241 + 0.562312 + 0.135822 = 2.939133
    // floor = 2.939133 * 0.086066 = 0.252960
    const noise = baselineNoise(BASELINE);
    expect(tApprox(2.241, 6)).toBeCloseTo(2.939133, 5);
    expect(floorFor(noise, [0, 1, 2], 2)).toBeCloseTo(0.25296, 5);
    // One section tested: z 1.96, tApprox(1.96, 6) = 2.433806.
    expect(floorFor(noise, [0, 1, 2], 1)).toBeCloseTo(0.209469, 5);
  });

  it("is null without a variance estimate or without items", () => {
    expect(floorFor(baselineNoise([[0.5], [0.7]]), [0, 1], 1)).toBeNull();
    expect(floorFor(baselineNoise(BASELINE), [], 1)).toBeNull();
    // Item 1 has no baseline score at all, so it cannot be part of a floor.
    expect(floorFor(baselineNoise([[0.5, 0.6, 0.7], [null, null, null]]), [0, 1], 1)).toBeNull();
  });

  it("is null until the baseline carries enough data to label anything", () => {
    expect(MIN_LABEL_DF).toBe(4);
    expect(MIN_LABEL_ITEMS).toBe(2);
    // One question, three repeats: a variance from 2 degrees of freedom.
    const oneByThree = baselineNoise([[0.8, 0.7, 0.9]]);
    expect(oneByThree).toMatchObject({ df: 2 });
    expect(oneByThree.s2).not.toBeNull();
    expect(floorFor(oneByThree, [0], 1)).toBeNull();
    // Two questions, two repeats each: still 2 degrees of freedom.
    expect(floorFor(baselineNoise([[0.5, 0.5], [0.25, 0.25]]), [0, 1], 12)).toBeNull();
    // One question, five repeats: enough repeats, but a single question.
    const oneByFive = baselineNoise([[0.8, 0.7, 0.9, 0.8, 0.7]]);
    expect(oneByFive.df).toBe(4);
    expect(floorFor(oneByFive, [0], 1)).toBeNull();
    // Enough baseline overall, but this section was measured on one question only.
    expect(floorFor(baselineNoise(BASELINE), [1], 2)).toBeNull();
    expect(floorFor(baselineNoise(BASELINE), [0, 1], 2)).not.toBeNull();
  });

  it("does not let identical repeats read as zero noise: the variance is floored at the judge's resolution", () => {
    const noise = baselineNoise([[0.8, 0.8, 0.8], [0.6, 0.6, 0.6]]);
    // (0.8 + 0.8 + 0.8) / 3 is not exactly 0.8, so s2 is zero only up to rounding.
    expect(noise.s2).toBeCloseTo(0, 12);
    expect(SCORE_RESOLUTION_VARIANCE).toBeCloseTo(0.01 / 12, 12);
    // se = sqrt(0.01/12 * 2 * (1 + 1/3)) / 2 = 0.023570, times tApprox(2.394, 4).
    const expected = (tApprox(2.394, 4) * Math.sqrt(SCORE_RESOLUTION_VARIANCE * 2 * (1 + 1 / 3))) / 2;
    expect(floorFor(noise, [0, 1], 3)).toBeCloseTo(expected, 12);
    // One judge step (0.1) on one answer of the two is a 0.05 mean change: inside it.
    expect(floorFor(noise, [0, 1], 3) as number).toBeGreaterThan(0.05);
    expect(floorFor(noise, [0, 1], 3) as number).toBeLessThan(0.1);
  });

  it("never drops below the minimum", () => {
    // 10 questions x 5 identical repeats, one section: the computed floor is about 0.02.
    const noise = baselineNoise(Array.from({ length: 10 }, () => [0.75, 0.75, 0.75, 0.75, 0.75]));
    expect(noise.df).toBe(40);
    expect(floorFor(noise, [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], 1)).toBe(MIN_NOISE_FLOOR);
  });
});

describe("canEstimateFloor", () => {
  it("needs two questions and four baseline degrees of freedom", () => {
    expect(canEstimateFloor(1, 2)).toBe(false);
    expect(canEstimateFloor(1, 5)).toBe(false);
    expect(canEstimateFloor(2, 2)).toBe(false);
    expect(canEstimateFloor(3, 2)).toBe(false);
    expect(canEstimateFloor(2, 3)).toBe(true);
    expect(canEstimateFloor(4, 2)).toBe(true);
    expect(canEstimateFloor(5, 3)).toBe(true);
  });

  it("agrees with floorFor on a full table of that shape", () => {
    for (const [items, repeats] of [[1, 3], [1, 5], [2, 2], [3, 2], [2, 3], [4, 2], [5, 3]] as const) {
      const noise = baselineNoise(
        Array.from({ length: items }, (_, i) => Array.from({ length: repeats }, (__, r) => 0.5 + 0.1 * ((i + r) % 3)))
      );
      const all = Array.from({ length: items }, (_, i) => i);
      expect(floorFor(noise, all, 3) !== null, `${items}x${repeats}`).toBe(canEstimateFloor(items, repeats));
    }
  });
});

describe("attributeSections", () => {
  it("computes each section's delta, floor and label on the hand-worked table", () => {
    const scores: KnockoutScores = {
      baseline: BASELINE,
      sections: {
        s1: [0.4, 0.3, 0.3],    // differences 0.4 0.4 0.3  -> delta 0.366667
        s2: [0.9, 0.75, 0.55],  // differences -0.1 -0.05 0.05 -> delta -0.033333
      },
    };
    const result = attributeSections(scores, ["s1", "s2"]);
    expect(result).toMatchObject({ df: 6, nItems: 3, repeats: 3, testedSections: 2 });
    expect(result.baselineMean).toBeCloseTo(0.7, 10);
    expect(result.noiseFloor).toBeCloseTo(0.25296, 5);

    const [s1, s2] = result.sections;
    expect(s1.sectionId).toBe("s1");
    expect(s1.delta).toBeCloseTo(0.366667, 6);
    expect(s1.floor).toBeCloseTo(0.25296, 5);
    expect(s1.label).toBe("load-bearing");
    expect(s1.nItems).toBe(3);
    expect(s1.agreeCount).toBe(3);
    expect(s1.perItem[0]).toBeCloseTo(0.4, 10);
    expect(s1.perItem[2]).toBeCloseTo(0.3, 10);

    expect(s2.delta).toBeCloseTo(-0.033333, 6);
    expect(s2.label).toBe("no-effect");
    // Two of the three questions moved the same way as the mean (down).
    expect(s2.agreeCount).toBe(2);
  });

  it("labels a section harmful when removing it raised the score beyond the floor", () => {
    const result = attributeSections(
      { baseline: [[0.5, 0.5, 0.5], [0.4, 0.4, 0.4]], sections: { s1: [0.9, 0.8], s2: [0.1, 0.0] } },
      ["s1", "s2"]
    );
    // Identical repeats: the floor comes from the judge's resolution, about 0.08.
    expect(result.noiseFloor).toBeCloseTo((tApprox(2.241, 4) * Math.sqrt(SCORE_RESOLUTION_VARIANCE * 2 * (1 + 1 / 3))) / 2, 12);
    expect(result.sections[0]).toMatchObject({ label: "harmful", agreeCount: 2 });
    expect(result.sections[0].delta).toBeCloseTo(-0.4, 10);
    expect(result.sections[1]).toMatchObject({ label: "load-bearing", agreeCount: 2 });
  });

  it("treats a delta exactly at the floor as no measurable effect", () => {
    // 10 questions x 5 identical repeats: floor = MIN_NOISE_FLOOR = 0.05.
    // 0.75 - 0.70 is 0.05 up to floating-point dust, which must not tip it over.
    const baseline = Array.from({ length: 10 }, () => [0.75, 0.75, 0.75, 0.75, 0.75]);
    const all = (score: number) => Array.from({ length: 10 }, () => score);
    const at = attributeSections({ baseline, sections: { s1: all(0.7), s2: all(0.8) } }, ["s1", "s2"]);
    expect(at.sections[0].floor).toBe(MIN_NOISE_FLOOR);
    expect(at.sections[0].label).toBe("no-effect");
    expect(at.sections[1].label).toBe("no-effect");
    const beyond = attributeSections({ baseline, sections: { s1: all(0.69), s2: all(0.81) } }, ["s1", "s2"]);
    expect(beyond.sections[0].label).toBe("load-bearing");
    expect(beyond.sections[1].label).toBe("harmful");
  });

  it("labels nothing from one question, however large the change and however many repeats", () => {
    // 1 question, 3 repeats that happen to coincide: 2 degrees of freedom.
    const three = attributeSections({ baseline: [[0.8, 0.8, 0.8]], sections: { s1: [0.9], s2: [0.2] } }, ["s1", "s2"]);
    expect(three.noiseFloor).toBeNull();
    expect(three.sections.map((s) => s.label)).toEqual(["not-measured", "not-measured"]);
    expect(three.sections[1].delta).toBeCloseTo(0.6, 10);
    expect(three.sections[1].floor).toBeNull();
    const five = attributeSections({ baseline: [[0.8, 0.8, 0.8, 0.8, 0.8]], sections: { s1: [0.2] } }, ["s1"]);
    expect(five.df).toBe(4);
    expect(five.sections[0]).toMatchObject({ label: "not-measured", floor: null, nItems: 1 });
  });

  it("labels nothing from two questions with two repeats each (2 degrees of freedom)", () => {
    const result = attributeSections({ baseline: [[0.8, 0.8], [0.6, 0.6]], sections: { s1: [0.1, 0.1] } }, ["s1"]);
    expect(result.df).toBe(2);
    expect(result.noiseFloor).toBeNull();
    expect(result.sections[0]).toMatchObject({ label: "not-measured", floor: null, nItems: 2 });
    expect(result.sections[0].delta).toBeCloseTo(0.6, 10);
  });

  it("does not label a one-step judge wobble when the baseline repeats coincide", () => {
    // 2 questions x 3 identical repeats. A variant whose answers BOTH land a
    // judge step (0.1) away is labelled; one answer of the two is not.
    const baseline = [[0.8, 0.8, 0.8], [0.8, 0.8, 0.8]];
    const both = attributeSections({ baseline, sections: { up: [0.9, 0.9], down: [0.7, 0.7], same: [0.8, 0.8] } }, ["up", "down", "same"]);
    expect(both.sections.map((s) => s.label)).toEqual(["harmful", "load-bearing", "no-effect"]);
    const one = attributeSections({ baseline, sections: { up: [0.9, 0.8], down: [0.7, 0.8] } }, ["up", "down"]);
    expect(one.sections.map((s) => s.label)).toEqual(["no-effect", "no-effect"]);
  });

  it("labels nothing when each item was only run once", () => {
    const result = attributeSections(
      { baseline: [[0.9], [0.8], [0.7]], sections: { s1: [0.1, 0.1, 0.1], s2: [0.9, 0.8, 0.7] } },
      ["s1", "s2"]
    );
    expect(result.noiseFloor).toBeNull();
    expect(result.repeats).toBe(1);
    expect(result.nItems).toBe(3);
    expect(result.sections.map((s) => s.label)).toEqual(["not-measured", "not-measured"]);
    // The delta itself is still reported.
    expect(result.sections[0].delta).toBeCloseTo(0.7, 10);
    expect(result.sections[0].floor).toBeNull();
  });

  it("counts the questions that moved the same way as the mean", () => {
    const result = attributeSections(
      { baseline: [[0.8, 0.8], [0.8, 0.8], [0.8, 0.8], [0.8, 0.8]], sections: { s1: [0.5, 0.5, 0.9, 0.5] } },
      ["s1"]
    );
    expect(result.sections[0].delta).toBeCloseTo(0.2, 10);
    expect(result.sections[0].agreeCount).toBe(3);
    expect(result.sections[0].nItems).toBe(4);
  });

  it("gives a section measured on fewer questions a larger floor", () => {
    const scores: KnockoutScores = {
      baseline: [BASELINE[0], BASELINE[1], BASELINE[2], [0.7, 0.8, 0.9]],
      sections: { full: [0.5, 0.5, 0.5, 0.5], partial: [0.5, null, 0.5, null], missing: [null, null, null, null] },
    };
    const result = attributeSections(scores, ["full", "partial", "missing", "unknown"]);
    const [full, partial, missing, unknown] = result.sections;
    expect(full.nItems).toBe(4);
    expect(partial.nItems).toBe(2);
    expect(partial.perItem).toEqual([expect.any(Number), null, expect.any(Number), null]);
    expect(partial.floor as number).toBeGreaterThan(full.floor as number);
    // The run-wide floor is the one over every item with a baseline.
    expect(result.noiseFloor).toBe(full.floor);
    for (const empty of [missing, unknown]) {
      expect(empty).toMatchObject({ delta: null, floor: null, label: "not-measured", nItems: 0, agreeCount: 0 });
    }
  });

  it("leaves an item with no baseline score out of every figure", () => {
    const result = attributeSections(
      { baseline: [[0.8, 0.6, 0.7], [null, null, null], [0.5, 0.7, 0.6]], sections: { s1: [0.2, 0.2, 0.2] } },
      ["s1"]
    );
    expect(result.nItems).toBe(2);
    expect(result.baselineMean).toBeCloseTo(0.65, 10);
    expect(result.sections[0].nItems).toBe(2);
    expect(result.sections[0].perItem[1]).toBeNull();
    expect(result.sections[0].delta).toBeCloseTo(0.45, 10);
  });

  it("handles an empty run", () => {
    expect(attributeSections({ baseline: [], sections: {} }, [])).toEqual({
      baselineMean: null, noiseFloor: null, df: 0, nItems: 0, repeats: 0, testedSections: 0, sections: [],
    });
  });
});

// --- Seeded null simulation -------------------------------------------------

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let x = a;
    x = Math.imul(x ^ (x >>> 15), x | 1);
    x ^= x + Math.imul(x ^ (x >>> 7), x | 61);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}

/** Box-Muller standard normals; scores are deliberately NOT clamped to 0..1. */
function normalSource(random: () => number): () => number {
  return () => {
    const u = 1 - random(); // (0, 1], so the log is finite
    const v = random();
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  };
}

describe("null simulation: no section has any effect", () => {
  it("labels a section in under 12% of runs, and under 2% of all sections", () => {
    const TRIALS = 400;
    const ITEMS = 8;
    const REPEATS = 3;
    const SECTIONS = 12;
    const normal = normalSource(mulberry32(20260930));
    const score = () => 0.7 + 0.1 * normal();
    const order = Array.from({ length: SECTIONS }, (_, j) => `s${j + 1}`);

    let trialsWithLabel = 0;
    let labelledSections = 0;
    for (let trial = 0; trial < TRIALS; trial++) {
      const scores: KnockoutScores = {
        baseline: Array.from({ length: ITEMS }, () => Array.from({ length: REPEATS }, score)),
        sections: Object.fromEntries(order.map((id) => [id, Array.from({ length: ITEMS }, score)])),
      };
      const labelled = attributeSections(scores, order).sections.filter(
        (section) => section.label === "load-bearing" || section.label === "harmful"
      ).length;
      if (labelled > 0) trialsWithLabel += 1;
      labelledSections += labelled;
    }

    expect(trialsWithLabel / TRIALS).toBeLessThan(0.12);
    expect(labelledSections / (TRIALS * SECTIONS)).toBeLessThan(0.02);
  });

  // The judge does not return continuous scores. These are the shapes the
  // continuous simulation above cannot see: few questions, few repeats, and
  // scores that land on a handful of 0.1 steps, so baseline repeats coincide.
  function labelRate(items: number, repeats: number, sections: number, score: () => number, trials: number): number {
    const order = Array.from({ length: sections }, (_, j) => `s${j + 1}`);
    let trialsWithLabel = 0;
    for (let trial = 0; trial < trials; trial++) {
      const scores: KnockoutScores = {
        baseline: Array.from({ length: items }, () => Array.from({ length: repeats }, score)),
        sections: Object.fromEntries(order.map((id) => [id, Array.from({ length: items }, score)])),
      };
      if (attributeSections(scores, order).sections.some((s) => s.label === "load-bearing" || s.label === "harmful")) trialsWithLabel += 1;
    }
    return trialsWithLabel / trials;
  }

  it("with scores on 0.1 steps: never labels at 1x3, 1x5 or 2x2, and under 8% of runs at every shape that can label", () => {
    const random = mulberry32(20260930);
    const normal = normalSource(random);
    const rounded = () => Math.round((0.7 + 0.1 * normal()) * 10) / 10;
    // A coarse judge: 0.8 most of the time, one step up or down otherwise.
    const coarse = () => {
      const r = random();
      return r < 0.7 ? 0.8 : r < 0.9 ? 0.9 : 0.7;
    };
    for (const score of [rounded, coarse]) {
      expect(labelRate(1, 3, 12, score, 500)).toBe(0);
      expect(labelRate(1, 5, 12, score, 500)).toBe(0);
      expect(labelRate(2, 2, 12, score, 500)).toBe(0);
      for (const [items, repeats] of [[2, 3], [3, 3], [4, 2], [5, 3]] as const) {
        expect(labelRate(items, repeats, 12, score, 1500), `${items}x${repeats}, 12 sections`).toBeLessThan(0.08);
        expect(labelRate(items, repeats, 3, score, 1500), `${items}x${repeats}, 3 sections`).toBeLessThan(0.08);
      }
    }
  });
});

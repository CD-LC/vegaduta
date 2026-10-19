// Consistency check arithmetic: leader clustering, entropy, the three states,
// and the rule that matters most - a comparison with no verdict is never
// reported as a disagreement. When reading the missing verdicts as "same" or
// as "different" would change the headline, the result is Inconclusive.

import { describe, expect, it } from "vitest";
import {
  assembleConsistency,
  classifyConsistency,
  clusterByLeader,
  clusterEntropyBits,
  normalisedEntropy,
  pairVerdict,
  withinClusterPairs,
} from "./consistencyMath";
import type { LabCellResult, LabRun, LabSimilarity } from "./labApi";

type Matrix = (number | null)[][];

/** A symmetric verdict matrix for the given groups: same group 1, else 0. */
function matrixOf(groups: number[]): Matrix {
  return groups.map((a) => groups.map((b) => (a === b ? 1 : 0)));
}

/** Sets one pair, in both triangles. */
function withPair(matrix: Matrix, a: number, b: number, value: number | null): Matrix {
  const next = matrix.map((row) => row.slice());
  next[a][b] = value;
  next[b][a] = value;
  return next;
}

function cell(key: string, status: LabCellResult["status"] = "DONE"): LabCellResult {
  return {
    key, status,
    answer: status === "DONE" ? `answer ${key}` : null, answerTruncated: false,
    score: null, scoreStatus: "NONE", scoreFeedback: null,
    errorMessage: status === "ERROR" ? "The model call failed." : null, ms: 100,
  };
}

/** A finished run of matrix.length samples, all DONE, with this matrix. */
function runOf(matrix: Matrix): Pick<LabRun, "cells" | "similarity"> {
  const keys = matrix.map((_, n) => `k${n}`);
  const similarity: LabSimilarity = { method: "JUDGE", status: "DONE", keys, matrix };
  return { cells: keys.map((key) => cell(key)), similarity };
}

describe("clusterByLeader", () => {
  it("puts samples that all match in one cluster", () => {
    expect(clusterByLeader(matrixOf([0, 0, 0, 0, 0]))).toEqual({ clusters: [[0, 1, 2, 3, 4]], unknownPairs: 0 });
  });

  it("starts a new cluster for a sample no leader accepts, keeping sample order", () => {
    expect(clusterByLeader(matrixOf([0, 1, 0, 2, 1])).clusters).toEqual([[0, 2], [1, 4], [3]]);
  });

  it("is deterministic on a non-transitive matrix: only leaders are consulted", () => {
    // 0 = 1 and 1 = 2, but 0 != 2. Sample 2 is compared with leader 0 only.
    const matrix: Matrix = [
      [1, 1, 0],
      [1, 1, 1],
      [0, 1, 1],
    ];
    const first = clusterByLeader(matrix);
    expect(first.clusters).toEqual([[0, 1], [2]]);
    expect(clusterByLeader(matrix)).toEqual(first);
  });

  it("joins the first cluster whose leader accepts, in cluster order", () => {
    // Sample 2 matches both leaders (0 and 1, which differ): it joins 0's.
    const matrix: Matrix = [
      [1, 0, 1],
      [0, 1, 1],
      [1, 1, 1],
    ];
    expect(clusterByLeader(matrix).clusters).toEqual([[0, 2], [1]]);
  });

  it("reads a verdict from either triangle", () => {
    const upperOnly: Matrix = [
      [1, 1, 0],
      [null, 1, 0],
      [null, null, 1],
    ];
    expect(clusterByLeader(upperOnly)).toEqual({ clusters: [[0, 1], [2]], unknownPairs: 0 });
    expect(pairVerdict(upperOnly, 2, 0)).toBe(0);
    expect(pairVerdict(upperOnly, 1, 0)).toBe(1);
  });

  it("counts a missing verdict and reads it as different by default", () => {
    const matrix = withPair(matrixOf([0, 0]), 0, 1, null);
    expect(clusterByLeader(matrix)).toEqual({ clusters: [[0], [1]], unknownPairs: 1 });
  });

  it("reads a missing verdict as same when asked to", () => {
    const matrix = withPair(matrixOf([0, 0]), 0, 1, null);
    expect(clusterByLeader(matrix, { unknownAs: "same" })).toEqual({ clusters: [[0, 1]], unknownPairs: 1 });
  });

  it("treats a verdict at the threshold as same, and honours a custom threshold", () => {
    const matrix = withPair(matrixOf([0, 0]), 0, 1, 0.5);
    expect(clusterByLeader(matrix).clusters).toEqual([[0, 1]]);
    expect(clusterByLeader(matrix, { threshold: 0.8 }).clusters).toEqual([[0], [1]]);
  });

  it("returns nothing for an empty matrix", () => {
    expect(clusterByLeader([])).toEqual({ clusters: [], unknownPairs: 0 });
  });
});

describe("withinClusterPairs", () => {
  it("finds nothing to report when every pair inside each cluster was judged the same", () => {
    const matrix = matrixOf([0, 1, 0, 1, 0]);
    expect(withinClusterPairs(matrix, clusterByLeader(matrix).clusters)).toEqual({ different: 0, unknown: 0 });
  });

  it("counts the pairs inside a cluster that were judged different, and the ones with no verdict", () => {
    // All four match leader 0. 1-2 was judged different, 2-3 has no verdict.
    let matrix = matrixOf([0, 0, 0, 0]);
    matrix = withPair(matrix, 1, 2, 0);
    matrix = withPair(matrix, 2, 3, null);
    const { clusters } = clusterByLeader(matrix);
    expect(clusters).toEqual([[0, 1, 2, 3]]);
    expect(withinClusterPairs(matrix, clusters)).toEqual({ different: 1, unknown: 1 });
  });

  it("ignores pairs that span two clusters", () => {
    const matrix = withPair(matrixOf([0, 0, 1, 1]), 1, 3, null);
    expect(withinClusterPairs(matrix, clusterByLeader(matrix).clusters)).toEqual({ different: 0, unknown: 0 });
  });
});

describe("clusterEntropyBits / normalisedEntropy", () => {
  it("is 0 for one cluster", () => {
    expect(clusterEntropyBits([5])).toBe(0);
    expect(Object.is(clusterEntropyBits([5]), -0)).toBe(false);
    expect(normalisedEntropy([5])).toBe(0);
  });

  it("is about 0.971 bits for a 3 + 2 split", () => {
    expect(clusterEntropyBits([3, 2])).toBeCloseTo(0.971, 3);
    expect(normalisedEntropy([3, 2])).toBeCloseTo(0.971 / Math.log2(5), 3);
  });

  it("is 1 bit for an even two-way split and log2(n) for n singletons", () => {
    expect(clusterEntropyBits([2, 2])).toBeCloseTo(1, 10);
    expect(clusterEntropyBits([1, 1, 1, 1, 1, 1, 1, 1])).toBeCloseTo(3, 10);
  });

  it("keeps the normalised value within 0..1, reaching 1 only when every sample is alone", () => {
    const splits = [[1], [8], [7, 1], [4, 4], [3, 2], [2, 2, 1], [1, 1, 1], [1, 1, 1, 1, 1, 1, 1, 1], [5, 1, 1, 1]];
    for (const sizes of splits) {
      const value = normalisedEntropy(sizes);
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThanOrEqual(1);
    }
    expect(normalisedEntropy([1, 1, 1])).toBeCloseTo(1, 10);
    expect(normalisedEntropy([4, 4])).toBeLessThan(1);
  });

  it("is 0 for no samples or a single sample, and ignores empty clusters", () => {
    expect(clusterEntropyBits([])).toBe(0);
    expect(normalisedEntropy([])).toBe(0);
    expect(normalisedEntropy([1])).toBe(0);
    expect(clusterEntropyBits([3, 0, 2])).toBeCloseTo(clusterEntropyBits([3, 2]), 12);
  });
});

describe("classifyConsistency", () => {
  it("needs at least two samples", () => {
    expect(classifyConsistency([])).toBe("insufficient");
    expect(classifyConsistency([1])).toBe("insufficient");
  });

  it("is consistent with one cluster", () => {
    expect(classifyConsistency([5])).toBe("consistent");
  });

  it("is mixed only when the largest cluster holds MORE than half", () => {
    expect(classifyConsistency([3, 2])).toBe("mixed");
    expect(classifyConsistency([3, 1, 1])).toBe("mixed");
    expect(classifyConsistency([2, 2])).toBe("unstable");
    expect(classifyConsistency([2, 2, 1])).toBe("unstable");
    expect(classifyConsistency([1, 1])).toBe("unstable");
  });
});

describe("assembleConsistency", () => {
  it("all the same: one cluster, entropy 0, consistent", () => {
    const result = assembleConsistency(runOf(matrixOf([0, 0, 0, 0, 0])));
    expect(result).toMatchObject({
      state: "consistent", reason: null, n: 5, k: 5, majority: 5, unknownPairs: 0, entropyBits: 0, normalised: 0,
      contradictions: 0, groupingCertain: true,
    });
    expect(result.clusters).toEqual([{ indices: [0, 1, 2, 3, 4], keys: ["k0", "k1", "k2", "k3", "k4"] }]);
  });

  it("3 + 2: mixed, about 0.971 bits, the entropy being clusterEntropyBits of the cluster sizes", () => {
    const result = assembleConsistency(runOf(matrixOf([0, 1, 0, 1, 0])));
    expect(result).toMatchObject({ state: "mixed", reason: null, n: 5, majority: 3, unknownPairs: 0 });
    expect(result.entropyBits).toBeCloseTo(0.971, 3);
    expect(result.entropyBits).toBe(clusterEntropyBits(result.clusters.map((cluster) => cluster.indices.length)));
    expect(result.normalised).toBe(normalisedEntropy([3, 2]));
    expect(result.clusters.map((cluster) => cluster.keys)).toEqual([["k0", "k2", "k4"], ["k1", "k3"]]);
  });

  it("2 + 2 + 1: unstable", () => {
    const result = assembleConsistency(runOf(matrixOf([0, 1, 0, 1, 2])));
    expect(result).toMatchObject({ state: "unstable", reason: null, majority: 2 });
    expect(result.clusters.map((cluster) => cluster.indices)).toEqual([[0, 2], [1, 3], [4]]);
  });

  it("lists the largest cluster first, ties in leader order", () => {
    const result = assembleConsistency(runOf(matrixOf([0, 1, 1, 1, 2])));
    expect(result.clusters.map((cluster) => cluster.indices)).toEqual([[1, 2, 3], [0], [4]]);
    expect(result.majority).toBe(3);
  });

  it("a missing verdict between two leaders that would change the state: inconclusive, unverified", () => {
    // 0-1 has no verdict. Read as different: [0] [1] -> unstable. Read as
    // same: one cluster -> consistent. Neither may be claimed.
    const result = assembleConsistency(runOf(withPair(matrixOf([0, 0]), 0, 1, null)));
    expect(result.state).toBe("insufficient");
    expect(result.reason).toBe("unverified");
    expect(result.unknownPairs).toBeGreaterThanOrEqual(1);
    expect(result.n).toBe(2);
  });

  it("a missing verdict that moves the largest cluster: inconclusive even though both readings are 'mixed'", () => {
    // 0,1,2 agree; 3 differs from them; 4 has no verdict against leader 0 and
    // differs from 3. Different: 3+1+1. Same: 4+1. Both mixed, majority 3 vs 4.
    let matrix = matrixOf([0, 0, 0, 1, 2]);
    matrix = withPair(matrix, 0, 4, null);
    const result = assembleConsistency(runOf(matrix));
    expect(result).toMatchObject({ state: "insufficient", reason: "unverified" });
    expect(result.unknownPairs).toBe(1);
  });

  it("a missing verdict that changes neither the state nor the largest cluster: the state stands, but the grouping is flagged as one reading of two", () => {
    // 0,1,2 agree; 3 and 4 each differ from leader 0; 3-4 has no verdict.
    // Different: 3+1+1. Same: 3+2. Mixed with a majority of 3 either way.
    const matrix = withPair(matrixOf([0, 0, 0, 1, 2]), 3, 4, null);
    const result = assembleConsistency(runOf(matrix));
    expect(result).toMatchObject({ state: "mixed", reason: null, majority: 3, unknownPairs: 1, n: 5 });
    // Clusters and entropy are the "different" reading's...
    expect(result.clusters.map((cluster) => cluster.indices)).toEqual([[0, 1, 2], [3], [4]]);
    expect(result.entropyBits).toBe(clusterEntropyBits([3, 1, 1]));
    // ...and the result says the other reading groups differently (3 + 2).
    expect(result.groupingCertain).toBe(false);
    expect(result.altClusterCount).toBe(2);
  });

  it("a missing verdict inside a group that leaves both readings with the same groups: the grouping is certain, the count is reported", () => {
    // 0,1,2 match leader 0 and 3 differs; 1-2 was never judged. Neither
    // reading of 1-2 moves anyone between groups.
    const matrix = withPair(matrixOf([0, 0, 0, 1]), 1, 2, null);
    const result = assembleConsistency(runOf(matrix));
    expect(result).toMatchObject({ state: "mixed", reason: null, majority: 3, unknownPairs: 1, contradictions: 0, groupingCertain: true });
    expect(result.altClusterCount).toBe(2);
  });

  it("one group with an unjudged pair inside it is NOT 'all agree': inconclusive, and the pair is counted", () => {
    // 1-2 has no verdict. Both match leader 0, so the leader rule never asks,
    // but "all 3 answers agree" would rest on a comparison nobody made.
    const result = assembleConsistency(runOf(withPair(matrixOf([0, 0, 0]), 1, 2, null)));
    expect(result).toMatchObject({ state: "insufficient", reason: "unverified", unknownPairs: 1, contradictions: 0 });
  });

  it("a run cancelled after the first row of comparisons does not claim that all answers agree", () => {
    // The server compares sample 0 against 1..4 first. All four came back
    // "same"; the other 6 of the 10 comparisons never ran.
    const matrix: Matrix = [0, 1, 2, 3, 4].map((a) => [0, 1, 2, 3, 4].map((b) => (a === b || a === 0 || b === 0 ? 1 : null)));
    const result = assembleConsistency(runOf(matrix));
    expect(result).toMatchObject({ state: "insufficient", reason: "unverified", unknownPairs: 6, n: 5 });
  });

  it("one group whose members the judge called different from each other: inconclusive, contradictory", () => {
    // 0 = 1 and 0 = 2, but 1 != 2. The leader rule puts all three together.
    const matrix: Matrix = [
      [1, 1, 1],
      [1, 1, 0],
      [1, 0, 1],
    ];
    expect(clusterByLeader(matrix).clusters).toEqual([[0, 1, 2]]);
    const result = assembleConsistency(runOf(matrix));
    expect(result).toMatchObject({ state: "insufficient", reason: "contradictory", contradictions: 1, unknownPairs: 0, n: 3 });
  });

  it("a contradiction inside one group of several keeps the state and is counted", () => {
    // 0,1,2 match leader 0 (1 != 2); 3 differs from everyone.
    const matrix = withPair(matrixOf([0, 0, 0, 1]), 1, 2, 0);
    const result = assembleConsistency(runOf(matrix));
    expect(result).toMatchObject({ state: "mixed", reason: null, majority: 3, contradictions: 1, unknownPairs: 0, groupingCertain: true });
  });

  it("'consistent' means every one of the n(n-1)/2 pairs was judged the same", () => {
    // Exhaustive over 4 samples: any single pair turned to "different" or to
    // "no verdict" must take the headline away from "consistent".
    for (let a = 0; a < 4; a++) {
      for (let b = a + 1; b < 4; b++) {
        for (const value of [0, null]) {
          const result = assembleConsistency(runOf(withPair(matrixOf([0, 0, 0, 0]), a, b, value)));
          expect(result.state, `pair ${a}-${b} = ${value}`).not.toBe("consistent");
        }
      }
    }
    expect(assembleConsistency(runOf(matrixOf([0, 0, 0, 0]))).state).toBe("consistent");
  });

  it("fewer than two finished samples: inconclusive, too few", () => {
    const none = assembleConsistency({ cells: [cell("k0", "ERROR"), cell("k1", "ERROR"), cell("k2", "ERROR")], similarity: null });
    expect(none).toMatchObject({ state: "insufficient", reason: "too-few", n: 0, k: 3, majority: 0, unknownPairs: 0 });
    expect(none.clusters).toEqual([]);

    const one = assembleConsistency({
      cells: [cell("k0", "ERROR"), cell("k1"), cell("k2", "PENDING")],
      similarity: { method: "JUDGE", status: "DONE", keys: ["k1"], matrix: [[1]] },
    });
    expect(one).toMatchObject({ state: "insufficient", reason: "too-few", n: 1, k: 3, majority: 1, entropyBits: 0 });
    expect(one.clusters).toEqual([{ indices: [0], keys: ["k1"] }]);
  });

  it("counts only finished samples in n, and keeps k as the number asked for", () => {
    const similarity: LabSimilarity = { method: "JUDGE", status: "DONE", keys: ["k0", "k2", "k3"], matrix: matrixOf([0, 0, 0]) };
    const result = assembleConsistency({ cells: [cell("k0"), cell("k1", "ERROR"), cell("k2"), cell("k3")], similarity });
    expect(result).toMatchObject({ state: "consistent", n: 3, k: 4, majority: 3 });
    expect(result.keys).toEqual(["k0", "k2", "k3"]);
    expect(result.clusters[0].keys).toEqual(["k0", "k2", "k3"]);
  });

  it("finished samples that were never compared are missing verdicts, not disagreements and not missing samples", () => {
    // Cancelled after the answers, before any comparison came back.
    const result = assembleConsistency({ cells: [cell("k0"), cell("k1"), cell("k2")], similarity: null });
    expect(result).toMatchObject({ state: "insufficient", reason: "unverified", n: 3, k: 3, unknownPairs: 3 });
    expect(result.matrix).toEqual([
      [1, null, null],
      [null, 1, null],
      [null, null, 1],
    ]);
  });

  it("a half-filled matrix is inconclusive when the gap could change the answer", () => {
    // PARTIAL: 0-1 judged same; every pair with sample 2 still missing.
    const similarity: LabSimilarity = {
      method: "JUDGE", status: "PARTIAL", keys: ["k0", "k1", "k2"],
      matrix: [
        [1, 1, null],
        [1, 1, null],
        [null, null, 1],
      ],
    };
    const result = assembleConsistency({ cells: [cell("k0"), cell("k1"), cell("k2")], similarity });
    expect(result).toMatchObject({ state: "insufficient", reason: "unverified", unknownPairs: 1 });
  });

  it("aligns the matrix to the finished cells by key, whatever order the server listed them in", () => {
    // Server order k2, k0, k1 with k2 the odd one out.
    const similarity: LabSimilarity = {
      method: "JUDGE", status: "DONE", keys: ["k2", "k0", "k1"],
      matrix: [
        [1, 0, 0],
        [0, 1, 1],
        [0, 1, 1],
      ],
    };
    const result = assembleConsistency({ cells: [cell("k0"), cell("k1"), cell("k2")], similarity });
    expect(result.keys).toEqual(["k0", "k1", "k2"]);
    expect(result.clusters.map((cluster) => cluster.keys)).toEqual([["k0", "k1"], ["k2"]]);
    expect(result.state).toBe("mixed");
  });
});

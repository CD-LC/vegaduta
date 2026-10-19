// Agent Lab - the arithmetic behind the Consistency check. Pure: no React, no
// network. The server only answers "do these two answers mean the same thing"
// per pair (LabSimilarity.matrix: 1 same, 0 different, null no verdict);
// grouping the samples, the entropy of the grouping and the headline state
// are all decided here.
//
// The one rule everything below protects: a comparison with NO verdict is
// never reported as a disagreement. The samples are grouped twice, once
// reading every missing verdict as "different" and once as "same"; a state is
// only reported when both readings agree on it.
//
// The second rule: "all answers agree" is only said when EVERY pair was
// judged the same. The grouping itself only consults each group's first
// member, so the pairs it never asked about are checked afterwards - a pair
// inside a group that the judge called different is a contradiction, and one
// it gave no verdict on is a missing verdict like any other.
import type { LabRun } from "./labApi";

export type ConsistencyState = "consistent" | "mixed" | "unstable" | "insufficient";
export type InsufficientReason = "too-few" | "unverified" | "contradictory" | null;

const DEFAULT_THRESHOLD = 0.5;

/** The verdict for the pair (a, b), read from either triangle of the matrix.
 * null when neither holds a finite number. */
export function pairVerdict(matrix: (number | null)[][], a: number, b: number): number | null {
  const direct = matrix[a]?.[b];
  if (typeof direct === "number" && Number.isFinite(direct)) return direct;
  const mirrored = matrix[b]?.[a];
  return typeof mirrored === "number" && Number.isFinite(mirrored) ? mirrored : null;
}

/**
 * Leader clustering. Each sample, in order, is compared with the LEADER (the
 * first member) of every existing cluster, in cluster order, and joins the
 * first one whose leader it matches; if none accepts it, it starts a cluster
 * of its own. Comparing against leaders only keeps the result deterministic
 * even when the verdicts are not transitive (a = b, b = c, a != c).
 *
 * A null verdict counts one `unknownPair` and is then read as `unknownAs`.
 */
export function clusterByLeader(
  matrix: (number | null)[][],
  opts?: { threshold?: number; unknownAs?: "different" | "same" }
): { clusters: number[][]; unknownPairs: number } {
  const threshold = opts?.threshold ?? DEFAULT_THRESHOLD;
  const unknownAs = opts?.unknownAs ?? "different";
  const clusters: number[][] = [];
  let unknownPairs = 0;
  for (let i = 0; i < matrix.length; i++) {
    let joined = false;
    for (const cluster of clusters) {
      const verdict = pairVerdict(matrix, cluster[0], i);
      let same: boolean;
      if (verdict === null) {
        unknownPairs += 1;
        same = unknownAs === "same";
      } else {
        same = verdict >= threshold;
      }
      if (same) {
        cluster.push(i);
        joined = true;
        break;
      }
    }
    if (!joined) clusters.push([i]);
  }
  return { clusters, unknownPairs };
}

/**
 * The pairs inside each cluster that the leader rule never consulted (every
 * member matched the leader; nobody asked whether the members match each
 * other). `different`: judged different, so the verdicts contradict the
 * grouping. `unknown`: no verdict.
 */
export function withinClusterPairs(
  matrix: (number | null)[][],
  clusters: number[][],
  threshold: number = DEFAULT_THRESHOLD
): { different: number; unknown: number } {
  let different = 0;
  let unknown = 0;
  for (const cluster of clusters) {
    for (let a = 0; a < cluster.length; a++) {
      for (let b = a + 1; b < cluster.length; b++) {
        const verdict = pairVerdict(matrix, cluster[a], cluster[b]);
        if (verdict === null) unknown += 1;
        else if (verdict < threshold) different += 1;
      }
    }
  }
  return { different, unknown };
}

const positive = (sizes: number[]) => sizes.filter((size) => size > 0);
const sum = (sizes: number[]) => sizes.reduce((total, size) => total + size, 0);

/** Shannon entropy of the cluster shares, in bits: -sum(p * log2 p). */
export function clusterEntropyBits(sizes: number[]): number {
  const counts = positive(sizes);
  const n = sum(counts);
  if (n <= 0) return 0;
  let bits = 0;
  for (const count of counts) {
    const p = count / n;
    bits -= p * Math.log2(p);
  }
  // One cluster computes to -0; report a plain 0.
  return bits > 0 ? bits : 0;
}

/** Entropy as a share of the most n samples can have (every sample alone):
 * H / log2(n). 0 when n <= 1. Always within 0..1. */
export function normalisedEntropy(sizes: number[]): number {
  const n = sum(positive(sizes));
  if (n <= 1) return 0;
  return Math.min(1, clusterEntropyBits(sizes) / Math.log2(n));
}

/** insufficient under 2 samples; consistent with one cluster; mixed when the
 * largest cluster holds MORE than half; otherwise unstable. */
export function classifyConsistency(sizes: number[]): ConsistencyState {
  const counts = positive(sizes);
  const n = sum(counts);
  if (n < 2) return "insufficient";
  if (counts.length === 1) return "consistent";
  return Math.max(...counts) > n / 2 ? "mixed" : "unstable";
}

export interface ConsistencyResult {
  state: ConsistencyState; reason: InsufficientReason;
  /** Largest first (ties: the one whose leader came first). `indices` point
   * into `keys`; the first entry of each is that cluster's leader. */
  clusters: { indices: number[]; keys: string[] }[];
  entropyBits: number; normalised: number; n: number; k: number; majority: number;
  /** Comparisons with no verdict that the result depends on: a sample against
   * a group's first member, or two members of the same group. */
  unknownPairs: number;
  /** Pairs inside one group that the judge called different. */
  contradictions: number;
  /** False when reading the missing verdicts as "same" would give different
   * group sizes (the headline state and the largest group still hold). The
   * clusters and entropy here are then the "different" reading's, and
   * `altClusterCount` is how many groups the "same" reading gives. */
  groupingCertain: boolean;
  altClusterCount: number;
  /** The n samples that finished (cell keys, in cell order)... */
  keys: string[];
  /** ...and the n x n verdicts the clusters were computed from, aligned to
   * `keys` (what the pairwise grid draws). */
  matrix: (number | null)[][];
}

/**
 * Turns a run into the result the panel shows. n is the number of samples
 * that finished, k the number asked for.
 *
 * The verdict matrix is re-read against the DONE cells by key rather than
 * trusted positionally: by contract `similarity.keys` IS the DONE cells in
 * cell order, in which case this is the server's matrix unchanged - but a run
 * that stopped before (or part-way through) the comparisons has finished
 * samples with no verdicts, and those are missing verdicts, not missing
 * samples.
 */
export function assembleConsistency(run: Pick<LabRun, "cells" | "similarity">): ConsistencyResult {
  const cells = run.cells ?? [];
  const k = cells.length;
  const keys = cells.filter((cell) => cell.status === "DONE").map((cell) => cell.key);
  const n = keys.length;

  const similarity = run.similarity;
  const position = new Map<string, number>();
  (similarity?.keys ?? []).forEach((key, index) => position.set(key, index));
  const serverMatrix = similarity?.matrix ?? [];
  const matrix = keys.map((rowKey, row) =>
    keys.map((colKey, col): number | null => {
      if (row === col) return 1;
      const a = position.get(rowKey);
      const b = position.get(colKey);
      if (a === undefined || b === undefined) return null;
      return pairVerdict(serverMatrix, a, b);
    })
  );

  const pess = clusterByLeader(matrix, { unknownAs: "different" });
  const ordered = pess.clusters
    .map((indices) => ({ indices, keys: indices.map((index) => keys[index]) }))
    // Array.prototype.sort is stable, so equal sizes keep leader order.
    .sort((a, b) => b.indices.length - a.indices.length);
  const sizes = ordered.map((cluster) => cluster.indices.length);
  const majority = sizes.length > 0 ? sizes[0] : 0;
  const measured = {
    clusters: ordered,
    entropyBits: clusterEntropyBits(sizes),
    normalised: normalisedEntropy(sizes),
    n, k, majority, keys, matrix,
  };

  const certain = { groupingCertain: true, altClusterCount: ordered.length };
  if (n < 2) {
    return { ...measured, ...certain, state: "insufficient", reason: "too-few", unknownPairs: 0, contradictions: 0 };
  }
  const state = classifyConsistency(sizes);
  // The pairs the leader rule never asked about.
  const inner = withinClusterPairs(matrix, pess.clusters);
  const unknownPairs = pess.unknownPairs + inner.unknown;
  const contradictions = inner.different;
  // One group, but the judge called two of its members different: the
  // verdicts do not support "all agree", and they do not support a split
  // either (every answer matched the first one).
  if (state === "consistent" && contradictions > 0) {
    return { ...measured, ...certain, state: "insufficient", reason: "contradictory", unknownPairs, contradictions };
  }
  // No verdict was missing where it mattered: the grouping is what it is.
  if (unknownPairs === 0) {
    return { ...measured, ...certain, state, reason: null, unknownPairs: 0, contradictions };
  }
  // Some were. "All agree" needs every pair judged the same, so a single
  // group with an unjudged pair inside it is not established.
  if (state === "consistent") {
    return { ...measured, ...certain, state: "insufficient", reason: "unverified", unknownPairs, contradictions };
  }
  // Otherwise group again with every missing verdict read the other way.
  const opt = clusterByLeader(matrix, { unknownAs: "same" });
  const optSizes = opt.clusters.map((cluster) => cluster.length).sort((a, b) => b - a);
  if (classifyConsistency(optSizes) === state && optSizes[0] === majority) {
    const groupingCertain = optSizes.length === sizes.length && optSizes.every((size, index) => size === sizes[index]);
    return { ...measured, state, reason: null, unknownPairs, contradictions, groupingCertain, altClusterCount: optSizes.length };
  }
  return { ...measured, ...certain, state: "insufficient", reason: "unverified", unknownPairs, contradictions };
}

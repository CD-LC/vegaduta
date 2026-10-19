// Agent Lab - sample answers for the dev-only preview fetch mock
// (app/ui-kit/dashboard-preview/mock-api.ts spreads these into its
// patternRoutes). Every lab route core serves is answered here, so the lab
// can be looked at with no backend: a run "finishes" the moment it is
// started, with answers, scores and a similarity matrix derived from a hash
// of each cell key - the same request always renders the same result.
//
// Types only: this file is reached from the mock, never from the lab's own
// runtime, and must not drag a component or the API client along with it.
import type { LabCellRequest, LabCellResult, LabRun, LabRunRequest, LabSimilarity } from "./labApi";

type LabMockHandler = (m: RegExpMatchArray, url: URL, init?: RequestInit) => { status?: number; body: unknown } | null;

const REWIND_PROBE_KEEP = 2147483647;
/** Stored entries the sample session "holds": labFixtures' two exchanges. */
const SAMPLE_SESSION_ENTRIES = 4;

const SAMPLE_ANSWERS = [
  "Your order has shipped and should arrive within three working days.",
  "It is on the way. Delivery is expected in about three working days.",
  "I could not find that order. Could you share the order number again?",
  "Refunds reach the original payment method in five to seven working days.",
];

const runs = new Map<string, LabRun>();
let runSeq = 1;

/** FNV-1a, 32 bit - small, stable, and good enough to spread sample data. */
function hashOf(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

function methodOf(init?: RequestInit): string {
  return (init?.method || "GET").toUpperCase();
}

function bodyOf(init?: RequestInit): Record<string, unknown> {
  try {
    return typeof init?.body === "string" ? (JSON.parse(init.body) as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

const notFound = () => ({ status: 404, body: { error: "This lab run is not available (sample)." } });

function buildRun(agentId: string, request: Partial<LabRunRequest>): LabRun {
  const requested: LabCellRequest[] = Array.isArray(request.cells) ? request.cells : [];
  const judged = request.scoring === "JUDGE";
  const cells: LabCellResult[] = requested.map((cell): LabCellResult => {
    const h = hashOf(String(cell.key));
    return {
      key: String(cell.key),
      status: "DONE",
      answer: SAMPLE_ANSWERS[h % SAMPLE_ANSWERS.length],
      answerTruncated: false,
      // 0.40 .. 1.00 in steps of 0.01.
      score: judged ? Math.round((0.4 + (h % 61) / 100) * 100) / 100 : null,
      scoreStatus: judged ? "SCORED" : "NONE",
      scoreFeedback: judged ? "Addresses the question directly (sample judge note)." : null,
      errorMessage: null,
      ms: 600 + (h % 1800),
    };
  });
  let similarity: LabSimilarity | null = null;
  if (request.similarity === "JUDGE") {
    const keys = cells.map((cell) => cell.key);
    // Two groups by key hash, so the verdicts are transitive like real ones.
    const group = keys.map((key) => hashOf(key) % 2);
    similarity = {
      method: "JUDGE",
      status: "DONE",
      keys,
      matrix: keys.map((_, a) => keys.map((__, b) => (group[a] === group[b] ? 1 : 0))),
    };
  }
  const n = cells.length;
  const calls = n * (judged ? 2 : 1) + (similarity ? (n * (n - 1)) / 2 : 0);
  const at = Date.now();
  return {
    runId: `labrun_${runSeq++}`,
    agentId,
    kind: request.kind === "CONSISTENCY" ? "CONSISTENCY" : "KNOCKOUT",
    status: "SUCCEEDED",
    plannedCalls: calls,
    completedCalls: calls,
    cancelRequested: false,
    errorMessage: null,
    createdAtMs: at,
    updatedAtMs: at,
    finishedAtMs: at,
    cells,
    similarity,
  };
}

const capabilities: LabMockHandler = () => ({
  body: { allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60 },
});

const startRun: LabMockHandler = (m, _url, init) => {
  if (methodOf(init) !== "POST") return null;
  const run = buildRun(m[1], bodyOf(init) as Partial<LabRunRequest>);
  runs.set(run.runId, run);
  return { status: 202, body: run };
};

const getRun: LabMockHandler = (m) => {
  const run = runs.get(m[2]);
  return run && run.agentId === m[1] ? { body: run } : notFound();
};

const cancelRun: LabMockHandler = (m, _url, init) => {
  if (methodOf(init) !== "POST") return null;
  const run = runs.get(m[2]);
  return run && run.agentId === m[1] ? { status: 202, body: { runId: run.runId, cancelRequested: true } } : notFound();
};

const rewind: LabMockHandler = (m, _url, init) => {
  if (methodOf(init) !== "POST") return null;
  const asked = Number(bodyOf(init).keepTurns);
  const sessionId = decodeURIComponent(m[2]);
  // The size probe: nothing is dropped, the stored-entry count comes back.
  if (asked === REWIND_PROBE_KEEP) return { body: { sessionId, keepTurns: SAMPLE_SESSION_ENTRIES, droppedTurns: 0 } };
  const keepTurns = Number.isFinite(asked) && asked >= 0 ? asked : 0;
  return { body: { sessionId, keepTurns, droppedTurns: Math.max(0, SAMPLE_SESSION_ENTRIES - keepTurns) } };
};

export const labMockPatternRoutes: Array<
  [RegExp, (m: RegExpMatchArray, url: URL, init?: RequestInit) => { status?: number; body: unknown } | null]
> = [
  [/^\/api\/agents\/([^/]+)\/lab\/capabilities$/, capabilities],
  [/^\/api\/agents\/([^/]+)\/lab\/runs$/, startRun],
  [/^\/api\/agents\/([^/]+)\/lab\/runs\/([^/]+)\/cancel$/, cancelRun],
  [/^\/api\/agents\/([^/]+)\/lab\/runs\/([^/]+)$/, getRun],
  [/^\/api\/agents\/([^/]+)\/chat\/sessions\/([^/]+)\/rewind$/, rewind],
];

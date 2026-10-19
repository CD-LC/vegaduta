// Platform overhead per chat turn, in milliseconds.
//
// Run it against an agent whose model is the mock server in ../mock-llm (fixed, known first-token delay), so
//     platform overhead ~= http_req_waiting (time to first byte) - model first-token delay.
// It is a closed loop: each VU sends a turn, reads the whole reply, and immediately sends the next one. A VU keeps
// one conversation for TURNS_PER_SESSION turns and then starts a new one, so history grows realistically.
//
// k6's http module is not a streaming client, but the platform sends no bytes before the first token, so
// http_req_waiting (time to first byte) is the time to first token as the client sees it. The result is written
// as compact JSON to OUT (default /out/result.json) by handleSummary; tests/load/kind/bench.sh drives it.
//
// Env: BASE_URL, KEYCLOAK_URL, LOAD_TEST_USERNAME, LOAD_TEST_PASSWORD, LOAD_TEST_AGENT_ID (as the other scripts);
//      VUS (default 8), DURATION (default 60s), TURNS_PER_SESSION (default 10), OUT, LABEL (free text),
//      EXPECT_EVENTS (optional: exact SSE event count of a complete reply; fails turns whose stream was cut).
// Gate (optional; tests/load/kind/gate.sh and .github/workflows/load-nightly.yml set them): each one set adds a k6
// threshold, so k6 exits non-zero (99) when it is crossed. Unset, the script has no thresholds (bench.sh, cell.sh).
//      GATE_P95_MS (p95 of the whole turn, turn_total_ms), GATE_TTFT_P95_MS (p95 of turn_ttft_ms),
//      GATE_MAX_FAILED_RATE (turn_failed must stay at or below it, e.g. 0.01), SUMMARY_OUT (full k6 summary JSON).
import http from "k6/http";
import { check } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { getAuthToken } from "../lib/auth.js";

function requireEnv(name) {
  const val = __ENV[name];
  if (!val) throw new Error(`[turn-overhead] Required environment variable ${name} is not set.`);
  return val;
}

const VUS = parseInt(__ENV.VUS || "8", 10);
const DURATION = __ENV.DURATION || "60s";
const TURNS_PER_SESSION = parseInt(__ENV.TURNS_PER_SESSION || "10", 10);
// Optional: the exact number of SSE data events a complete reply has (the mock model's TOKENS). A stream cut short, for
// example by a pod being replaced mid-reply, then counts as a failed turn instead of passing as "200 with some data".
const EXPECT_EVENTS = __ENV.EXPECT_EVENTS ? parseInt(__ENV.EXPECT_EVENTS, 10) : 0;

const ttft = new Trend("turn_ttft_ms", true);
const total = new Trend("turn_total_ms", true);
const failed = new Rate("turn_failed");
// Why turns failed: one counter per HTTP status we expect to see (0 = no response / connection error), plus
// "incomplete" for a 200 whose stream did not carry EXPECT_EVENTS events.
const STATUSES = [200, 401, 403, 404, 429, 500, 502, 503, 504, 0];
const byStatus = {};
STATUSES.forEach((c) => (byStatus[c] = new Counter("turn_http_" + c)));
const incomplete = new Counter("turn_incomplete");

// A number from the environment, or null when unset; a value that is set but not a positive number stops the run, so a
// typo cannot turn the gate off silently.
function gateNumber(name) {
  const raw = __ENV[name];
  if (raw === undefined || raw === "") return null;
  const n = Number(raw);
  if (!isFinite(n) || n < 0) throw new Error(`[turn-overhead] ${name}=${raw} is not a non-negative number.`);
  return n;
}
const GATE_P95_MS = gateNumber("GATE_P95_MS");
const GATE_TTFT_P95_MS = gateNumber("GATE_TTFT_P95_MS");
const GATE_MAX_FAILED_RATE = gateNumber("GATE_MAX_FAILED_RATE");

const thresholds = {};
if (GATE_P95_MS !== null) thresholds.turn_total_ms = [`p(95)<${GATE_P95_MS}`];
if (GATE_TTFT_P95_MS !== null) thresholds.turn_ttft_ms = [`p(95)<${GATE_TTFT_P95_MS}`];
if (GATE_MAX_FAILED_RATE !== null) thresholds.turn_failed = [`rate<=${GATE_MAX_FAILED_RATE}`];

export const options = {
  scenarios: {
    turns: { executor: "constant-vus", vus: VUS, duration: DURATION },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds,
};

export function setup() {
  const baseUrl = requireEnv("BASE_URL");
  requireEnv("KEYCLOAK_URL");
  const agentId = requireEnv("LOAD_TEST_AGENT_ID");
  console.log(`[turn-overhead] BASE_URL=${baseUrl} agent=${agentId} VUS=${VUS} DURATION=${DURATION}`);
  return { baseUrl, agentId, token: getAuthToken(), run: Date.now().toString(36) };
}

export default function (data) {
  const sid = `bench-${data.run}-${__VU}-${Math.floor(__ITER / TURNS_PER_SESSION)}`;
  const res = http.post(
    `${data.baseUrl}/api/agents/${data.agentId}/chat`,
    JSON.stringify({ message: `bench turn ${__ITER}`, sessionId: sid }),
    {
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.token}` },
      tags: { name: "AgentChat" },
    }
  );
  const ok = check(res, {
    "status is 200": (r) => r.status === 200,
    "has SSE data": (r) => typeof r.body === "string" && r.body.indexOf("data:") !== -1,
    "stream complete": (r) => EXPECT_EVENTS === 0 || (typeof r.body === "string" && r.body.split("data:").length - 1 === EXPECT_EVENTS),
  });
  failed.add(!ok);
  if (byStatus[res.status]) byStatus[res.status].add(1);
  if (res.status === 200 && !ok) incomplete.add(1);
  if (ok) {
    ttft.add(res.timings.waiting);
    total.add(res.timings.duration);
  }
}

const pick = (m) => {
  const v = (m && m.values) || {};
  const r = (x) => (x === undefined ? null : Math.round(x * 10) / 10);
  return { avg: r(v.avg), p50: r(v.med), p90: r(v["p(90)"]), p95: r(v["p(95)"]), p99: r(v["p(99)"]), max: r(v.max), min: r(v.min) };
};

export function handleSummary(d) {
  const m = d.metrics;
  const out = {
    label: __ENV.LABEL || "",
    vus: VUS,
    duration: DURATION,
    turns: m.turn_total_ms ? m.turn_total_ms.values.count : 0,
    turns_per_s: m.iterations ? Math.round(m.iterations.values.rate * 10) / 10 : null,
    failed_rate: m.turn_failed ? m.turn_failed.values.rate : null,
    statuses: STATUSES.reduce((o, c) => {
      const v = m["turn_http_" + c] ? m["turn_http_" + c].values.count : 0;
      if (v) o[c] = v;
      return o;
    }, {}),
    incomplete_streams: m.turn_incomplete ? m.turn_incomplete.values.count : 0,
    ttft_ms: pick(m.turn_ttft_ms),
    total_ms: pick(m.turn_total_ms),
  };
  // Only when a gate threshold is set, so the benchmark's JSON lines keep their shape.
  if (Object.keys(thresholds).length > 0) {
    out.gate = {};
    Object.keys(thresholds).forEach((name) => {
      const t = (m[name] && m[name].thresholds) || {};
      Object.keys(t).forEach((expr) => (out.gate[`${name}: ${expr}`] = t[expr].ok));
    });
    // turn_total_ms has no samples when every turn failed, so its threshold may not be evaluated; turn_failed is 1 then.
  }
  const path = __ENV.OUT || "/out/result.json";
  const files = { [path]: JSON.stringify(out, null, 2) + "\n", stdout: JSON.stringify(out) + "\n" };
  if (__ENV.SUMMARY_OUT) files[__ENV.SUMMARY_OUT] = JSON.stringify(d, null, 2) + "\n";
  return files;
}

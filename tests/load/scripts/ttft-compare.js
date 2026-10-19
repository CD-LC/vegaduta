// load-test/scripts/ttft-compare.js
//
// Latency-baseline script for the "no latency regression" gate used by every
// step of the core decomposition (see docs/ops/LATENCY-BASELINE-RUNBOOK.md).
// Runs, side by side in one k6 `scenarios` block:
//   chat  - POST /api/agents/{LOAD_TEST_AGENT_ID}/chat (SSE), recording
//           client-side time-to-first-byte (TTFB) and total turn time
//   reads - GET /api/agents (plain authenticated DB read), TTFB + total
// and emits custom Trend metrics so p50/p95/p99 appear in the k6 summary:
//   chat_ttfb_ms, chat_total_ms, read_ttfb_ms, read_total_ms
//
// WHAT "TTFT" MEANS HERE (read this): k6's http module is not a streaming
// client (see chat-load.js header). res.timings.waiting is the time until the
// FIRST response byte arrives. If the server flushes the SSE response
// headers, a comment or a keep-alive before the first model token,
// chat_ttfb_ms under-reports true time-to-first-token. It is nevertheless a
// stable, comparable before/after proxy - the runbook's interim measure until
// the server-side timer `agentic.chat.ttft` (phase 2) exists. Compare
// like-for-like runs only; never quote chat_ttfb_ms as absolute user TTFT.
//
// SAFETY:
//  * Refuses to run when BASE_URL or KEYCLOAK_URL contains PROD_DOMAIN
//    (your production apex domain, if set) unless ALLOW_PROD=1.
//  * LOAD_TEST_AGENT_ID MUST belong to a tenant on the backend's load-test-mode
//    tenant allowlist (canned instant responses, no real LLM cost) - see
//    load-test/README.md. Never point it at a real tenant's agent. If the
//    tenant is NOT allowlisted, every chat call is a real, billed LLM turn.
//  * Defaults are small: 20 chat VUs + 30 read VUs. Override at run time;
//    more than 50 per scenario also needs ALLOW_HIGH_VUS=1.
//
// ENV (all passed as -e KEY=VALUE):
//   required: BASE_URL, KEYCLOAK_URL, LOAD_TEST_USERNAME, LOAD_TEST_PASSWORD,
//             LOAD_TEST_AGENT_ID
//   optional: PROD_DOMAIN=<apex>      refuse to run against this domain
//             ALLOW_PROD=1            override the PROD_DOMAIN guard
//             CHAT_VUS (20)  READ_VUS (30)  ALLOW_HIGH_VUS=1
//             WARMUP (30s)  HOLD (3m)  RAMP_DOWN (15s)
//             THRESH_CHAT_TTFB_P50_MS / _P95_MS / _P99_MS (1000/3000/5000)
//             THRESH_CHAT_TOTAL_P95_MS (5000)
//             THRESH_READ_TTFB_P50_MS / _P95_MS / _P99_MS (250/1000/2000)
//             THRESH_READ_TOTAL_P95_MS (1500)
//             THRESH_FAIL_RATE (0.01)
//   Example (staging):
//     k6 run --summary-export=ttft-run1.json \
//       -e BASE_URL=https://api.staging.example.com -e KEYCLOAK_URL=https://auth.staging.example.com \
//       -e LOAD_TEST_USERNAME=... -e LOAD_TEST_PASSWORD=... -e LOAD_TEST_AGENT_ID=... \
//       load-test/scripts/ttft-compare.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";
import { getAuthToken } from "../lib/auth.js";

function requireEnv(name) {
  const val = __ENV[name];
  if (!val) {
    throw new Error(
      `[ttft-compare] Required environment variable ${name} is not set. Pass it with -e ${name}=...`
    );
  }
  return val;
}

function intEnv(name, dflt) {
  const raw = __ENV[name];
  if (raw === undefined || raw === "") return dflt;
  const n = parseInt(raw, 10);
  if (isNaN(n) || n < 1) {
    throw new Error(`[ttft-compare] ${name} must be a positive integer, got "${raw}"`);
  }
  return n;
}

function numEnv(name, dflt) {
  const raw = __ENV[name];
  if (raw === undefined || raw === "") return dflt;
  const n = Number(raw);
  if (isNaN(n) || n <= 0) {
    throw new Error(`[ttft-compare] ${name} must be a positive number, got "${raw}"`);
  }
  return n;
}

// SAFE DEFAULTS: 20 chat + 30 read VUs. Above 50 per scenario needs an
// explicit ALLOW_HIGH_VUS=1 so a typo cannot hammer a shared staging stack.
const MAX_VUS_WITHOUT_OVERRIDE = 50;
const CHAT_VUS = intEnv("CHAT_VUS", 20);
const READ_VUS = intEnv("READ_VUS", 30);
if (
  (CHAT_VUS > MAX_VUS_WITHOUT_OVERRIDE || READ_VUS > MAX_VUS_WITHOUT_OVERRIDE) &&
  __ENV.ALLOW_HIGH_VUS !== "1"
) {
  throw new Error(
    `[ttft-compare] CHAT_VUS/READ_VUS above ${MAX_VUS_WITHOUT_OVERRIDE} require -e ALLOW_HIGH_VUS=1`
  );
}

const WARMUP = __ENV.WARMUP || "30s";
const HOLD = __ENV.HOLD || "3m";
const RAMP_DOWN = __ENV.RAMP_DOWN || "15s";

const chatTtfb = new Trend("chat_ttfb_ms", true);
const chatTotal = new Trend("chat_total_ms", true);
const readTtfb = new Trend("read_ttfb_ms", true);
const readTotal = new Trend("read_total_ms", true);

function stages(target) {
  return [
    { duration: WARMUP, target }, // ramp 0 -> target (also warms JIT/caches)
    { duration: HOLD, target }, // measured hold
    { duration: RAMP_DOWN, target: 0 },
  ];
}

export const options = {
  scenarios: {
    chat: {
      executor: "ramping-vus",
      exec: "chat",
      startVUs: 0,
      stages: stages(CHAT_VUS),
      gracefulRampDown: "30s",
    },
    reads: {
      executor: "ramping-vus",
      exec: "reads",
      startVUs: 0,
      stages: stages(READ_VUS),
      gracefulRampDown: "10s",
    },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: {
    http_req_failed: [`rate<${numEnv("THRESH_FAIL_RATE", 0.01)}`],
    chat_ttfb_ms: [
      `p(50)<${numEnv("THRESH_CHAT_TTFB_P50_MS", 1000)}`,
      `p(95)<${numEnv("THRESH_CHAT_TTFB_P95_MS", 3000)}`,
      `p(99)<${numEnv("THRESH_CHAT_TTFB_P99_MS", 5000)}`,
    ],
    chat_total_ms: [`p(95)<${numEnv("THRESH_CHAT_TOTAL_P95_MS", 5000)}`],
    read_ttfb_ms: [
      `p(50)<${numEnv("THRESH_READ_TTFB_P50_MS", 250)}`,
      `p(95)<${numEnv("THRESH_READ_TTFB_P95_MS", 1000)}`,
      `p(99)<${numEnv("THRESH_READ_TTFB_P99_MS", 2000)}`,
    ],
    read_total_ms: [`p(95)<${numEnv("THRESH_READ_TOTAL_P95_MS", 1500)}`],
  },
};

export function setup() {
  const baseUrl = requireEnv("BASE_URL");
  const keycloakUrl = requireEnv("KEYCLOAK_URL");
  requireEnv("LOAD_TEST_USERNAME");
  requireEnv("LOAD_TEST_PASSWORD");
  const agentId = requireEnv("LOAD_TEST_AGENT_ID");

  // Production guard: BASE_URL or KEYCLOAK_URL naming the PROD_DOMAIN marker
  // (e.g. -e PROD_DOMAIN=example.com). Unset = no guard.
  const hosts = `${baseUrl} ${keycloakUrl}`.toLowerCase();
  const prodDomain = (__ENV.PROD_DOMAIN || "").toLowerCase();
  if (prodDomain && hosts.indexOf(prodDomain) !== -1 && __ENV.ALLOW_PROD !== "1") {
    throw new Error(
      `[ttft-compare] Refusing to run: BASE_URL/KEYCLOAK_URL points at ${prodDomain} (PRODUCTION). ` +
        "Run against staging, or set -e ALLOW_PROD=1 if a human explicitly approved this."
    );
  }

  console.log(
    "\n=================================================================\n" +
      `[ttft-compare] BASE_URL = ${baseUrl}\n` +
      `[ttft-compare] agentId = ${agentId}, CHAT_VUS = ${CHAT_VUS}, READ_VUS = ${READ_VUS}\n` +
      `[ttft-compare] warmup=${WARMUP} hold=${HOLD} rampDown=${RAMP_DOWN}\n` +
      "[ttft-compare] LOAD_TEST_AGENT_ID must belong to a load-test-mode allowlisted tenant.\n" +
      "[ttft-compare] Abort now (Ctrl+C) if this is not the environment you intended.\n" +
      "================================================================="
  );

  const token = getAuthToken();
  return { baseUrl, agentId, token };
}

export function chat(data) {
  const { baseUrl, agentId, token } = data;
  // Deterministic per-iteration text (k6 discourages Math.random for bodies).
  const message = `ttft-compare vu=${__VU} iter=${__ITER} seq=${__VU * 100000 + __ITER}`;

  const res = http.post(
    `${baseUrl}/api/agents/${agentId}/chat`,
    JSON.stringify({ message }),
    {
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        Authorization: `Bearer ${token}`,
      },
      tags: { name: "AgentChat" },
    }
  );

  const ok = check(res, {
    "chat status is 200": (r) => r.status === 200,
    "chat body contains an SSE data line": (r) =>
      typeof r.body === "string" && r.body.indexOf("data:") !== -1,
  });

  // Only record latency for successful turns so an error burst (fast 5xx)
  // cannot make percentiles look better; failures show in http_req_failed.
  if (ok) {
    chatTtfb.add(res.timings.waiting);
    chatTotal.add(res.timings.duration);
  }
  sleep(1);
}

export function reads(data) {
  const { baseUrl, token } = data;
  const res = http.get(`${baseUrl}/api/agents`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: "ListAgents" },
  });

  const ok = check(res, { "read status is 200": (r) => r.status === 200 });
  if (ok) {
    readTtfb.add(res.timings.waiting);
    readTotal.add(res.timings.duration);
  }
  sleep(0.5);
}

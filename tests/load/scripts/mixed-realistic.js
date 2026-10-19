// load-test/scripts/mixed-realistic.js
//
// A single k6 `scenarios` block running read-heavy.js's GET /api/agents
// traffic and chat-load.js's POST .../chat traffic side by side at roughly
// an 80% read / 20% chat VU split - the closest single script in this
// harness to "audit all functionality under load" in one run.
//
// This intentionally duplicates the two request bodies from read-heavy.js
// and chat-load.js rather than importing their default() functions, because
// each needs its own top-level exported entry point wired to a distinct
// scenario via `exec` below - keeping the request logic inline here keeps
// that wiring readable. See those two scripts for narrower, single-purpose
// runs and their fuller per-endpoint comments (the SSE/streaming
// measurement caveat, the DB-pool rationale, etc.) - both still apply here
// unchanged.
//
// SAFETY: the `chat` scenario here carries the exact same constraint as
// chat-load.js - LOAD_TEST_AGENT_ID MUST be the pre-provisioned,
// tenant-allowlist-gated load-test agent, never a real tenant's agent id.
// The `read_heavy` scenario has no such constraint (see read-heavy.js).
import http from "k6/http";
import { check, sleep } from "k6";
import { getAuthToken } from "../lib/auth.js";

function requireEnv(name) {
  const val = __ENV[name];
  if (!val) {
    throw new Error(
      `[mixed-realistic] Required environment variable ${name} is not set. Pass it with -e ${name}=...`
    );
  }
  return val;
}

// SAFE DEFAULT: 20 total VUs split ~80/20 across the two scenarios below -
// NEVER a large number. The invoker decides real scale at run time via
// -e MIXED_PEAK_VUS=<n>; this fallback only applies when it is unset.
const MIXED_PEAK_VUS = __ENV.MIXED_PEAK_VUS ? parseInt(__ENV.MIXED_PEAK_VUS, 10) : 20;
const READ_VUS = Math.max(1, Math.round(MIXED_PEAK_VUS * 0.8));
const CHAT_VUS = Math.max(1, MIXED_PEAK_VUS - READ_VUS);

export const options = {
  scenarios: {
    read_heavy: {
      executor: "ramping-vus",
      exec: "readHeavy",
      startVUs: 0,
      stages: [
        { duration: "1m", target: READ_VUS },
        { duration: "3m", target: READ_VUS },
        { duration: "30s", target: 0 },
      ],
      tags: { scenario: "read_heavy" },
    },
    chat: {
      executor: "ramping-vus",
      exec: "chat",
      startVUs: 0,
      stages: [
        { duration: "1m", target: CHAT_VUS },
        { duration: "3m", target: CHAT_VUS },
        { duration: "30s", target: 0 },
      ],
      tags: { scenario: "chat" },
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"], // <1% failed requests, across both scenarios
    // Kept at the chat-load.js bar (3000ms) globally since this file mixes
    // both endpoint types under one threshold set - a fast read and a
    // slower chat turn share the same p95 gate here. Split by the
    // `scenario` tag if a tighter read-only gate is needed, e.g.:
    //   "http_req_duration{scenario:read_heavy}": ["p(95)<500"]
    http_req_duration: ["p(95)<3000"],
  },
};

export function setup() {
  const baseUrl = requireEnv("BASE_URL");
  requireEnv("KEYCLOAK_URL");
  requireEnv("LOAD_TEST_USERNAME");
  requireEnv("LOAD_TEST_PASSWORD");
  const agentId = requireEnv("LOAD_TEST_AGENT_ID");

  console.log(
    "\n=================================================================\n" +
      `[mixed-realistic] About to run against BASE_URL = ${baseUrl}\n` +
      `[mixed-realistic] agentId = ${agentId}, MIXED_PEAK_VUS = ${MIXED_PEAK_VUS} ` +
      `(read_heavy=${READ_VUS}, chat=${CHAT_VUS})\n` +
      "[mixed-realistic] If this is NOT the environment you intended, abort now (Ctrl+C).\n" +
      "================================================================="
  );

  const token = getAuthToken();
  return { baseUrl, agentId, token };
}

// Bound to the `read_heavy` scenario via options.scenarios.read_heavy.exec.
// Mirrors read-heavy.js's default() function - see that file for the
// no-LLM-cost / DB-pool rationale.
export function readHeavy(data) {
  const { baseUrl, token } = data;

  const res = http.get(`${baseUrl}/api/agents`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: "ListAgents" },
  });

  check(res, {
    "status is 200": (r) => r.status === 200,
  });

  sleep(1);
}

// Bound to the `chat` scenario via options.scenarios.chat.exec. Mirrors
// chat-load.js's default() function - see that file for the
// tenant-allowlist safety note and the SSE/total-turn-latency measurement
// caveat, both of which apply here unchanged.
export function chat(data) {
  const { baseUrl, agentId, token } = data;

  const message = `mixed-load message vu=${__VU} iter=${__ITER} seq=${__VU * 100000 + __ITER}`;

  const res = http.post(
    `${baseUrl}/api/agents/${agentId}/chat`,
    JSON.stringify({ message }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      tags: { name: "AgentChat" },
    }
  );

  check(res, {
    "status is 200": (r) => r.status === 200,
    "body contains an SSE data line": (r) =>
      typeof r.body === "string" && r.body.indexOf("data:") !== -1,
  });

  sleep(1);
}

// Not used directly - k6's `scenarios` above dispatch every VU to either
// readHeavy() or chat() via `exec`. A default() export is not required when
// `options.scenarios` is set and every scenario has its own `exec`, but it
// is kept here (as a documented no-op) in case this file is ever run
// without an explicit --scenario flag or someone forgets the config wiring.
export default function () {}

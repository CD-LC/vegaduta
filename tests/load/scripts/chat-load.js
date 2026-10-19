// load-test/scripts/chat-load.js
//
// Drives POST /api/agents/{agentId}/chat - the LLM chat turn path
// (see web/app/lib/api.ts streamChat, ~line 1106).
//
// SAFETY (read before running): LOAD_TEST_AGENT_ID MUST be the id of a
// pre-provisioned agent belonging to a tenant that has been explicitly added
// to the backend's tenant-ID allowlist for "load-test mode" (a separate,
// in-flight, admin-controlled effort - see load-test/README.md). Only for
// that allowlisted tenant does this endpoint return instant canned
// responses instead of invoking a real LLM provider. NEVER point this
// script at a real tenant's agent id - every call is a real chat turn
// unless the backend allowlist gate is active for that exact tenant.
//
// MEASUREMENT LIMITATION (documented, not hidden): the chat endpoint
// responds with a text/event-stream (SSE) body ("data: ..." lines), but
// k6's http module is not a streaming client - http.post() blocks until the
// entire response body has been received and hands it back as one string.
// That means every latency number this script reports (http_req_duration,
// etc.) is the TOTAL turn latency - wall-clock time to the very last SSE
// chunk - and NOT per-chunk / time-to-first-byte streaming latency. A real
// user perceives the first chunk arriving well before the full turn
// completes; this script cannot see that difference. If per-chunk/TTFB
// timing is ever needed, k6's plain http module can't provide it for SSE -
// that would need a different tool or a custom k6 extension.
import http from "k6/http";
import { check, sleep } from "k6";
import { getAuthToken } from "../lib/auth.js";

function requireEnv(name) {
  const val = __ENV[name];
  if (!val) {
    throw new Error(
      `[chat-load] Required environment variable ${name} is not set. Pass it with -e ${name}=...`
    );
  }
  return val;
}

// SAFE DEFAULT: 20, NEVER a large number. The person invoking the script
// decides the real peak concurrency at run time via -e PEAK_VUS=<n> (e.g.
// -e PEAK_VUS=200) - this fallback only applies when PEAK_VUS is unset.
const PEAK_VUS = __ENV.PEAK_VUS ? parseInt(__ENV.PEAK_VUS, 10) : 20;

export const options = {
  stages: [
    { duration: "1m", target: PEAK_VUS }, // ramp 0 -> PEAK_VUS
    { duration: "3m", target: PEAK_VUS }, // hold at PEAK_VUS
    { duration: "30s", target: 0 }, // ramp back to 0
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"], // <1% failed requests
    // 3000ms, not the usual ~500ms static-page bar: this hits an LLM-backed
    // chat turn - even in load-test canned-response mode it still traverses
    // auth, the agent controller, session/memory reads, and SSE framing -
    // so 3s p95 is the realistic bar for "chat is not degraded", not a
    // static asset or a cheap read endpoint (contrast with read-heavy.js,
    // which hits a plain DB-backed read with no LLM in the path at all).
    http_req_duration: ["p(95)<3000"],
  },
};

export function setup() {
  const baseUrl = requireEnv("BASE_URL");
  requireEnv("KEYCLOAK_URL");
  requireEnv("LOAD_TEST_USERNAME");
  requireEnv("LOAD_TEST_PASSWORD");
  const agentId = requireEnv("LOAD_TEST_AGENT_ID");

  // Printed once, before any load is generated, so a human watching the
  // terminal can abort (Ctrl+C) if BASE_URL is not the environment they
  // meant to hit (e.g. production instead of staging).
  console.log(
    "\n=================================================================\n" +
      `[chat-load] About to run against BASE_URL = ${baseUrl}\n` +
      `[chat-load] agentId = ${agentId}, PEAK_VUS = ${PEAK_VUS}\n` +
      "[chat-load] If this is NOT the environment you intended, abort now (Ctrl+C).\n" +
      "================================================================="
  );

  const token = getAuthToken();
  return { baseUrl, agentId, token };
}

export default function (data) {
  const { baseUrl, agentId, token } = data;

  // Vary the message text per iteration using k6's own __VU/__ITER counters
  // (deterministic, k6-documented) rather than Math.random(), which k6's
  // docs discourage for generating load-test request bodies.
  const message = `load-test message vu=${__VU} iter=${__ITER} seq=${__VU * 100000 + __ITER}`;

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

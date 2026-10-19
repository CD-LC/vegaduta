// load-test/scripts/read-heavy.js
//
// Drives GET /api/agents - the tenant's agent list
// (see web/app/lib/api.ts:735, `listAgents: () => apiFetch(`/api/agents`)`).
//
// This is a plain authenticated read with NO LLM cost whatsoever - it lists
// the calling tenant's agents from Postgres and returns, nothing more.
// Unlike chat-load.js, this scenario is safe to run at REAL target
// concurrency even WITHOUT the tenant-allowlist load-test mode, because
// there is no LLM provider call anywhere on this path to worry about
// cost/rate-limits for. What this actually stress-tests is the DB
// connection pool / HikariCP / Postgres max_connections ceiling - the
// platform's likely real bottleneck at scale (see load-test/README.md).
//
// That said, this script still follows the same "explicit, human-chosen
// concurrency" discipline as the rest of this harness - see READ_PEAK_VUS
// below - because "safe from LLM cost" is not the same thing as "safe to
// hammer a shared staging Postgres instance without warning anyone."
import http from "k6/http";
import { check, sleep } from "k6";
import { getAuthToken } from "../lib/auth.js";

function requireEnv(name) {
  const val = __ENV[name];
  if (!val) {
    throw new Error(
      `[read-heavy] Required environment variable ${name} is not set. Pass it with -e ${name}=...`
    );
  }
  return val;
}

// Higher default than chat-load.js's PEAK_VUS (20) because this path has no
// LLM cost/rate-limit exposure - but still a modest, explicit default, not
// a "real target scale" number. Override with -e READ_PEAK_VUS=<n> once a
// real ceiling has been agreed with whoever owns the shared environment.
const READ_PEAK_VUS = __ENV.READ_PEAK_VUS ? parseInt(__ENV.READ_PEAK_VUS, 10) : 50;

export const options = {
  stages: [
    { duration: "1m", target: READ_PEAK_VUS }, // ramp 0 -> READ_PEAK_VUS
    { duration: "3m", target: READ_PEAK_VUS }, // hold at READ_PEAK_VUS
    { duration: "30s", target: 0 }, // ramp back to 0
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"], // <1% failed requests
    // Kept at the same 3000ms bar as chat-load.js/mixed-realistic.js so one
    // dashboard/CI gate applies the same number platform-wide across this
    // harness. A plain list-agents read would normally warrant a far
    // tighter bar (e.g. p(95)<500ms) - tighten this locally if you want a
    // stricter DB-connection-pool regression signal specifically for this
    // script.
    http_req_duration: ["p(95)<3000"],
  },
};

export function setup() {
  const baseUrl = requireEnv("BASE_URL");
  requireEnv("KEYCLOAK_URL");
  requireEnv("LOAD_TEST_USERNAME");
  requireEnv("LOAD_TEST_PASSWORD");

  console.log(
    "\n=================================================================\n" +
      `[read-heavy] About to run against BASE_URL = ${baseUrl}\n` +
      `[read-heavy] READ_PEAK_VUS = ${READ_PEAK_VUS}\n` +
      "[read-heavy] If this is NOT the environment you intended, abort now (Ctrl+C).\n" +
      "================================================================="
  );

  const token = getAuthToken();
  return { baseUrl, token };
}

export default function (data) {
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

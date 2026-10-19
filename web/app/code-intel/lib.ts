// Co-located fetch helper for the /code-intel page.
//
// By default this now talks to code-intel THROUGH core's /api/code-intel
// proxy (CodeIntelController), NOT to the service directly - so every
// write/execute the console initiates is filed on the platform's ONE central
// Approvals screen via HumanApprovalGateService, exactly like an agent's
// apply_code_changes/run_in_code_workspace tool call. This closes the
// governance gap the code-intel-approval-reconciliation findings flagged:
// before, this page hit the service on its own origin with the user's token,
// entirely outside core/approval.
//
// Escape hatch (flexibility, not the default): set NEXT_PUBLIC_CODE_INTEL_URL
// to talk to the service directly (e.g. a deployment that intentionally runs
// code-intel standalone with no core). Direct mode bypasses the central
// approval routing - reads/search/graph behave identically, but a
// write/execute is then governed only by code-intel's own guardrail policy.
//
// Auth: reuses the app's existing Keycloak session token (auth.getToken()),
// sent as a Bearer. In the default (core-proxy) mode core validates it via
// its normal security filter; in direct mode code-intel validates it against
// the same realm JWKS (CODE_INTEL_AUTH_MODE=keycloak). Same tenant_id claim,
// identical multi-tenancy, no separate login either way.
import { auth } from "../lib/auth";
import { getPublicEnv, publicApiBase } from "../lib/env";

const CORE_API = publicApiBase();

const LOOPBACK = /^(localhost|127\.0\.0\.1|\[::1\]|::1)$/i;

function hostOf(url: string): string | undefined {
  try {
    return new URL(url).hostname;
  } catch {
    return undefined;
  }
}

/**
 * True when `url` points at the loopback interface. A loopback URL is only
 * reachable from the machine running the browser, so it is a leftover compose
 * default whenever the console itself is served from another origin.
 */
export function isLoopbackUrl(url: string | undefined): boolean {
  if (!url) return false;
  const host = hostOf(url);
  return host !== undefined && LOOPBACK.test(host);
}

/**
 * Drops a loopback direct URL when the page origin is not loopback too (AGENTS.md
 * rule 4: never a localhost browser-facing URL). The caller then uses the core proxy.
 */
export function resolveDirectUrl(configured: string | undefined, pageHostname: string | undefined): string | undefined {
  if (!configured) return undefined;
  if (isLoopbackUrl(configured) && pageHostname !== undefined && !LOOPBACK.test(pageHostname)) return undefined;
  return configured;
}

/** Which hint /code-intel shows under a failed call (i18n key in the "codeIntel" namespace). */
export function errorHintKey(
  error: string,
  pageHref?: string,
  configured: string | undefined = getPublicEnv("NEXT_PUBLIC_CODE_INTEL_URL")
): string {
  const href = pageHref ?? (typeof window !== "undefined" ? window.location.href : undefined);
  // A loopback target seen from a non-loopback page is a leftover default, not a service to "check".
  if (isLoopbackUrl(configured) && href !== undefined && !isLoopbackUrl(href)) return "errorNotEnabledHint";
  return /not found|404/i.test(error) ? "errorOldBuildHint" : "errorCheckUrlHint";
}

const DIRECT_URL = resolveDirectUrl(
  getPublicEnv("NEXT_PUBLIC_CODE_INTEL_URL"),
  typeof window !== "undefined" ? window.location.hostname : undefined
);

// core-proxy by default; the explicit direct URL wins when set.
export const CODE_INTEL_URL = DIRECT_URL || `${CORE_API}/api/code-intel`;
export const VIA_CORE = !DIRECT_URL;

type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

export interface CodeIntelPolicy {
  mode: string;
  exec_enabled: boolean;
  apply_enabled: boolean;
  max_files_per_apply: number;
  max_bytes_per_file: number;
  max_command_timeout: number;
  protected_path_globs?: string[];
}

export interface RegisterRepoPayload {
  repo_id: string;
  source_type: string;
  git_url?: string;
  auth_env?: string;
  path?: string;
}

export interface ApplyFile {
  path: string;
  content: string;
}

async function call(method: HttpMethod, path: string, body?: unknown) {
  const token = await auth.getToken();
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${CODE_INTEL_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: text };
  }
  if (!res.ok) {
    const message = (data && (data.error || data.detail)) || `Request failed (${res.status})`;
    throw Object.assign(new Error(message), { status: res.status });
  }
  return data;
}

export const codeIntel = {
  health: () => call("GET", "/health"),

  listRepos: () => call("GET", "/repos"),
  registerRepo: (payload: RegisterRepoPayload) => call("POST", "/repos", payload),
  deleteRepo: (repoId: string) => call("DELETE", `/repos/${encodeURIComponent(repoId)}`),
  // `background: true` returns immediately ({status:"accepted"}) and runs the
  // index as a server BackgroundTask; the outcome is persisted on the repo's
  // registry entry (last_index) either way, so the UI drives completion by
  // polling repoStatus() rather than holding a long request open. Older service
  // builds ignore the flag and run synchronously — the poller handles both.
  indexRepo: (repoId: string, full = false, background = false) =>
    call("POST", `/repos/${encodeURIComponent(repoId)}/index`, { full, background }),
  repoStatus: (repoId: string) => call("GET", `/repos/${encodeURIComponent(repoId)}/status`),

  search: (repoId: string, query: string, topK = 8) =>
    call("POST", `/repos/${encodeURIComponent(repoId)}/search`, {
      query,
      top_k: topK,
    }),

  graphBuild: (repoId: string) => call("POST", `/repos/${encodeURIComponent(repoId)}/graph/build`),
  graphStats: (repoId: string) => call("GET", `/repos/${encodeURIComponent(repoId)}/graph/stats`),
  callers: (repoId: string, symbol: string) =>
    call("GET", `/repos/${encodeURIComponent(repoId)}/graph/callers/${encodeURIComponent(symbol)}`),
  callees: (repoId: string, symbol: string) =>
    call("GET", `/repos/${encodeURIComponent(repoId)}/graph/callees/${encodeURIComponent(symbol)}`),
  impact: (repoId: string, symbol: string, depth = 3) =>
    call("GET", `/repos/${encodeURIComponent(repoId)}/graph/impact/${encodeURIComponent(symbol)}?depth=${depth}`),

  // knowledge-base bridge (read-only, grounded, cross-repo)
  knowledgeQuery: (query: string, repoId?: string | null, topK = 6) =>
    call("POST", `/knowledge/query`, {
      query,
      repo_id: repoId || null,
      top_k: topK,
    }),

  // guardrails: policy, approvals, audit
  getPolicy: () => call("GET", `/policy`),
  setPolicy: (patch: Partial<CodeIntelPolicy>) => call("PUT", `/policy`, patch),
  listApprovals: () => call("GET", `/approvals`),
  resolveApproval: (approvalId: string, approved: boolean) =>
    call("POST", `/approvals/${encodeURIComponent(approvalId)}/resolve?approved=${approved ? "true" : "false"}`),
  audit: (limit = 100) => call("GET", `/audit?limit=${limit}`),

  createWorkspace: (repoId: string) => call("POST", `/repos/${encodeURIComponent(repoId)}/workspaces`),
  applyFiles: (repoId: string, wsId: string, files: ApplyFile[]) =>
    call("POST", `/repos/${encodeURIComponent(repoId)}/workspaces/${encodeURIComponent(wsId)}/apply`, { files }),
  runCode: (repoId: string, wsId: string, command: string, attempts = 1) =>
    call("POST", `/repos/${encodeURIComponent(repoId)}/workspaces/${encodeURIComponent(wsId)}/run`, {
      command,
      attempts,
    }),
  workspaceDiff: (repoId: string, wsId: string) =>
    call("GET", `/repos/${encodeURIComponent(repoId)}/workspaces/${encodeURIComponent(wsId)}/diff`),
  workspaceDelete: (repoId: string, wsId: string) =>
    call("DELETE", `/repos/${encodeURIComponent(repoId)}/workspaces/${encodeURIComponent(wsId)}`),

  // Autonomous SDLC agent: context -> workspace -> fix -> test -> rework ->
  // diff, unattended (main.py's POST /repos/{repo_id}/agent/resolve). Same
  // 403-unless-CODE_INTEL_ALLOW_EXEC gate as the manual Execute tab, plus
  // needs ANTHROPIC_API_KEY server-side for the planner.
  agentResolve: (repoId: string, goal: string, testCommand: string, maxIterations = 4) =>
    call("POST", `/repos/${encodeURIComponent(repoId)}/agent/resolve`, {
      goal,
      test_command: testCommand,
      max_iterations: maxIterations,
    }),
};

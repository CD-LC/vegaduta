// Co-located fetch helper for the /build page (the "app builder" console).
//
// DIRECT mode only for v0 — app-builder is a brand-new standalone
// microservice with no core proxy yet (unlike /code-intel, which defaults
// through core's /api/code-intel proxy and only falls back to direct via an
// explicit env override). There is nothing to fall back FROM here, so this
// client always talks straight to the service at NEXT_PUBLIC_APP_BUILDER_URL
// using the browser's own Keycloak token — same "escape hatch" pattern
// code-intel/lib.ts documents, just without the proxy-by-default half of it.
// If/when a core proxy for app-builder ships, this file is the one place
// that needs to change.
//
// Auth: reuses the app's existing Keycloak session token (auth.getToken()),
// sent as a Bearer — identical to code-intel/lib.ts.
//
// Error shape: app-builder returns {timestamp, status, error} on non-2xx,
// same as code-intel — call() below throws an Error built from `.error`
// (falling back to `.detail` then a generic message), exactly like
// code-intel/lib.ts's `call()`.
import { auth } from "../lib/auth";
import { createWarmingTracker } from "../lib/cold-start";
import { getPublicEnv } from "../lib/env";
import type {
  AppBuilderExternalEdits,
  AppBuilderSyncPullResult,
  AppBuilderSyncStatus,
  ImportRepoRequest,
} from "./syncLogic";

// app-builder runs min_replicas=0 on Azure (2026-09-26 cost cut) — the first
// call after idle can sit ~9s+ while a replica boots. Pages subscribe to
// this to show a "warming up" notice instead of looking hung.
const warming = createWarmingTracker();
export const onWarming = warming.subscribe;

// RULE 0 (CLAUDE.md): no browser-facing URL may point at localhost/127.0.0.1.
// This constant used to default to "http://localhost:8091", which — because
// NEXT_PUBLIC_* is baked into the browser bundle at BUILD time — shipped to
// production and made every real visitor on the hosted app fetch
// their OWN machine's port 8091, producing a "Failed to fetch" toast that
// named a localhost URL.
//
// There is deliberately NO fallback now. app-builder is an OPTIONAL service:
// it is not in deploy/docker-compose.yml, it has no Caddy host route, and it
// has no public hostname on the Cloudflare tunnel — it only exists behind the
// standalone app-builder/docker-compose.app-builder.yml overlay. So the
// correct behaviour when NEXT_PUBLIC_APP_BUILDER_URL is unset is "this
// deployment does not have App Builder", not "guess a URL and 502/ERR_FAILED".
// Mirrors how NEXT_PUBLIC_API_BASE_URL is supplied for core (build arg in
// web/Dockerfile, real domain injected by deploy/docker-compose.domain.yml) —
// once an operator actually deploys app-builder behind a public hostname they
// set NEXT_PUBLIC_APP_BUILDER_URL=https://builder.<your-domain> at build time
// and this whole page lights up with no code change.
export const APP_BUILDER_URL = getPublicEnv("NEXT_PUBLIC_APP_BUILDER_URL") || "";

/**
 * False when this deployment has no app-builder service wired up. Callers MUST
 * check this before invoking anything on `appBuilder` below — see page.tsx's
 * unavailable state and AppShell's nav filter.
 */
export const APP_BUILDER_CONFIGURED = APP_BUILDER_URL.length > 0;

/** Client-minted project id. Timestamp + random suffix — the bare
 * `app-<Date.now().toString(36)>` form this replaces could collide when two
 * creates landed inside the same millisecond (e.g. CreateSurface's app build
 * and the studio's APK hand-off on a fast machine). */
export function newProjectId(): string {
  return `app-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
}

// Per-call time budgets. Generation and anything that applies + checks a build
// can legitimately run for a minute or two; everything else should answer fast.
const SHORT_TIMEOUT_MS = 30_000;
const LONG_TIMEOUT_MS = 120_000;

async function call(method: string, path: string, body?: unknown, timeoutMs: number = SHORT_TIMEOUT_MS) {
  // Defence in depth: the UI gates on APP_BUILDER_CONFIGURED before it renders
  // anything that can call this, so reaching here unconfigured is a bug — fail
  // with an honest message rather than fetching a relative URL against the web
  // origin (which would 404 into the Next.js router and read as a mystery).
  if (!APP_BUILDER_CONFIGURED) {
    throw Object.assign(
      new Error("App Builder is not available on this deployment."),
      { status: 0, notConfigured: true }
    );
  }
  const settle = warming.begin();
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  try {
    const token = await auth.getToken();
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (token) headers.Authorization = `Bearer ${token}`;
    let res: Response;
    let text: string;
    try {
      res = await fetch(`${APP_BUILDER_URL}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      text = await res.text();
    } catch (e) {
      if (timedOut) {
        throw Object.assign(
          new Error(
            `The App Builder service took too long to respond (over ${Math.round(timeoutMs / 1000)}s). ` +
              "It may still be working - check again in a moment before retrying."
          ),
          { status: 0, timeout: true }
        );
      }
      throw Object.assign(
        new Error("Can't reach the App Builder service. Check your connection and try again."),
        { status: 0, network: true, cause: e }
      );
    }
    let data: any = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { error: text };
    }
    if (!res.ok) {
      const message = (data && (data.error || data.detail)) || `Request failed (${res.status})`;
      throw Object.assign(new Error(message), { status: res.status, body: data });
    }
    return data;
  } finally {
    clearTimeout(timer);
    settle();
  }
}

export interface AppBuilderTemplate {
  template_id: string;
  name: string;
  description: string;
}

export interface AppBuilderProjectSummary {
  project_id: string;
  template_id: string;
  status: string;
  created_at: string;
  // Disposable-software lifecycle (app-builder `Project`): "ephemeral"
  // projects self-archive once `expires_at` passes; archived projects stay
  // readable but refuse mutation (409). Absent on pre-lifecycle backends —
  // treat as permanent. Timestamps are epoch seconds.
  lifecycle?: "permanent" | "ephemeral";
  expires_at?: number | null;
  archived_at?: number | null;
}

export interface AppBuilderPreview {
  preview_url: string;
  started_at: string;
}

// BYO-repo pivot (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md). Once a
// tenant connects GitHub, `generate()` commits to a branch in their repo and
// opens/updates a PR instead of writing to a local Docker preview workspace.
// This is the last-known activity for a project, persisted across turns so
// the UI has something to show even before the next chat message.
export interface AppBuilderGithubActivity {
  branch: string | null;
  pr_url: string | null;
  pr_number: number | null;
  pushed_at: string | null;
  // GitHub Pages preview — set once the scaffold's pages job has somewhere
  // to publish; `pages_enabled` false means the tenant must enable Pages
  // manually (token lacked Pages:write).
  pages_url?: string | null;
  pages_enabled?: boolean;
}

export interface AppBuilderProject extends AppBuilderProjectSummary {
  // Docker-preview path (dead in the primary flow post-pivot — the contract
  // says the backend gates preview.py/ingress.py behind
  // APP_BUILDER_LOCAL_PREVIEW_ENABLED, defaulting OFF, rather than deleting
  // it). startPreview/stopPreview below still exist for that flagged-off
  // local-dev fallback; page.tsx no longer calls them from the main flow.
  preview: AppBuilderPreview | null;
  // Present once the backend ships the BYO-repo pivot; optional so today's
  // (pre-pivot) responses keep working unchanged.
  github?: AppBuilderGithubActivity | null;
}

export interface AppBuilderFileWrite {
  path: string;
  content: string;
}

export interface AppBuilderBackendProvision {
  provisioned: boolean;
  reason?: string;
  schema?: string;
  role?: string;
  database_url?: string;
}

export interface AppBuilderTable {
  name: string;
  rls: boolean;
  columns: { name: string; type: string; nullable: boolean }[];
}

export type DataOp = "select" | "insert" | "update" | "delete";

export interface AppBuilderDataTable {
  name: string;
  columns: { name: string; type: string; nullable: boolean }[];
  policy: Record<DataOp, boolean>;
}

export interface AppBuilderDataConfig {
  enabled: boolean;
  available?: boolean;
  base_url?: string;
  anon_key?: string;
  has_service_key?: boolean;
  /** Present exactly once: on enable, or when the service key was just rotated. */
  service_key?: string;
  allowed_origins?: string[];
  tables?: AppBuilderDataTable[];
  tables_error?: string;
  limits?: { max_rows: number; max_insert_rows: number; anon_requests_per_minute: number };
}

export interface AppBuilderSchemaResult {
  applied: boolean;
  reason?: string;
  tables?: AppBuilderTable[];
}

export interface AppBuilderGenerateResult {
  diff: string;
  files_changed: string[];
  success: boolean;
  // The applied file set (path + full content) — used by SandboxPreview to
  // render the result instantly in the browser without waiting for the
  // Pages deploy.
  files?: AppBuilderFileWrite[];
  // Present when the workspace check command failed — stdout+stderr the
  // caller can feed back to its model for a corrective retry (the
  // client owns the retry loop on the edge path; the server retries
  // internally only for the hosted /generate route).
  check_output?: string;
  // BYO-repo pivot: optional until the backend ships them. Absence means "no
  // GitHub activity to show for this turn" (e.g. GitHub isn't connected yet),
  // not an error — every call site must treat these as best-effort.
  branch?: string | null;
  pr_url?: string | null;
  pr_number?: number | null;
  // GitHub Pages preview URL for this project's repo — deterministic
  // (https://<owner>.github.io/<repo>/), live once the pages job deploys.
  pages_url?: string | null;
  // Which engine produced this change (hosted /generate route only —
  // app-builder forwards core's providerName/model; "PLATFORM_HOSTED" =
  // the included platform pool, a BYOK name = the tenant's own key).
  // Absent on older core builds and on the on-device generate/apply path
  // (that caller sets its own label). Never fabricated client-side.
  powered_by?: { provider?: string | null; model?: string | null } | null;
  // Writes the server refused because the model only saw excerpts of
  // those files (too large to send whole).
  skipped_files?: string[];
  // The model's own plain-English explanation of what it changed (hosted
  // route: core's new contract field; apply route: the client-side model's
  // own summary echoed back). Absent on older builds — render the file
  // list + diff as before.
  summary?: string | null;
}

/** One Server-Sent-Events line from POST /projects/{id}/generate/stream. */
export interface AppBuilderGenerateEvent {
  stage: "context" | "writing" | "files" | "checking" | "publishing" | "done" | "error";
  // "files": the exact applied set — render it in the sandbox preview
  // immediately, before the build/test check finishes.
  files?: AppBuilderFileWrite[];
  files_changed?: string[];
  // "checking"/"writing": the self-repair loop's attempt bookkeeping.
  attempt?: number;
  total?: number;
  // "done": identical to the POST /generate body.
  result?: AppBuilderGenerateResult;
  // "error": pipeline failure mid-turn.
  status?: number;
  error?: string;
}

// POST /projects/{id}/generate/context - what an edit should see of the
// existing project. `full` false = excerpts only; never overwrite those.
export interface AppBuilderEditContext {
  files: { path: string; content: string; full: boolean }[];
  project_files: string[];
}

export interface AppBuilderImage {
  name: string;
  media_type: string;
  data_base64: string;
}

export interface AppBuilderAsset {
  path: string;
  size?: number | null;
}

// Per-tenant GitHub connection (contract §1/§4). `connected_by`/`connected_at`
// are display-only audit fields; the token itself is write-only and never
// appears in this response (same posture as every credential field in this
// codebase — see the contract's "never log/return the raw token" rule).
export interface AppBuilderGithubStatus {
  connected: boolean;
  repo_full_name: string | null;
  connected_by?: string | null;
  connected_at?: string | null;
}

export interface AppBuilderGithubConnectResult {
  connected: boolean;
  repo_full_name: string;
}

export interface AppBuilderStoreConfig {
  app_name: string;
  package_id: string;
  version_name: string;
  short_description: string;
  full_description: string;
}

export interface AppBuilderStoreStatus {
  paths: string[];
  config: AppBuilderStoreConfig;
  has_store_config: boolean;
}

export const appBuilder = {
  health: () => call("GET", "/health"),

  listTemplates: (): Promise<AppBuilderTemplate[]> => call("GET", "/templates"),

  listProjects: (): Promise<AppBuilderProjectSummary[]> => call("GET", "/projects"),
  getProject: (id: string): Promise<AppBuilderProject> =>
    call("GET", `/projects/${encodeURIComponent(id)}`),
  createProject: (body: {
    project_id: string;
    template_id: string;
    prompt?: string;
    // Disposable apps: ephemeral=true makes the project self-archive after
    // ttl_hours (server default 72, max 720). extendProject/keepProject below
    // push the deadline out or make it permanent.
    ephemeral?: boolean;
    ttl_hours?: number;
  }): Promise<AppBuilderProject> =>
    call("POST", "/projects", body),
  // Register an EXISTING repo (no scaffolding; nothing is committed into it).
  importProject: (body: ImportRepoRequest): Promise<AppBuilderProject> =>
    call("POST", "/projects/import", body),
  syncStatus: (id: string): Promise<AppBuilderSyncStatus> =>
    call("GET", `/projects/${encodeURIComponent(id)}/sync/status`),
  syncPull: (id: string): Promise<AppBuilderSyncPullResult> =>
    call("POST", `/projects/${encodeURIComponent(id)}/sync/pull`, undefined, LONG_TIMEOUT_MS),
  syncDetectExternal: (id: string): Promise<AppBuilderExternalEdits> =>
    call("POST", `/projects/${encodeURIComponent(id)}/sync/detect-external`),
  deleteProject: (id: string) => call("DELETE", `/projects/${encodeURIComponent(id)}`),
  // Disposable-app lifecycle (POST /projects/{id}/archive|extend|keep).
  // archive is a soft delete - the record stays readable, mutating routes
  // 409, and nothing touches the tenant's repo or BaaS schema. keep() makes
  // an ephemeral (or archived) project permanent. extend() adds ttl_hours
  // (400 on permanent projects, 409 on archived ones). All three return the
  // updated project dict.
  archiveProject: (id: string): Promise<AppBuilderProject> =>
    call("POST", `/projects/${encodeURIComponent(id)}/archive`),
  extendProject: (id: string, ttl_hours: number): Promise<AppBuilderProject> =>
    call("POST", `/projects/${encodeURIComponent(id)}/extend`, { ttl_hours }),
  keepProject: (id: string): Promise<AppBuilderProject> =>
    call("POST", `/projects/${encodeURIComponent(id)}/keep`),

  generate: (
    id: string,
    instruction: string,
    opts?: { context?: string; images?: AppBuilderImage[] }
  ): Promise<AppBuilderGenerateResult> =>
    call("POST", `/projects/${encodeURIComponent(id)}/generate`, {
      instruction,
      context: opts?.context,
      images: opts?.images,
    }, LONG_TIMEOUT_MS),

  // The streamed variant of /generate: identical request + final result,
  // but the turn's stages arrive as SSE events while it runs — live stage
  // labels instead of a dead spinner, and the "files" event carries the
  // applied set EARLY so the preview updates before the build check ends.
  // onStage is optional/best-effort. Deployments whose app-builder predates
  // the route answer 404/405 — call sites fall back to `generate()`.
  generateStream: async (
    id: string,
    instruction: string,
    opts?: { context?: string; images?: AppBuilderImage[] },
    onStage?: (stage: AppBuilderGenerateEvent["stage"], event: AppBuilderGenerateEvent) => void
  ): Promise<AppBuilderGenerateResult> => {
    if (!APP_BUILDER_CONFIGURED) {
      throw Object.assign(
        new Error("App Builder is not available on this deployment."),
        { status: 0, notConfigured: true }
      );
    }
    const settle = warming.begin();
    const controller = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, LONG_TIMEOUT_MS);
    try {
      const token = await auth.getToken();
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      };
      if (token) headers.Authorization = `Bearer ${token}`;
      let res: Response;
      try {
        res = await fetch(
          `${APP_BUILDER_URL}/projects/${encodeURIComponent(id)}/generate/stream`,
          {
            method: "POST",
            headers,
            body: JSON.stringify({
              instruction,
              context: opts?.context,
              images: opts?.images,
            }),
            signal: controller.signal,
          }
        );
      } catch (e) {
        if (timedOut) {
          throw Object.assign(
            new Error(
              `The App Builder service took too long to respond (over ${Math.round(LONG_TIMEOUT_MS / 1000)}s). ` +
                "It may still be working - check again in a moment before retrying."
            ),
            { status: 0, timeout: true }
          );
        }
        throw Object.assign(
          new Error("Can't reach the App Builder service. Check your connection and try again."),
          { status: 0, network: true, cause: e }
        );
      }
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        let data: any = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch {
          data = { error: text };
        }
        const message = (data && (data.error || data.detail)) || `Request failed (${res.status})`;
        throw Object.assign(new Error(message), { status: res.status, body: data });
      }
      const reader = res.body?.getReader();
      if (!reader) {
        throw new Error("The builder closed the stream before replying.");
      }
      const decoder = new TextDecoder();
      let buf = "";
      let result: AppBuilderGenerateResult | null = null;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buf.indexOf("\n\n")) >= 0) {
          const chunk = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          for (const line of chunk.split("\n")) {
            if (!line.startsWith("data: ")) continue;
            let evt: AppBuilderGenerateEvent;
            try {
              evt = JSON.parse(line.slice(6)) as AppBuilderGenerateEvent;
            } catch {
              continue;
            }
            if (!evt || typeof evt.stage !== "string") continue;
            onStage?.(evt.stage, evt);
            if (evt.stage === "done") result = evt.result ?? null;
            if (evt.stage === "error") {
              throw Object.assign(new Error(evt.error || "Generate failed."), {
                status: evt.status ?? 500,
              });
            }
          }
        }
      }
      if (!result) {
        throw new Error("The builder ended the stream without a result.");
      }
      return result;
    } finally {
      clearTimeout(timer);
      settle();
    }
  },

  // The existing files an on-device edit should see (same retrieval as the
  // hosted route). Older app-builder builds 404 - callers skip the edge
  // path then rather than let a local model write files blind.
  editContext: (id: string, instruction: string): Promise<AppBuilderEditContext> =>
    call("POST", `/projects/${encodeURIComponent(id)}/generate/context`, { instruction }),

  // Edge path: the on-device model (WebLLM) produced the file set in the
  // browser — zero hosted cost. Server applies it in a code-intel
  // workspace, runs the check command, commits on success; check_output on
  // failure feeds the client-side corrective retry.
  applyEdits: (
    id: string,
    body: { instruction: string; files: AppBuilderFileWrite[]; summary?: string | null }
  ): Promise<AppBuilderGenerateResult> =>
    call("POST", `/projects/${encodeURIComponent(id)}/generate/apply`, body, LONG_TIMEOUT_MS),

  // The project's current text sources — powers SandboxPreview's instant
  // render and the Design panel's token edit.
  listFiles: (id: string): Promise<{ files: AppBuilderFileWrite[] }> =>
    call("GET", `/projects/${encodeURIComponent(id)}/files`),

  // User-uploaded material (logo, photo, Figma export, font) committed
  // under assets/ on the project branch.
  listAssets: (id: string): Promise<{ assets: AppBuilderAsset[] }> =>
    call("GET", `/projects/${encodeURIComponent(id)}/assets`),
  uploadAsset: (id: string, name: string, contentBase64: string) =>
    call("POST", `/projects/${encodeURIComponent(id)}/assets`, {
      name,
      content_base64: contentBase64,
    }),
  // Figma frame -> design context (+ rendered PNG). The token is per-request
  // and never stored server-side.
  importFigma: (
    id: string,
    url: string,
    token: string
  ): Promise<{ context: string; image?: AppBuilderImage; file_key?: string; node_id?: string; name?: string }> =>
    call("POST", `/projects/${encodeURIComponent(id)}/design/figma`, { url, token }),
  generateImage: (
    id: string,
    body: { prompt: string; kind: string; size?: string }
  ): Promise<{ path: string; bytes: number; kind: string; size: string }> =>
    call("POST", `/projects/${encodeURIComponent(id)}/assets/generate`, body, LONG_TIMEOUT_MS),
  deleteAsset: (id: string, path: string) =>
    call("DELETE", `/projects/${encodeURIComponent(id)}/assets`, { path }),

  // Store-ready mobile (capacitor-app only). Status feeds the checklist;
  // save commits fastlane metadata + capacitor.config via the apply pipeline.
  getStoreStatus: (id: string): Promise<AppBuilderStoreStatus> =>
    call("GET", `/projects/${encodeURIComponent(id)}/mobile/store-config`),
  saveStoreConfig: (id: string, body: AppBuilderStoreConfig): Promise<AppBuilderGenerateResult> =>
    call("POST", `/projects/${encodeURIComponent(id)}/mobile/store-config`, body, LONG_TIMEOUT_MS),

  // Docker-preview path — dead code in the primary flow post BYO-repo pivot.
  // Kept (not deleted) per the contract: the backend gates this behind
  // APP_BUILDER_LOCAL_PREVIEW_ENABLED (default OFF) rather than removing it,
  // so a future flagged-on local-dev fallback still has a client to call.
  // Nothing in page.tsx's primary create -> chat -> PR flow calls these.
  startPreview: (id: string): Promise<{ preview_url: string }> =>
    call("POST", `/projects/${encodeURIComponent(id)}/preview/start`),
  stopPreview: (id: string) => call("POST", `/projects/${encodeURIComponent(id)}/preview/stop`),

  // --- BYO-repo GitHub connection (contract §1/§4) ---------------------
  // Per-tenant fine-grained PAT + repo, stored server-side by app-builder.
  // Not live on any deployment yet as of 2026-07-30 — app-builder's backend
  // for this ships in parallel. Every call site must degrade to a "not
  // connected" / error state rather than assume success.
  getGithubStatus: (): Promise<AppBuilderGithubStatus> => call("GET", "/projects/github/status"),
  connectGithub: (body: { token: string; repo_full_name: string }): Promise<AppBuilderGithubConnectResult> =>
    call("POST", "/projects/github/connect", body),
  disconnectGithub: () => call("DELETE", "/projects/github/connect"),

  // Best-effort — the platform operator may not have a backend DB configured
  // for this deployment yet. Callers should treat a rejection here as a soft
  // "not available" state, not a hard error (see page.tsx's BackendPanel).
  provisionBackend: (id: string): Promise<AppBuilderBackendProvision> =>
    call("POST", `/projects/${encodeURIComponent(id)}/backend/provision`),
  // Runs migration SQL as the project's own Postgres role (isolated schema).
  applyBackendSchema: (id: string, sql: string): Promise<AppBuilderSchemaResult> =>
    call("POST", `/projects/${encodeURIComponent(id)}/backend/schema`, { sql }),
  backendTables: (id: string): Promise<{ tables: AppBuilderTable[]; reason?: string }> =>
    call("GET", `/projects/${encodeURIComponent(id)}/backend/tables`),
  // Public data API (HTTPS access to the project's own schema) - see app-builder/app/dataapi.py.
  dataConfig: (id: string): Promise<AppBuilderDataConfig> =>
    call("GET", `/projects/${encodeURIComponent(id)}/data/config`),
  dataEnable: (id: string): Promise<AppBuilderDataConfig> =>
    call("POST", `/projects/${encodeURIComponent(id)}/data/enable`),
  dataRotateKey: (id: string, kind: "anon" | "service"): Promise<AppBuilderDataConfig> =>
    call("POST", `/projects/${encodeURIComponent(id)}/data/keys/rotate`, { kind }),
  dataSetPolicies: (
    id: string,
    body: { policies: Record<string, Record<DataOp, boolean>>; allowed_origins: string[] }
  ): Promise<AppBuilderDataConfig> => call("PUT", `/projects/${encodeURIComponent(id)}/data/policies`, body),
  dataDisable: (id: string): Promise<{ enabled: false; keys_revoked: number }> =>
    call("DELETE", `/projects/${encodeURIComponent(id)}/data`),
};

import { publicApiBase } from "../../lib/env";
import { fetchWithRetry } from "../../lib/http/fetchWithRetry";
// Client for Magic Studio (/api/demo/magic/**) — the landing-page flow where
// a visitor types an outcome and the platform plans, reveals, and publishes a
// runnable workflow.
//
// Same philosophy as demoClient.ts (and deliberately colocated with it): no
// app/lib/api.ts, no Keycloak, no toast bus. Every call resolves to a typed
// result; nothing throws. The backend speaks error CODES; mapping codes to
// sentences is the rendering component's job.

const API_BASE = publicApiBase();

export type MagicErrorCode =
  | "disabled"
  | "invalid"
  | "expired"
  | "workflow_cap"
  | "already_ran"
  | "runner_cap"
  | "runs_exhausted"
  | "budget_exhausted"
  | "moderated"
  | "unavailable";

const KNOWN_CODES: MagicErrorCode[] = [
  "disabled",
  "invalid",
  "expired",
  "workflow_cap",
  "already_ran",
  "runner_cap",
  "runs_exhausted",
  "budget_exhausted",
  "moderated",
];

export type MagicTool = {
  name: string;
  description: string | null;
  free: boolean;
  runnable: boolean;
  repositoryUrl: string | null;
};

export type MagicFormField = {
  id: string;
  label: string;
  type: "text" | "textarea" | "url";
  required: boolean;
  placeholder: string | null;
};

export type MagicPlanStep = { id: string; label: string; detail: string };

export type MagicPlan = {
  title: string;
  intent: "IMAGE" | "VIDEO" | "RESEARCH" | "WRITE" | "GENERAL";
  model: { label: string; model: string; available: boolean; fallbacks?: string[] };
  agentName: string;
  agentSystemPrompt: string;
  tools: MagicTool[];
  registryUnavailable: boolean;
  steps: MagicPlanStep[];
  mermaid: string;
  formSpec: MagicFormField[];
  honestyNote: string | null;
};

export type MagicPlanResult = {
  ok: boolean;
  workflowId?: string;
  plan?: MagicPlan;
  error?: MagicErrorCode;
};

export type MagicRunResult = {
  ok: boolean;
  type?: "text" | "image";
  text?: string;
  imageUrl?: string;
  simulated?: boolean;
  error?: MagicErrorCode;
};

export type MagicPublishResult = {
  ok: boolean;
  shareId?: string;
  shareUrl?: string;
  runCap?: number;
  perIpRuns?: number;
  error?: MagicErrorCode;
};

export type MagicRunMeta = {
  title: string;
  fields: MagicFormField[];
  expiresAt: string | null;
  runsRemaining: number;
};

export type MagicRunMetaResult = {
  ok: boolean;
  meta?: MagicRunMeta;
  error?: MagicErrorCode;
};

async function readJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

function normalizeError(status: number, body: any): MagicErrorCode {
  if (status === 410) return "expired";
  const code = body && (body.error || body.code);
  if (typeof code === "string" && (KNOWN_CODES as string[]).includes(code)) {
    return code as MagicErrorCode;
  }
  return "unavailable";
}

/** POST /api/demo/magic/plan — plan + persist a workflow from one sentence. */
export async function planWorkflow(token: string, prompt: string, link?: string): Promise<MagicPlanResult> {
  try {
    const res = await fetchWithRetry(
      `${API_BASE}/api/demo/magic/plan`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Demo-Token": token },
        body: JSON.stringify({ prompt: prompt.trim(), link: link?.trim() || null }),
      },
      30000,
      1
    );
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    if (!body || !body.workflowId || !body.plan) return { ok: false, error: "unavailable" };
    return { ok: true, workflowId: body.workflowId, plan: body.plan as MagicPlan };
  } catch (error) {
    console.error("Failed to plan workflow:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/demo/magic/{id}/preview — the owner's own test run. */
export async function previewWorkflow(
  token: string,
  workflowId: string,
  values: Record<string, string>
): Promise<MagicRunResult> {
  try {
    const res = await fetchWithRetry(
      `${API_BASE}/api/demo/magic/${encodeURIComponent(workflowId)}/preview`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Demo-Token": token },
        body: JSON.stringify({ values }),
      },
      60000,
      1
    );
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    return {
      ok: true,
      type: body?.type === "image" ? "image" : "text",
      text: typeof body?.text === "string" ? body.text : undefined,
      imageUrl: typeof body?.imageUrl === "string" ? body.imageUrl : undefined,
      simulated: Boolean(body?.simulated),
    };
  } catch (error) {
    console.error("Failed to preview workflow:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/demo/magic/{id}/publish — mint (or re-read) the public /w link. */
export async function publishWorkflow(token: string, workflowId: string): Promise<MagicPublishResult> {
  try {
    const res = await fetchWithRetry(
      `${API_BASE}/api/demo/magic/${encodeURIComponent(workflowId)}/publish`,
      { method: "POST", headers: { "X-Demo-Token": token } },
      10000,
      2
    );
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    if (!body?.shareUrl) return { ok: false, error: "unavailable" };
    return {
      ok: true,
      shareId: body.shareId,
      shareUrl: body.shareUrl,
      runCap: typeof body.runCap === "number" ? body.runCap : 3,
      perIpRuns: typeof body.perIpRuns === "number" ? body.perIpRuns : 1,
    };
  } catch (error) {
    console.error("Failed to publish workflow:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** GET /api/demo/magic/run/{shareId} — the public page's world: title + form. */
export async function fetchRunMeta(shareId: string): Promise<MagicRunMetaResult> {
  try {
    const res = await fetchWithRetry(`${API_BASE}/api/demo/magic/run/${encodeURIComponent(shareId)}`, {}, 8000, 2);
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    if (!body || typeof body.title !== "string" || !Array.isArray(body.fields)) {
      return { ok: false, error: "unavailable" };
    }
    return {
      ok: true,
      meta: {
        title: body.title,
        fields: body.fields as MagicFormField[],
        expiresAt: typeof body.expiresAt === "string" ? body.expiresAt : null,
        runsRemaining: typeof body.runsRemaining === "number" ? body.runsRemaining : 0,
      },
    };
  } catch (error) {
    console.error("Failed to fetch run meta:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/demo/magic/run/{shareId} — a stranger's one run. */
export async function runWorkflow(shareId: string, values: Record<string, string>): Promise<MagicRunResult> {
  try {
    const res = await fetchWithRetry(
      `${API_BASE}/api/demo/magic/run/${encodeURIComponent(shareId)}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ values }),
      },
      60000,
      1
    );
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    return {
      ok: true,
      type: body?.type === "image" ? "image" : "text",
      text: typeof body?.text === "string" ? body.text : undefined,
      imageUrl: typeof body?.imageUrl === "string" ? body.imageUrl : undefined,
      simulated: Boolean(body?.simulated),
    };
  } catch (error) {
    console.error("Failed to run workflow:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** Absolute URL for a relative imageUrl the backend returns. */
export function absoluteImageUrl(imageUrl: string): string {
  return imageUrl.startsWith("http") ? imageUrl : `${API_BASE}${imageUrl}`;
}

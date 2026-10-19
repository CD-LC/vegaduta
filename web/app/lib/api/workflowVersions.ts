import { apiFetch, jsonBody, toJson } from "./core";

// Write side of workflow versioning - WorkflowVersionController
// (/api/workflows/{id}/versions). The read side (list + diff) lives in
// components/workflow/versionDiff.ts because the diff panel owns its own
// quiet-failure handling. Every call here throws on failure (apiFetch +
// toJson surface the server message), so callers toast or show it inline.

export type VersionLabel = "STAGING" | "PRODUCTION";

const base = (workflowId: string) => `/api/workflows/${encodeURIComponent(workflowId)}/versions`;

/** POST /versions - snapshot the current saved draft. `createdNew` is false when nothing changed. */
export async function saveWorkflowVersion(
  workflowId: string,
  changeNote?: string
): Promise<{ createdNew: boolean; version: { versionNumber: number } | null }> {
  const note = changeNote?.trim();
  const res = await apiFetch(base(workflowId), {
    method: "POST",
    ...jsonBody(note ? { changeNote: note } : {}),
  });
  const body = await toJson<any>(res);
  return { createdNew: body?.createdNew !== false, version: body?.version ?? null };
}

/** POST /versions/{n}/restore - overwrites the live definition's graph with that version. */
export async function restoreWorkflowVersion(workflowId: string, versionNumber: number): Promise<unknown> {
  const res = await apiFetch(`${base(workflowId)}/${versionNumber}/restore`, { method: "POST" });
  return toJson(res);
}

/** POST /versions/{n}/promote - point STAGING or PRODUCTION at a version (a label move, not a new snapshot). */
export async function promoteWorkflowVersion(
  workflowId: string,
  versionNumber: number,
  label: VersionLabel
): Promise<unknown> {
  const res = await apiFetch(`${base(workflowId)}/${versionNumber}/promote`, {
    method: "POST",
    ...jsonBody({ label }),
  });
  return toJson(res);
}

/** POST /versions/rollback - PRODUCTION back to the previous version (409 when there is none). */
export async function rollbackWorkflowVersion(workflowId: string): Promise<unknown> {
  const res = await apiFetch(`${base(workflowId)}/rollback`, { method: "POST" });
  return toJson(res);
}

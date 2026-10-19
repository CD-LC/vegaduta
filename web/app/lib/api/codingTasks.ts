import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { CodingTask, CodingTaskDiff, CodingTasksConfig } from "../codingTasks";

// Coding tasks (core com.agenticai.core.agents.coding, /api/coding-tasks): an agent's model edits a repository in an
// isolated sandbox until the tests pass, then a person approves or rejects the diff. Off by default on the server
// (app.coding-harness.enabled): while off every route except /capabilities answers 404, so the page reads
// getCodingTasksConfig() first and never calls the rest.
//
// Expected states are quiet (no global toast) because the page renders them inline: 404 = feature off / not the
// caller's task, 403 = plan gate, 409 = nothing to approve any more or a live task already exists, 412 = no sandbox
// set up for this workspace, 413 = archive too large, 429 = pool busy.

const enc = encodeURIComponent;

export const codingTasksApi = {
  getCodingTasksConfig: (): Promise<CodingTasksConfig> =>
    apiFetch(`/api/coding-tasks/capabilities`).then(toJsonQuietOn([404])),
  listCodingTasks: (): Promise<CodingTask[]> => apiFetch(`/api/coding-tasks`).then(toJsonQuietOn([404])),
  // A multipart body (the source may be an uploaded archive); apiFetch must not set a Content-Type for it.
  startCodingTask: (form: FormData): Promise<CodingTask> =>
    apiFetch(`/api/coding-tasks`, { method: "POST", body: form, timeoutMs: 120000 }).then(
      toJsonQuietOn([400, 403, 404, 409, 412, 413, 429])
    ),
  // Polled every couple of seconds: a poll must not raise a toast.
  getCodingTask: (id: string): Promise<CodingTask> =>
    apiFetch(`/api/coding-tasks/${enc(id)}`, { skipGetCache: true }).then(toJsonQuietOn([404, 503])),
  getCodingTaskDiff: (id: string): Promise<CodingTaskDiff> =>
    apiFetch(`/api/coding-tasks/${enc(id)}/diff`, { skipGetCache: true }).then(toJsonQuietOn([404])),
  // The decision is bound to the diff the user saw: the server answers 409 if it no longer matches.
  approveCodingTask: (id: string, diffHash: string): Promise<CodingTask> =>
    apiFetch(`/api/coding-tasks/${enc(id)}/approve`, { method: "POST", ...jsonBody({ diffHash }) }).then(
      toJsonQuietOn([404, 409])
    ),
  rejectCodingTask: (id: string): Promise<CodingTask> =>
    apiFetch(`/api/coding-tasks/${enc(id)}/reject`, { method: "POST" }).then(toJsonQuietOn([404, 409])),
  cancelCodingTask: (id: string) =>
    apiFetch(`/api/coding-tasks/${enc(id)}/cancel`, { method: "POST" }).then(toJson),
  rollbackCodingTask: (id: string): Promise<CodingTask> =>
    apiFetch(`/api/coding-tasks/${enc(id)}/rollback`, { method: "POST" }).then(toJsonQuietOn([404, 409])),
};

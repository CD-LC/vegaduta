import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import { fetchPaged } from "./pagedFetch";
import type { ApprovalRequestResponse, InputRequestResponse, AuditLogEntry } from "./types";

// Optional `quietOn` statuses skip the global error toast for callers that
// report those outcomes inline (e.g. the workflow canvas decision card).
const parse = <T = unknown>(quietOn?: number[]) => (quietOn ? toJsonQuietOn<T>(quietOn) : toJson<T>);

export const governanceApi = {
  // Human-in-the-loop approvals (Autonomy/Guardrails Strategy Stage A/C).
  // list/approve/reject are open to any authenticated tenant member server-
  // side (SecurityConfig only gates the policy PUT below to tenant-admin) -
  // matches this panel being mounted on the admin console for now anyway,
  // since that's where a human reviewing gated actions is expected to look.
  // `live`: bypass the short GET cache - for a caller that POLLS this list
  // (the inline tool-confirmation cards), where a cached copy would keep the
  // UI one refresh behind.
  listApprovals: (status: string, quietOn?: number[], live?: boolean) =>
    // No limit: the server returns the complete list unless a limit is sent, so PENDING lookups
    // (badges, pause cards, pending tool approvals) and the All/APPROVED/REJECTED views never
    // silently see a truncated list. Use countApprovals() for totals.
    apiFetch(`/api/approvals${status ? `?status=${status}` : ""}`, live ? { skipGetCache: true } : undefined).then(
      parse<ApprovalRequestResponse[]>(quietOn)
    ),
  // Real total for a status, read from the X-Total-Count header the list
  // endpoint sets. The list itself is capped (newest 200 by default), so
  // `.length` of it is NOT a count. limit=1 keeps the payload tiny.
  countApprovals: async (status: string): Promise<number> => {
    const res = await apiFetch(`/api/approvals?status=${encodeURIComponent(status)}&limit=1`, { skipGetCache: true });
    const header = res.headers?.get?.("X-Total-Count");
    if (header != null && header !== "" && Number.isFinite(Number(header))) return Number(header);
    const rows = await toJson<unknown>(res);
    return Array.isArray(rows) ? rows.length : 0;
  },
  getApproval: (id: string, quietOn?: number[], live?: boolean) =>
    apiFetch(`/api/approvals/${encodeURIComponent(id)}`, live ? { skipGetCache: true } : undefined).then(
      parse<ApprovalRequestResponse>(quietOn)
    ),
  // formValues: answers to the fields a workflow approval node asked for.
  approveRequest: (id: string, decidedBy: string, formValues?: Record<string, string>, quietOn?: number[]) =>
    apiFetch(`/api/approvals/${encodeURIComponent(id)}/approve`, {
      method: "POST",
      ...jsonBody({
        decidedBy: decidedBy || undefined,
        formValues: formValues && Object.keys(formValues).length > 0 ? formValues : undefined,
      }),
    }).then(parse<ApprovalRequestResponse>(quietOn)),
  rejectRequest: (id: string, decidedBy: string, reason: string, quietOn?: number[]) =>
    apiFetch(`/api/approvals/${encodeURIComponent(id)}/reject`, {
      method: "POST",
      ...jsonBody({ decidedBy: decidedBy || undefined, reason: reason || undefined }),
    }).then(parse<ApprovalRequestResponse>(quietOn)),
  // Gated server-side to ROLE_tenant-admin (SecurityConfig) - a non-admin
  // calling this gets a 403, surfaced through the panel's normal error box.
  setApprovalPolicy: (actionType: string, requiresApproval: boolean, quietOn?: number[]) =>
    apiFetch(`/api/approvals/policy/${encodeURIComponent(actionType)}`, {
      method: "PUT",
      ...jsonBody({ requiresApproval }),
    }).then(parse(quietOn)),

  // Workflow input requests (Tier-3) - the structured mid-run form queue
  // Human Input nodes create. GET defaults to PENDING server-side; submit
  // returns {runId, status, error} for the resumed run.
  listInputRequests: (status?: string, runId?: string, quietOn?: number[]) => {
    const params = new URLSearchParams();
    if (status) params.set("status", status);
    if (runId) params.set("runId", runId);
    const qs = params.toString();
    return apiFetch(`/api/workflows/input-requests${qs ? `?${qs}` : ""}`).then(parse<InputRequestResponse[]>(quietOn));
  },
  submitInputRequest: (id: string, values: Record<string, unknown>, quietOn?: number[]) =>
    apiFetch(`/api/workflows/input-requests/${encodeURIComponent(id)}/submit`, {
      method: "POST",
      ...jsonBody({ values }),
    }).then(parse<InputRequestResponse>(quietOn)),

  // Audit log - read-only,
  // gated server-side to ROLE_tenant-admin. from/to are ISO-8601 instant
  // strings (e.g. new Date().toISOString()), both optional.
  listAuditLog: (actionType?: string, targetType?: string, from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (actionType) params.set("actionType", actionType);
    if (targetType) params.set("targetType", targetType);
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const qs = params.toString();
    return apiFetch(`/api/audit-log${qs ? `?${qs}` : ""}`).then(toJson<AuditLogEntry[]>);
  },

  // Server-paged audit log: limit (1..1000) + offset, newest first. Returns the
  // rows plus X-Total-Count (rows matching the filters) and X-Has-More. Sending
  // limit/offset lifts the legacy silent 200-row cap.
  listAuditLogPage: (opts: {
    limit: number;
    offset: number;
    actionType?: string;
    targetType?: string;
    from?: string;
    to?: string;
  }) => {
    const params = new URLSearchParams({ limit: String(opts.limit), offset: String(opts.offset) });
    if (opts.actionType) params.set("actionType", opts.actionType);
    if (opts.targetType) params.set("targetType", opts.targetType);
    if (opts.from) params.set("from", opts.from);
    if (opts.to) params.set("to", opts.to);
    return fetchPaged<AuditLogEntry>(`/api/audit-log?${params.toString()}`, opts.limit);
  },

  // GET /api/audit-log/counts - exact per-outcome counts of ONE action type
  // over a window (default: the last 7 days; more than 90 days is a 400).
  // Same VIEW_AUDIT_LOG gate as the list - callers must check the permission
  // BEFORE calling, because a 403 here is itself written to the audit log.
  // The response echoes the resolved from/to; caption from those. Quiet on
  // 400/403/404/405 so an optional summary hides instead of raising a toast.
  getAuditLogCounts: (opts: {
    actionType: string;
    targetId?: string;
    actorId?: string;
    detailContains?: string;
    from?: string;
    to?: string;
  }) => {
    const params = new URLSearchParams({ actionType: opts.actionType });
    if (opts.targetId) params.set("targetId", opts.targetId);
    if (opts.actorId) params.set("actorId", opts.actorId);
    if (opts.detailContains) params.set("detailContains", opts.detailContains);
    if (opts.from) params.set("from", opts.from);
    if (opts.to) params.set("to", opts.to);
    return apiFetch(`/api/audit-log/counts?${params.toString()}`).then(toJsonQuietOn([400, 403, 404, 405]));
  },

  // Backs the event-type filter dropdown - the real, always-current
  // alphabet of action types instead of a hardcoded frontend list.
  listAuditLogActionTypes: () => apiFetch(`/api/audit-log/action-types`).then(toJson<string[]>),

  // Admin Issues portal (#425/#426) - deduplicated failure records
  // synchronously recorded from GlobalExceptionHandler's catch-all,
  // WorkflowExecutionEngine's FAILED runs, and LoggingToolCallback's tool
  // errors. See PlatformIssueController.
  listIssues: (status: string) => apiFetch(`/api/admin/issues${status ? `?status=${status}` : ""}`).then(toJson),
  acknowledgeIssue: (id: string) => apiFetch(`/api/admin/issues/${id}/acknowledge`, { method: "POST" }).then(toJson),
  resolveIssue: (id: string) => apiFetch(`/api/admin/issues/${id}/resolve`, { method: "POST" }).then(toJson),
  // #974 - tenant admin self-service support ticket, filed as another
  // PlatformIssue row (source TENANT_REPORTED) so it shows up in the same
  // Issues portal above rather than a parallel ticketing system.
  createIssueTicket: (category: string, title: string, message: string) =>
    apiFetch(`/api/admin/issues`, { method: "POST", ...jsonBody({ category, title, message }) }).then(
      toJson<{ id: string; [key: string]: unknown }>
    ),
};

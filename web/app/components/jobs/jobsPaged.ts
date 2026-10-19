import { fetchPaged, type PagedResult } from "../../lib/api/pagedFetch";

/**
 * GET /api/agent-jobs with limit/offset, reading X-Total-Count / X-Has-More when the server sends them
 * (docs/api/LIST-ENDPOINT-PAGING.md). On a core that predates the header `total` is undefined and the pager
 * falls back to "there may be more". Status filters repeat the query key (?status=A&status=B), as the server
 * binds a List, never a comma-joined value.
 */
export function listTenantJobsPage(opts: {
  status?: string[];
  agentId?: string | null;
  limit: number;
  offset: number;
}): Promise<PagedResult<any>> {
  const params = new URLSearchParams();
  (opts.status ?? []).forEach((s) => params.append("status", s));
  if (opts.agentId) params.set("agentId", opts.agentId);
  params.set("limit", String(opts.limit));
  params.set("offset", String(opts.offset));
  return fetchPaged<any>(`/api/agent-jobs?${params.toString()}`, opts.limit);
}

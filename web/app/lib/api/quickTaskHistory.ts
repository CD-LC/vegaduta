import { apiFetch } from "./core";
import { pagedQuery, toPaged, type PagedRows } from "./paged";

/**
 * One page of the signed-in user's Quick Task runs, newest first.
 * GET /api/quick-task/recent takes limit (1..200) and offset and answers
 * X-Total-Count / X-Has-More (docs/api/LIST-ENDPOINT-PAGING.md).
 */
export function fetchQuickTaskPage<T = any>(offset: number, limit: number): Promise<PagedRows<T>> {
  const qs = pagedQuery(new URLSearchParams(), offset, limit);
  return apiFetch(`/api/quick-task/recent?${qs}`).then((res) => toPaged<T>(res, offset, limit));
}

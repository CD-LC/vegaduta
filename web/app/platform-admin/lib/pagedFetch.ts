import { apiFetch, toJson } from "./api";

export interface PagedResult<T> {
  body: T;
  /** X-Total-Count, when the server sent it. */
  total: number | null;
  hasMore: boolean;
}

/**
 * GET a limit/offset endpoint and surface the X-Total-Count / X-Has-More
 * headers the paged list endpoints send (docs/api/LIST-ENDPOINT-PAGING.md).
 */
export async function fetchPaged<T = any>(path: string): Promise<PagedResult<T>> {
  const res = await apiFetch(path);
  const t = res.headers?.get?.("X-Total-Count");
  const h = res.headers?.get?.("X-Has-More");
  const total = t != null && t !== "" && Number.isFinite(Number(t)) ? Number(t) : null;
  const body = (await toJson(res)) as T;
  return { body, total, hasMore: h === "true" };
}

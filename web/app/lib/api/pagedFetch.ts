import { apiFetch, toJson } from "./core";

/** One server page: the rows plus what the X-Total-Count / X-Has-More headers said. */
export interface PagedResult<T> {
  rows: T[];
  /** Matching rows before paging; undefined when the server did not send it. */
  total?: number;
  hasMore: boolean;
}

/**
 * GET a list endpoint that supports limit/offset and read the paging headers
 * (see docs/api/LIST-ENDPOINT-PAGING.md). Falls back to "a full page means
 * there may be more" when the headers are missing (older core).
 */
export async function fetchPaged<T>(path: string, limit: number): Promise<PagedResult<T>> {
  const res = await apiFetch(path, { skipGetCache: true });
  const totalHeader = res.headers?.get?.("X-Total-Count");
  const moreHeader = res.headers?.get?.("X-Has-More");
  const rows = (await toJson<T[]>(res)) ?? [];
  const list = Array.isArray(rows) ? rows : [];
  const total =
    totalHeader != null && totalHeader !== "" && Number.isFinite(Number(totalHeader)) ? Number(totalHeader) : undefined;
  const hasMore = moreHeader != null ? moreHeader === "true" : list.length >= limit;
  return { rows: list, total, hasMore };
}

import { toJson } from "./core";

/**
 * One page of a server-capped list. The capped list endpoints (workflow runs,
 * contacts, KB documents) answer a plain JSON array and put the truth about
 * truncation in X-Total-Count / X-Has-More; `offset` reaches older rows.
 */
export type PagedRows<T = any> = {
  rows: T[];
  /** Real total when the server said so (it is only counted for a full or paged-in page). */
  total: number | null;
  /** True when older rows exist beyond `offset + rows.length`. */
  hasMore: boolean;
};

export function pagedQuery(base: URLSearchParams, offset: number, limit: number): string {
  base.set("limit", String(limit));
  if (offset > 0) base.set("offset", String(offset));
  return base.toString();
}

export async function toPaged<T = any>(res: Response, _offset: number, limit: number): Promise<PagedRows<T>> {
  const body = await toJson(res);
  const rows: T[] = Array.isArray(body) ? body : [];
  const totalHeader = res.headers?.get?.("X-Total-Count");
  const total =
    totalHeader != null && totalHeader !== "" && Number.isFinite(Number(totalHeader)) ? Number(totalHeader) : null;
  const moreHeader = res.headers?.get?.("X-Has-More");
  // Headers can be missing (an older core, or a cached copy rebuilt without them):
  // a full page then still means "there may be more".
  const hasMore = moreHeader != null && moreHeader !== "" ? moreHeader === "true" : rows.length >= limit;
  return { rows, total, hasMore };
}

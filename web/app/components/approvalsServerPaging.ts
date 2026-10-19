import { fetchPaged, type PagedResult } from "../lib/api/pagedFetch";
import type { ApprovalRequestResponse } from "../lib/api/types";

/** Builds the paged approvals list URL (limit/offset; the server answers X-Total-Count). */
export function approvalsPageUrl(status: string, limit: number, offset: number): string {
  const q = new URLSearchParams();
  if (status) q.set("status", status);
  q.set("limit", String(limit));
  q.set("offset", String(offset));
  return `/api/approvals?${q.toString()}`;
}

/** One server page of approvals; `total` comes from X-Total-Count (undefined on an older core). */
export function listApprovalsPage(
  status: string,
  page: number,
  pageSize: number
): Promise<PagedResult<ApprovalRequestResponse>> {
  return fetchPaged<ApprovalRequestResponse>(approvalsPageUrl(status, pageSize, (Math.max(1, page) - 1) * pageSize), pageSize);
}

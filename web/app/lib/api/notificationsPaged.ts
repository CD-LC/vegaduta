import { fetchPaged, type PagedResult } from "./pagedFetch";
import type { NotificationResponse } from "./types";

/** GET /api/notifications with limit/offset; X-Total-Count counts rows matching `unreadOnly`. */
export function listNotificationsPage(opts: {
  unreadOnly: boolean;
  limit: number;
  offset: number;
}): Promise<PagedResult<NotificationResponse>> {
  const qs = new URLSearchParams({
    unreadOnly: String(opts.unreadOnly),
    limit: String(opts.limit),
    offset: String(opts.offset),
  });
  return fetchPaged<NotificationResponse>(`/api/notifications?${qs.toString()}`, opts.limit);
}

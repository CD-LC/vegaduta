import { apiFetch, toJson, toJsonQuietOn } from "./core";
import type { AmbientDigest, NotificationResponse } from "./types";

export const notificationsApi = {
  // ---- Notification bell (AppShell header) -------------------------
  // GET /api/notifications - any authenticated tenant member (falls under
  // SecurityConfig's default anyRequest().authenticated(), no dedicated
  // admin rule). unreadOnly defaults false; the bell's popover always wants
  // the recent mixed list, not just unread.
  getNotifications: (unreadOnly = false, limit = 20): Promise<NotificationResponse[]> =>
    apiFetch(`/api/notifications?unreadOnly=${unreadOnly}&limit=${limit}`).then(toJson<NotificationResponse[]>),
  // Plain number (long), not wrapped in an object - see the audit context's
  // documented response shape. This is the endpoint NotificationController's
  // own source comment calls out as "backing a cheap, frequently-polled
  // unread-count badge" - the direct evidence for polling this on an interval
  // rather than standing up a new SSE surface for it.
  getUnreadNotificationCount: (): Promise<number> => apiFetch(`/api/notifications/unread-count`).then(toJson<number>),
  markNotificationRead: (id: string): Promise<void> =>
    apiFetch(`/api/notifications/${id}/read`, { method: "POST" }).then(toJson<void>),
  markAllNotificationsRead: (): Promise<void> => apiFetch(`/api/notifications/read-all`, { method: "POST" }).then(toJson<void>),

  // GET /api/ambient/digest - the "while you were away" card's one call.
  // sinceHours bounded server-side to [1, 336].
  getAmbientDigest: (sinceHours = 24): Promise<AmbientDigest | null> =>
    apiFetch(`/api/ambient/digest?sinceHours=${sinceHours}`).then(toJsonQuietOn<AmbientDigest | null>([404])),
};

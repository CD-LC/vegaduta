"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { AnimatePresence, motion } from "motion/react";
import { Bell, CheckCheck, Info, AlertTriangle, AlertCircle, type LucideIcon } from "lucide-react";
import { api, API_BASE } from "../lib/api";
import { timeAgo } from "./sections/common";
import { getPublicEnv } from "../lib/env";
import { firstPartyOrigins, resolveSafeLink } from "../lib/safeLink";

// Header notification bell (workflow: "notification bells + diagnostic
// explainer" - this piece is just the bell). Reuses the SAME tenant-scoped
// endpoints web/'s console calls (NotificationController - core/audit,
// V176__notification.sql): GET /api/notifications, GET
// /api/notifications/unread-count, POST /api/notifications/{id}/read, POST
// /api/notifications/read-all. A platform admin is still a real signed-in
// user, so this shows whatever's addressed to them or broadcast to their
// tenant - same UI pattern as the tenant console for consistency, even
// though most platform admins won't see much traffic here day to day.
//
// Mounted once in AdminShell's header, next to the theme toggle - the
// ten-section nav rail is deliberately fixed-size (see AdminShell's own
// header comment), so this is a header entry point, not a new NAV_ITEMS row.

const SEVERITY_ICON: Record<string, LucideIcon> = { INFO: Info, WARNING: AlertTriangle, ERROR: AlertCircle };
const SEVERITY_CLS: Record<string, string> = {
  INFO: "text-[var(--info)]",
  WARNING: "text-[var(--warning)]",
  ERROR: "text-destructive",
};

const CATEGORY_LABEL: Record<string, string> = {
  SYSTEM: "System",
  TOOL_FAILURE: "Tool failure",
  BILLING: "Billing",
  SAFETY: "Safety",
  WORKFLOW: "Workflow",
};

// /api/notifications row (NotificationController - core/audit).
type AdminNotification = {
  id: string;
  severity: string;
  category: string;
  title: string;
  body?: string | null;
  link?: string | null;
  read?: boolean;
  createdAt?: string;
};

function countLabel(n: number) {
  if (!n || n < 1) return null;
  return n > 9 ? "9+" : String(n);
}

// Route ownership boundary (2026-09-27 audit): notification.link values are
// minted server-side by core's NotificationService for the TENANT console -
// every non-null link it emits today is a web/ route ("/agents/{id}",
// "/admin/inbox"), not a platform-admin route. router.push()ing those here lands
// on a 404, which is exactly the "unmapped link" class of bug this fixes:
// same-origin relative paths that ARE this section's pages route internally;
// everything else is handed to the tenant console in a new tab.
//
// These prefixes stay UNPREFIXED ("/tenants", not "/platform-admin/tenants"):
// they match the paths core mints for the admin console. When one matches,
// the click handler below pushes "/platform-admin" + path so it lands inside
// this section.
const INTERNAL_ROUTE_PREFIXES = [
  "/tenants",
  "/demo-sandbox",
  "/academy-learners",
  "/llm-providers",
  "/edge-models",
  "/plugins",
  "/tool-credentials",
  "/mcp-catalog",
  "/mcp-review",
  "/budgets",
  "/spend",
  "/safety",
  "/templates",
  "/abuse",
  "/fraud",
  "/audit",
  "/settings",
  "/diagnostics",
  "/funnel",
  "/newsletter",
];

function isInternalAdminRoute(link: string) {
  if (link === "/") return true;
  return INTERNAL_ROUTE_PREFIXES.some((p) => link === p || link.startsWith(`${p}/`));
}

// A matched admin path ("/tenants/x") maps into this section's real route
// space ("/platform-admin/tenants/x"); "/" is the section overview.
function toPlatformAdminPath(path: string) {
  return path === "/" ? "/platform-admin" : `/platform-admin${path}`;
}

// Public origin of the tenant console (web/), where non-admin notification
// links resolve. NEXT_PUBLIC_APP_URL wins when set; otherwise derive it from
// the configured API origin (the platform's api.X <-> app.X hostname
// convention) and fall back to the web app's dev port when the API base is
// a localhost dev default.
function tenantConsoleBase() {
  const configured = getPublicEnv("NEXT_PUBLIC_APP_URL");
  if (configured) return configured;
  try {
    const apiUrl = new URL(API_BASE);
    if (apiUrl.hostname.startsWith("api.")) {
      apiUrl.hostname = `app.${apiUrl.hostname.slice(4)}`;
      return apiUrl.origin;
    }
    if (apiUrl.hostname === "localhost" || apiUrl.hostname === "127.0.0.1") {
      return "http://localhost:3000";
    }
  } catch {
    // fall through
  }
  return null;
}

export default function NotificationBell() {
  const router = useRouter();
  const wrapRef = useRef(null);
  const [open, setOpen] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [items, setItems] = useState<AdminNotification[]>([]);
  const [loading, setLoading] = useState(false);
  const [marking, setMarking] = useState(false);

  function refreshUnreadCount() {
    api
      .getUnreadNotificationCount()
      .then((n) => setUnreadCount(Number(n) || 0))
      .catch(() => {
        // Silent - a failed poll shouldn't toast on top of whatever else is
        // already surfacing backend-unreachable state elsewhere in the shell.
      });
  }

  // Poll every 30s regardless of open/closed, same cadence as other
  // lightweight admin polls in this app (see AdminShell's own platform
  // health check). Stops on unmount.
  useEffect(() => {
    refreshUnreadCount();
    const id = setInterval(refreshUnreadCount, 30000);
    return () => clearInterval(id);
  }, []);

  // Click-outside + Escape to close, same posture as BrandIdentityModal's
  // own key handling.
  useEffect(() => {
    if (!open) return undefined;
    function onPointerDown(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onPointerDown);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function toggleOpen() {
    setOpen((wasOpen) => {
      const willOpen = !wasOpen;
      if (willOpen) {
        setLoading(true);
        api
          .getNotifications(false, 50)
          .then((rows) => setItems(Array.isArray(rows) ? rows : []))
          .catch(() => setItems([]))
          .finally(() => setLoading(false));
      }
      return willOpen;
    });
  }

  function markRead(notification: AdminNotification) {
    if (notification.read) return;
    setItems((prev) => prev.map((n) => (n.id === notification.id ? { ...n, read: true } : n)));
    setUnreadCount((c) => Math.max(0, c - 1));
    api.markNotificationRead(notification.id).catch(() => {
      // Best-effort - a failed mark-as-read just leaves it unread on the
      // next poll/open rather than blocking the UI.
    });
  }

  function markAllRead() {
    if (marking || unreadCount < 1) return;
    setMarking(true);
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
    setUnreadCount(0);
    api
      .markAllNotificationsRead()
      .catch(() => {})
      .finally(() => setMarking(false));
  }

  function onRowClick(notification: AdminNotification) {
    markRead(notification);
    const link = notification.link;
    if (!link) return;
    setOpen(false);
    // FE-006: the link is server data - validate before navigating. Only
    // single-slash paths, same-origin URLs, and https: first-party origins
    // survive; javascript:/data:/foreign/protocol-relative links are ignored.
    const base = tenantConsoleBase();
    const target = resolveSafeLink(link, { trustedOrigins: firstPartyOrigins([base]) });
    if (!target) return;
    if (target.kind === "external") {
      window.open(target.href, "_blank", "noopener,noreferrer");
    } else if (isInternalAdminRoute(target.path)) {
      router.push(toPlatformAdminPath(target.path));
    } else if (base) {
      // A tenant-console path (see INTERNAL_ROUTE_PREFIXES' comment) - route
      // it to the web/ app in a new tab rather than 404ing this app's router.
      window.open(`${base}${target.path}`, "_blank", "noopener,noreferrer");
    }
  }

  const badge = countLabel(unreadCount);

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={toggleOpen}
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : "Notifications"}
        aria-expanded={open}
        className="relative flex size-9 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
      >
        <Bell size={17} />
        {badge && (
          <span className="absolute right-1 top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-destructive px-1 text-xs font-semibold leading-none text-destructive-foreground">
            {badge}
          </span>
        )}
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -6, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.98 }}
            transition={{ duration: 0.15, ease: [0.16, 1, 0.3, 1] }}
            // Below sm, this is fixed + viewport-relative (inset-x-3) instead
            // of anchored to the bell at a fixed 320px width - right-0
            // relative to the bell pushed its left edge off-screen on
            // narrower phones. sm and up keeps the original bell-anchored
            // popover.
            className="fixed inset-x-3 top-16 z-50 overflow-hidden rounded-lg border border-border bg-card shadow-lg sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-2 sm:w-80 md:w-96"
            role="region"
            aria-label="Notifications"
          >
            <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
              <span className="text-sm font-semibold text-foreground">Notifications</span>
              <button
                type="button"
                onClick={markAllRead}
                disabled={marking || unreadCount < 1}
                className="flex items-center gap-1 rounded border-0 bg-transparent p-1 text-[11px] font-medium text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
              >
                <CheckCheck size={12} />
                Mark all read
              </button>
            </div>

            <div className="max-h-96 overflow-y-auto">
              {loading && <div className="px-3.5 py-8 text-center text-xs text-muted-foreground">Loading…</div>}

              {!loading && items.length === 0 && (
                <div className="px-3.5 py-8 text-center text-xs text-muted-foreground">You&apos;re all caught up.</div>
              )}

              {!loading &&
                items.map((n) => {
                  const Icon = SEVERITY_ICON[n.severity] || Info;
                  const sevCls = SEVERITY_CLS[n.severity] || "text-muted-foreground";
                  return (
                    <button
                      key={n.id}
                      type="button"
                      onClick={() => onRowClick(n)}
                      className={`flex w-full items-start gap-2.5 border-b border-border/60 px-3.5 py-2.5 text-left transition-colors last:border-b-0 hover:bg-accent ${
                        n.read ? "" : "bg-primary/[0.04]"
                      }`}
                    >
                      <Icon
                        size={15}
                        className={`mt-0.5 shrink-0 ${sevCls}`}
                        aria-label={`${(n.severity || "info").toLowerCase()} severity`}
                      />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5">
                          <span
                            className={`truncate text-[13px] leading-snug ${n.read ? "text-foreground/80" : "font-semibold text-foreground"}`}
                          >
                            {n.title}
                          </span>
                          {!n.read && <span className="size-1.5 shrink-0 rounded-full bg-primary" aria-hidden="true" />}
                          {!n.read && <span className="sr-only">Unread</span>}
                        </div>
                        {n.body && (
                          <div className="mt-0.5 line-clamp-2 text-xs leading-snug text-muted-foreground">{n.body}</div>
                        )}
                        <div className="mt-1 flex items-center gap-1.5 text-xs uppercase tracking-wide text-muted-foreground">
                          <span>{CATEGORY_LABEL[n.category] || n.category}</span>
                          <span aria-hidden="true">·</span>
                          <span>{timeAgo(n.createdAt)}</span>
                        </div>
                      </div>
                    </button>
                  );
                })}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

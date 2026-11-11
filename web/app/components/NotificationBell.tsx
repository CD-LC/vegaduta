"use client";

// Tenant-facing notification bell (AppShell topbar, mounted right after
// EdgeSetupChip - see AppShell.tsx). Backed by the already-built/compiled
// NotificationController (core/audit) - see NotificationResponse in
// app/lib/api.ts for the wire shape.
//
// Transport is polling, not SSE/WebSocket - see getUnreadNotificationCount's
// call site below. NotificationController's own source comment documents
// GET /api/notifications/unread-count as backing "a cheap, frequently-polled
// unread-count badge on the bell icon" - i.e. the backend was designed for
// this from day one. The full recent-list fetch is NOT interval-polled: it
// only runs once when the popover opens (and once more after a mark-read/
// mark-all-read action), so the interval-polled badge count and the
// on-demand list never need to be reconciled against each other mid-session.
//
// Popover: this app's public/console surfaces standardize on Radix/ShadCN
// (Tooltip, AlertDialog already ported - see ui/tooltip.tsx, ui/alert-
// dialog.tsx) - ui/popover.tsx is the same port for a click-triggered panel,
// first used here.
//
// Severity dot reuses the shared `.state-dot`/`.state-pill` CSS primitives
// (globals.css, design contract §3/§4) - the exact same primitives AppShell's
// own system-health pill renders a few lines above this component's mount
// point - rather than inventing a second colour system. There is no
// dedicated "info" state hue, so INFO reuses --state-success (a calm, non-
// alarming colour) instead of adding a new one.
//
// Chrome strings come from the auditNotifications i18n namespace; the notification
// CONTENT (title/body) comes from the backend untranslated. The full history,
// with an unread filter and paging, lives on /notifications ("View all").

import { timeAgo } from "../lib/format";
import { useCallback, useContext, useEffect, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Bell, CheckCheck } from "lucide-react";
import { cn } from "../lib/utils";
import { visibleInterval } from "../lib/visibleInterval";
import { AuthContext } from "./AuthProvider";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { useTranslations } from "../lib/i18n";
import { getProblems, markAllProblemsRead, markProblemRead, subscribeProblems } from "../lib/toastBus";
import { api, type NotificationResponse, type NotificationSeverity } from "../lib/api";

// globals.css styles every bare <button> with padding/border/background - the
// same neutralization every icon button in this app carries (see the "Global
// button rule clips icon buttons" gotcha this component's own header refers to).
const bareButtonClass = "flex items-center rounded border-0 bg-transparent p-0 shadow-none transition-colors";

const UNREAD_POLL_MS = 30_000;

// Maps straight onto the design contract's --state-* hue family (globals.css)
// rather than inventing new colours - ERROR/WARNING have a direct match.
// INFO used to reuse --state-success, which made an informational row and a
// success row read identically at a glance (a11y/color audit follow-up); it
// now uses the live theme accent instead, which is always distinct from the
// fixed success/warning/error hues regardless of which accent a tenant picked.
const SEVERITY_STATE_VAR: Record<NotificationSeverity, string> = {
  ERROR: "var(--state-error)",
  WARNING: "var(--state-warning)",
  INFO: "var(--accent-500)",
};

const SEVERITY_KEY: Record<NotificationSeverity, string> = {
  ERROR: "severityError",
  WARNING: "severityWarning",
  INFO: "severityInfo",
};

// First line only (spec: "title, first line of body") - a notification body
// can carry multiple lines, and the row is a compact single-glance summary,
// not a full rendering of the body.
function firstLine(body: string | null | undefined): string {
  if (!body) return "";
  return body.split(/\r?\n/)[0] ?? "";
}

export default function NotificationBell() {
  const router = useRouter();
  const t = useTranslations("auditNotifications");
  // Is there actually a signed-in person behind this bell? Read via
  // useContext(AuthContext) directly rather than the useAuth() hook, for the
  // same reason AppChromeProvider does (see its header): useAuth() hard-throws
  // without a provider, useContext resolves to the `null` default.
  //
  // This gate exists because AppShell — which mounts this component — is NOT
  // only ever rendered for an authenticated session. AuthProvider's
  // ANONYMOUS_RENDER_ROUTES ("/help", "/features") render their own children
  // under a deliberately anonymous AuthContext (username: null) so that a
  // logged-out visitor or crawler gets the real page content instead of the
  // marketing homepage — and that content sits behind AppShell. So on those two
  // routes the whole console shell, this bell included, mounts for someone with
  // no session at all. Production E2E (2026-08-16, logged out) caught the
  // result: GET /api/notifications/unread-count firing and 401ing on /help,
  // re-firing every 30s, and — because /help is not in PUBLIC_ROUTES and can't
  // be — driving the token layer as far as showing a "we couldn't verify your
  // session" prompt to a visitor who had never signed in.
  const username = useContext(AuthContext)?.username ?? null;
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<NotificationResponse[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listFailed, setListFailed] = useState(false);
  const [serverUnread, setUnreadCount] = useState(0);
  // Failed calls and warnings from this browser session (see toastBus): shown here, not as popups.
  const problems = useSyncExternalStore(subscribeProblems, getProblems, getProblems);
  const unreadCount = serverUnread + problems.filter((p) => !p.read).length;

  const refreshUnreadCount = useCallback(() => {
    api
      .getUnreadNotificationCount()
      .then((n) => setUnreadCount(typeof n === "number" ? n : 0))
      .catch(() => {
        // Transient failure / old core without the route - the badge just
        // stays at its last-known value rather than complaining.
      });
  }, []);

  const refreshList = useCallback(() => {
    api
      .getNotifications(false, 20)
      .then((list) => {
        setItems(Array.isArray(list) ? list : []);
        setListFailed(false);
      })
      .catch(() => setListFailed(true))
      .finally(() => setLoaded(true));
  }, []);

  // Badge count: fetched once on mount, then polled every 30s regardless of
  // whether the popover is open - this is the "cheap, frequently-polled"
  // affordance the backend was built for. Only ever for a real session, though
  // - see the `username` gate above for how an anonymous visitor gets here.
  useEffect(() => {
    if (!username) return undefined;
    refreshUnreadCount();
    return visibleInterval(refreshUnreadCount, UNREAD_POLL_MS);
  }, [username, refreshUnreadCount]);

  // Full list: fetched once each time the popover opens, NOT on an interval.
  // No `username` gate needed here specifically: `open` can only ever become
  // true by clicking the trigger, which isn't rendered at all without one (see
  // the early return just below).
  useEffect(() => {
    if (open) refreshList();
  }, [open, refreshList]);

  // Nothing to notify an anonymous visitor about. Rendering a permanently-empty
  // bell whose every interaction 401s would be worse than not rendering it, and
  // it also closes the refreshList() path above. Placed after every hook, not
  // before, so the hook order stays stable across the signed-in/out transition.
  if (!username) return null;

  const handleRowClick = (n: NotificationResponse) => {
    if (!n.read) {
      // Optimistic - flip local state immediately, don't wait for the
      // response (spec).
      setItems((prev) => prev.map((row) => (row.id === n.id ? { ...row, read: true } : row)));
      setUnreadCount((c) => Math.max(0, c - 1));
      api.markNotificationRead(n.id).catch(() => {
        // Best-effort - the next open/poll reconciles it either way.
      });
    }
    if (n.link) {
      setOpen(false);
      router.push(n.link);
    }
  };

  const handleMarkAllRead = () => {
    markAllProblemsRead();
    api
      .markAllNotificationsRead()
      .catch(() => {})
      .finally(() => {
        // Refetch both, per spec, rather than only flipping local state -
        // keeps this the one place list/badge get reconciled against the
        // server instead of drifting from independently-guessed local edits.
        refreshList();
        refreshUnreadCount();
      });
  };

  const badgeLabel = unreadCount > 9 ? "9+" : String(unreadCount);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={unreadCount > 0 ? t("bellAriaUnread", { count: unreadCount }) : t("bellTitle")}
          title={t("bellTitle")}
          className={cn(
            bareButtonClass,
            "relative size-9 justify-center text-[var(--text-muted)] hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
          )}
        >
          <Bell size={16} aria-hidden="true" />
          {unreadCount > 0 && (
            <span
              className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-destructive-foreground"
              aria-hidden="true"
            >
              {badgeLabel}
            </span>
          )}
        </button>
      </PopoverTrigger>

      <PopoverContent className="p-0">
        <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-2">
          <span className="text-xs font-semibold text-foreground">{t("bellTitle")}</span>
          <button
            type="button"
            onClick={handleMarkAllRead}
            disabled={unreadCount === 0}
            className={cn(
              bareButtonClass,
              "gap-1 rounded px-1.5 py-1 text-[11px] text-muted-foreground hover:bg-secondary hover:text-foreground",
              "disabled:pointer-events-none disabled:opacity-40"
            )}
          >
            <CheckCheck aria-hidden="true" size={12} />
            {t("markAllRead")}
          </button>
        </div>

        <div className="max-h-96 overflow-y-auto">
          {!loaded && <p className="px-3 py-6 text-center text-xs text-muted-foreground">{t("bellLoading")}</p>}
          {loaded && listFailed && problems.length === 0 && (
            <div className="flex flex-col items-center gap-2 px-3 py-6 text-center text-xs text-muted-foreground">
              <p>{t("bellLoadFailed")}</p>
              <button
                type="button"
                onClick={refreshList}
                className={cn(bareButtonClass, "rounded px-2 py-1 text-foreground hover:bg-secondary")}
              >
                {t("retry")}
              </button>
            </div>
          )}
          {problems.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => markProblemRead(p.id)}
              className={cn(
                bareButtonClass,
                "w-full flex-col items-stretch gap-1 border-b border-border/60 px-3 py-2.5 text-start hover:bg-secondary",
                !p.read && "bg-secondary/40"
              )}
            >
              <span className="flex min-w-0 items-center gap-1.5">
                <span
                  className="state-dot shrink-0"
                  style={{
                    ["--state" as string]: p.kind === "error" ? SEVERITY_STATE_VAR.ERROR : SEVERITY_STATE_VAR.WARNING,
                  }}
                  aria-hidden="true"
                />
                <span className="sr-only">{t(p.kind === "error" ? "severityError" : "severityWarning") + ": "}</span>
                <span className={cn("text-xs", p.read ? "text-foreground" : "font-semibold text-foreground")}>
                  {p.message}
                </span>
              </span>
              <span className="ps-3.5 text-[10px] text-muted-foreground">
                {timeAgo(new Date(p.createdAt).toISOString())}
              </span>
            </button>
          ))}
          {loaded && !listFailed && items.length === 0 && problems.length === 0 && (
            <p className="px-3 py-6 text-center text-xs text-muted-foreground">{t("bellEmpty")}</p>
          )}
          {items.map((n) => (
            <button
              key={n.id}
              type="button"
              onClick={() => handleRowClick(n)}
              className={cn(
                bareButtonClass,
                "w-full flex-col items-stretch gap-1 border-b border-border/60 px-3 py-2.5 text-start last:border-b-0 hover:bg-secondary",
                !n.read && "bg-secondary/40"
              )}
            >
              <span className="flex min-w-0 items-center gap-1.5">
                {/* Shared execution-state dot primitive (globals.css
                    `.state-dot`) - the same one AppShell's own system-health
                    pill uses, driven off the same `--state` custom property. */}
                <span
                  className="state-dot shrink-0"
                  style={{ ["--state" as string]: SEVERITY_STATE_VAR[n.severity] ?? SEVERITY_STATE_VAR.INFO }}
                  aria-hidden="true"
                />
                {/* Severity is otherwise conveyed only by the dot's colour
                    (aria-hidden, above) - name it in words for assistive
                    tech too (a11y audit follow-up). */}
                <span className="sr-only">{t(SEVERITY_KEY[n.severity] ?? SEVERITY_KEY.INFO) + ": "}</span>
                <span className={cn("truncate text-xs", n.read ? "text-foreground" : "font-semibold text-foreground")}>
                  {n.title}
                </span>
                {/* AGENT = ambient/sentinel output (a watch noticed something or
                    parked a proposal) - worth a marker so it does not read as
                    a system alert. Plain text chip; no new colour needed. */}
                {n.category === "AGENT" && (
                  <span className="shrink-0 rounded border border-[color-mix(in_srgb,var(--accent-500)_40%,transparent)] px-1 text-[10px] text-[var(--accent-500)]">
                    {t("agentChip")}
                  </span>
                )}
              </span>
              {n.body && <span className="truncate ps-3.5 text-[11px] text-muted-foreground">{firstLine(n.body)}</span>}
              <span className="ps-3.5 text-[10px] text-muted-foreground">{timeAgo(n.createdAt)}</span>
            </button>
          ))}
        </div>

        <div className="border-t border-border px-3 py-2 text-center">
          <Link
            href="/notifications"
            onClick={() => setOpen(false)}
            className="text-xs font-medium text-primary underline-offset-4 hover:underline"
          >
            {t("viewAll")}
          </Link>
        </div>
      </PopoverContent>
    </Popover>
  );
}

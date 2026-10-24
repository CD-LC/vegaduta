"use client";

// Browser-notification opt-in for the approvals queue, and the watcher that raises them.
//
// Scope, honestly: this is a page-level notifier. It runs while a tab with the approvals queue is open (it polls
// the pending list) and shows a system notification only when that tab is in the background. There is no service
// worker or push subscription, so it cannot reach someone with no tab open; the in-app notification centre and
// the "needs a decision" badges cover that.
import { useCallback, useEffect, useRef, useState } from "react";
import { Bell, BellOff } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import type { ApprovalRequestItem } from "./approvalsModel";
import { requestToolName } from "./approvalsQueueModel";

const PREF_KEY = "vegaduta.approvals.notify";
export const NOTIFY_POLL_MS = 30_000;

export type NotifyPermission = "unsupported" | "default" | "granted" | "denied";

export function notifyPermission(): NotifyPermission {
  if (typeof window === "undefined" || typeof Notification === "undefined") return "unsupported";
  return Notification.permission as NotifyPermission;
}

function readPref(): boolean {
  try {
    return window.localStorage.getItem(PREF_KEY) === "1";
  } catch {
    return false;
  }
}
function writePref(on: boolean): void {
  try {
    if (on) window.localStorage.setItem(PREF_KEY, "1");
    else window.localStorage.removeItem(PREF_KEY);
  } catch {
    /* storage blocked: the choice lasts for this page view only */
  }
}

/** Opt-in state: permission + the person's on/off choice. Notifications fire only when both say yes. */
export function useNotifyPreference() {
  const [permission, setPermission] = useState<NotifyPermission>("unsupported");
  const [on, setOn] = useState(false);
  useEffect(() => {
    setPermission(notifyPermission());
    setOn(readPref());
  }, []);

  const enable = useCallback(async () => {
    if (typeof Notification === "undefined") return;
    let result: NotificationPermission = Notification.permission;
    if (result === "default") {
      try {
        result = await Notification.requestPermission();
      } catch {
        result = Notification.permission;
      }
    }
    setPermission(result as NotifyPermission);
    const granted = result === "granted";
    setOn(granted);
    writePref(granted);
  }, []);
  const disable = useCallback(() => {
    setOn(false);
    writePref(false);
  }, []);
  return { permission, on: on && permission === "granted", enable, disable };
}

export function ApprovalNotifyOptIn({
  permission,
  on,
  onEnable,
  onDisable,
}: {
  permission: NotifyPermission;
  on: boolean;
  onEnable: () => void;
  onDisable: () => void;
}) {
  const t = useTranslations("approvalsQueue");
  if (permission === "unsupported") {
    return (
      <p className="text-xs text-muted-foreground" data-testid="notify-unsupported">
        {t("notifyUnsupported")}
      </p>
    );
  }
  if (permission === "denied") {
    return (
      <p className="inline-flex items-center gap-1.5 text-xs text-muted-foreground" data-testid="notify-blocked">
        <BellOff size={13} aria-hidden="true" />
        {t("notifyBlocked")}
      </p>
    );
  }
  return on ? (
    <Button type="button" size="sm" variant="outline" aria-pressed="true" onClick={onDisable} data-testid="notify-disable">
      <Bell size={14} aria-hidden="true" />
      {t("notifyOn")}
    </Button>
  ) : (
    <Button type="button" size="sm" variant="outline" aria-pressed="false" title={t("notifyHelp")} onClick={onEnable} data-testid="notify-enable">
      <BellOff size={14} aria-hidden="true" />
      {t("notifyOff")}
    </Button>
  );
}

/**
 * Polls the pending requests and reports the ones that were not there before (the first read only seeds the set).
 * When the person opted in and the tab is in the background, each batch raises one system notification whose
 * click focuses the tab and opens that request.
 */
export function useNewApprovalWatcher({
  poll,
  notify,
  onNew,
  onOpen,
  pollMs = NOTIFY_POLL_MS,
}: {
  poll: () => Promise<ApprovalRequestItem[]>;
  notify: boolean;
  onNew?: (fresh: ApprovalRequestItem[]) => void;
  onOpen: (id: string) => void;
  pollMs?: number;
}) {
  const t = useTranslations("approvalsQueue");
  const seen = useRef<Set<string> | null>(null);
  const latest = useRef({ poll, notify, onNew, onOpen, t });
  latest.current = { poll, notify, onNew, onOpen, t };

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const tick = async () => {
      const cur = latest.current;
      try {
        const rows = await cur.poll();
        if (stopped) return;
        const ids = new Set(rows.map((r) => r.id));
        if (seen.current) {
          const fresh = rows.filter((r) => !seen.current!.has(r.id));
          if (fresh.length > 0) {
            latest.current.onNew?.(fresh);
            if (latest.current.notify && typeof document !== "undefined" && document.hidden && notifyPermission() === "granted") {
              raise(fresh, latest.current.t, latest.current.onOpen);
            }
          }
        }
        seen.current = ids;
      } catch {
        /* quiet: the next tick tries again */
      }
      if (!stopped) timer = setTimeout(() => void tick(), pollMs);
    };
    // Not synchronously: the host's own first read goes out first.
    timer = setTimeout(() => void tick(), 0);
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [pollMs]);
}

function raise(fresh: ApprovalRequestItem[], t: (k: string, v?: Record<string, unknown>) => string, onOpen: (id: string) => void) {
  try {
    const first = fresh[0];
    const body =
      fresh.length === 1 ? t("notifyBodyOne", { tool: requestToolName(first) }) : t("notifyBodyMany", { count: fresh.length });
    // One tag: a new batch replaces the previous notification instead of stacking.
    const n = new Notification(t("notifyTitle"), { body, tag: "vegaduta-approvals" });
    n.onclick = () => {
      try {
        window.focus();
      } catch {
        /* ignore */
      }
      onOpen(first.id);
      n.close();
    };
  } catch {
    /* a notification that cannot be shown is not an error worth surfacing */
  }
}

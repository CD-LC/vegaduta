"use client";

import { useEffect, useMemo, useRef, useState } from "react";

/**
 * Trigger/schedule status, surfaced in an Alerts/Warnings popover instead of
 * banners above the canvas (#342). Computed for every viewer so it doubles as
 * one glance-able summary of "how can this workflow actually be triggered".
 * The popover's trigger button is never rendered when there is nothing to
 * show, so there is no empty/closed-forever state to design for; it closes on
 * an outside click.
 */
export function useCanvasAlerts({
  workflow,
  schedule,
  canManageWorkflows,
  t,
}: {
  workflow: { status?: string } | null;
  schedule: { enabled?: boolean; nextRunAt?: string | null } | null;
  canManageWorkflows: boolean;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  const [showAlertsPopover, setShowAlertsPopover] = useState(false);
  const alertsPopoverRef = useRef(null);

  useEffect(() => {
    function onClickOutside(e: any) {
      if (alertsPopoverRef.current && !alertsPopoverRef.current.contains(e.target)) {
        setShowAlertsPopover(false);
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  const alerts = useMemo(() => {
    if (!workflow) return [];
    const list = [];
    if (workflow.status !== "ACTIVE") {
      list.push({
        id: "not-active",
        tone: "info",
        text: t("draftAlertText"),
      });
      return list;
    }
    if (schedule) {
      list.push({
        id: "schedule",
        tone: schedule.enabled ? "ok" : "warn",
        text: schedule.enabled
          ? schedule.nextRunAt
            ? t("scheduleOnNextRunText", { date: new Date(schedule.nextRunAt).toLocaleString() })
            : t("scheduleOnText")
          : t("scheduleOffText"),
      });
    }
    if (!canManageWorkflows && list.some((a) => a.tone === "warn")) {
      list.push({ id: "admin-note", tone: "info", text: t("adminNoteText") });
    }
    return list;
  }, [workflow, schedule, canManageWorkflows, t]);

  return { alerts, showAlertsPopover, setShowAlertsPopover, alertsPopoverRef };
}

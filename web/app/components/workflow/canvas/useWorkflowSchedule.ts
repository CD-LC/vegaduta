"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { toast } from "../../../lib/toastBus";
import { cronFromPreset, parseCronPreset } from "../workflowCanvasHelpers";

/**
 * Schedule trigger (#166): fires the workflow automatically with no external
 * caller. frequency "off" means no schedule configured/enabled. Times are UTC
 * (see WorkflowScheduler's javadoc), stated in the UI rather than assumed local.
 */
export function useWorkflowSchedule(opts: {
  workflowId: any;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  const { workflowId, t } = opts;
  const [schedule, setSchedule] = useState<any>(null);
  const [scheduleFrequency, setScheduleFrequency] = useState("off");
  const [scheduleTime, setScheduleTime] = useState("09:00");
  const [scheduleDay, setScheduleDay] = useState("1"); // cron day-of-week: 0=Sun..6=Sat
  const [scheduleBusy, setScheduleBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getWorkflowSchedule(workflowId)
      .then((s) => {
        if (cancelled) return;
        setSchedule(s);
        // Reflect the saved schedule back into the picker so re-opening a
        // workflow shows what's already configured, not a reset "Off".
        const parsed = parseCronPreset(s?.cron);
        if (s?.enabled && parsed) {
          setScheduleFrequency(parsed.frequency);
          if (parsed.time) setScheduleTime(parsed.time);
          if (parsed.day !== undefined) setScheduleDay(parsed.day);
        }
      })
      .catch(() => !cancelled && setSchedule(null));
    return () => {
      cancelled = true;
    };
  }, [workflowId]);

  async function enable(cron: string) {
    setScheduleBusy(true);
    try {
      setSchedule(await api.enableWorkflowSchedule(workflowId, cron));
      toast.success(t("scheduleSavedSuccess"));
    } catch (err) {
      toast.error(errorMessage(err, t("scheduleUpdateError")));
    } finally {
      setScheduleBusy(false);
    }
  }

  // Same admin-only, ACTIVE-only posture as the old workflow-wide control.
  const applySchedule = () => enable(cronFromPreset(scheduleFrequency, scheduleTime, scheduleDay));

  // Switches the AI plan's suggested schedule on with the cron core validated.
  const turnOnSuggestedSchedule = (cron: string | undefined) => (cron ? enable(cron) : undefined);

  async function turnOffSchedule() {
    setScheduleBusy(true);
    try {
      setSchedule(await api.disableWorkflowSchedule(workflowId));
      setScheduleFrequency("off");
    } catch (err) {
      toast.error(errorMessage(err, t("scheduleUpdateError")));
    } finally {
      setScheduleBusy(false);
    }
  }

  return {
    schedule,
    scheduleFrequency,
    setScheduleFrequency,
    scheduleTime,
    setScheduleTime,
    scheduleDay,
    setScheduleDay,
    scheduleBusy,
    applySchedule,
    turnOnSuggestedSchedule,
    turnOffSchedule,
  };
}

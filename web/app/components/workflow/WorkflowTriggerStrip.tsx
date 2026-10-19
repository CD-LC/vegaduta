"use client";

import Select from "../Select";
import { Button } from "../ui/button";

export default function WorkflowTriggerStrip({
  applySchedule,
  canManageWorkflows,
  schedule,
  scheduleBusy,
  scheduleDay,
  scheduleFrequency,
  scheduleTime,
  setScheduleDay,
  setScheduleFrequency,
  setScheduleTime,
  scheduleSuggestion,
  t,
  turnOffSchedule,
  workflow,
}: any) {
  return (
    <>
      {/* Trigger/schedule ADMIN CONTROLS stay here (this is where you'd come
       * to actually turn them on) - only the passive status sentences moved
       * to the bottom Alerts & Warnings section (#342). Nothing renders here
       * at all for non-admins or while still a DRAFT, so building a workflow
       * never surfaces trigger/schedule chrome before there's anything to
       * configure. */}
      {/* Per-node trigger channel binding (multi-trigger graphs) - the
       * channel-select + webhook enable/disable controls that used to live
       * in this toolbar strip (as a single workflow-wide setting) moved into
       * each Trigger node's own NodeConfigDrawer.tsx config panel, since a
       * graph can now hold more than one Trigger node, each independently
       * bound to a different inbound channel - there's no longer one
       * workflow-wide value for this strip to show. Only the schedule box
       * stays here: schedule is time-based, not channel-based, so it's
       * still a single workflow-wide setting. */}
      <div className="ax-wf-trigger-strip">
      {workflow.status === "ACTIVE" && canManageWorkflows && (
        <div className="wf-trigger-box">
          <strong style={{ fontSize: "0.85rem" }}>{t("scheduleLabel")}</strong>
          <Select
            className="select-inline"
            value={scheduleFrequency}
            onChange={(e) => setScheduleFrequency(e.target.value)}
            options={[
              { value: "off", label: t("scheduleOffOption") },
              { value: "hourly", label: t("hourlyOption") },
              { value: "daily", label: t("dailyOption") },
              { value: "weekly", label: t("weeklyOption") },
            ]}
          />
          {(scheduleFrequency === "daily" || scheduleFrequency === "weekly") && (
            <>
              {scheduleFrequency === "weekly" && (
                <Select
                  className="select-inline"
                  value={scheduleDay}
                  onChange={(e) => setScheduleDay(e.target.value)}
                  options={[
                    { value: "0", label: t("sundayOption") },
                    { value: "1", label: t("mondayOption") },
                    { value: "2", label: t("tuesdayOption") },
                    { value: "3", label: t("wednesdayOption") },
                    { value: "4", label: t("thursdayOption") },
                    { value: "5", label: t("fridayOption") },
                    { value: "6", label: t("saturdayOption") },
                  ]}
                />
              )}
              <input type="time" value={scheduleTime} onChange={(e) => setScheduleTime(e.target.value)} />
              <span className="muted" style={{ fontSize: "0.76rem" }}>
                {t("utcLabel")}
              </span>
            </>
          )}
          {scheduleFrequency === "off" ? (
            schedule?.enabled && (
              <Button type="button" variant="outline" size="sm" onClick={turnOffSchedule} disabled={scheduleBusy}>
                {scheduleBusy ? t("working") : t("turnOffSchedule")}
              </Button>
            )
          ) : (
            <Button type="button" variant="outline" size="sm" onClick={applySchedule} disabled={scheduleBusy}>
              {scheduleBusy ? t("saving") : t("saveSchedule")}
            </Button>
          )}
          {/* nlflow W4: the AI builder read a recurrence out of the description
           * and prefilled the picker above; say so, the user still has to save. */}
          {scheduleSuggestion && (
            <span className="muted" style={{ fontSize: "0.78rem" }} data-testid="schedule-suggestion">
              {scheduleSuggestion}
            </span>
          )}
          {schedule?.enabled && schedule?.nextRunAt && (
            <span className="muted" style={{ fontSize: "0.78rem" }}>
              {t("nextRunLabel", { date: new Date(schedule.nextRunAt).toLocaleString() })}
            </span>
          )}
        </div>
      )}
      </div>
    </>
  );
}

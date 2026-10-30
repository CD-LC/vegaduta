"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { CalendarClock, Loader2, Play } from "lucide-react";
import Link from "next/link";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../AuthProvider";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Select } from "../ui/select";
import { Textarea } from "../ui/textarea";
import { EmptyState } from "../ax/page-kit";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { formatWhen } from "./jobStatus";
import {
  describeCron,
  describeInterval,
  intervalToSeconds,
  secondsToAmountUnit,
  validateInterval,
  type Unit,
} from "./scheduleDescribe";

// Recurring runs for one agent. The endpoint contract this UI leans on
// (AgentScheduleController):
//   - GET 404        -> the agent has never had a schedule ("add one")
//   - GET 200 + enabled:false -> configured but paused ("resume")
//   - PUT upserts, DELETE pauses but KEEPS the configuration, POST /resume
//     re-arms it, POST /run-now fires once, GET /runs is the recent history
// which is why Pause below re-renders from the DELETE response instead of
// clearing the form - the configuration is still there, merely inactive.
//
// Cadence is EXACTLY ONE of a cron or an interval (server-validated too). The
// cron field accepts 5-field Unix or 6-field Spring cron; the server normalizes
// 5 -> 6 itself, so no client-side cron parsing is attempted beyond the
// best-effort human-readable preview. Validation errors (bad cron, unknown
// timezone) come back as clean 400 messages and are shown verbatim.
type Cadence = "interval" | "cron";
type Target = "AGENT" | "WORKFLOW";

export default function SchedulePanel({ agents }: { agents: any[] }) {
    const tHC = useTranslations("hcComponentsJobsSchedulePanel");
  const t = useTranslations("schedulePanel");
  const { isAdmin } = useAuth();
  const [agentId, setAgentId] = useState<string>(agents[0]?.id || "");
  const [schedule, setSchedule] = useState<any>(null);
  const [runs, setRuns] = useState<any[]>([]);
  const runsPager = usePagination(runs, 10);
  const [workflows, setWorkflows] = useState<any[]>([]);
  const [absent, setAbsent] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [cadence, setCadence] = useState<Cadence>("interval");
  const [amount, setAmount] = useState("15");
  const [unit, setUnit] = useState<Unit>("minutes");
  const [cron, setCron] = useState("");
  const [timezone, setTimezone] = useState("");
  const [goalTemplate, setGoalTemplate] = useState("");
  const [target, setTarget] = useState<Target>("AGENT");
  const [workflowId, setWorkflowId] = useState("");
  const [sentinel, setSentinel] = useState(false);

  const browserZone = useMemo(() => {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    } catch {
      return "UTC";
    }
  }, []);

  const applySchedule = useCallback((data: any) => {
    setSchedule(data);
    setAbsent(false);
    if (data.intervalSeconds) {
      const parts = secondsToAmountUnit(data.intervalSeconds);
      setCadence("interval");
      setAmount(String(parts.amount));
      setUnit(parts.unit);
      setCron("");
    } else {
      setCadence("cron");
      setCron(data.cron || "");
    }
    setTimezone(data.timezone || "");
    setGoalTemplate(data.goalTemplate || "");
    setTarget(data.targetType === "WORKFLOW" ? "WORKFLOW" : "AGENT");
    setWorkflowId(data.workflowId || "");
    setSentinel(data.sentinel === true);
  }, []);

  const loadRuns = useCallback(async () => {
    if (!agentId) return;
    try {
      const data = await api.listAgentScheduleRuns(agentId, 20);
      setRuns(Array.isArray(data) ? data : []);
    } catch {
      setRuns([]);
    }
  }, [agentId]);

  const load = useCallback(async () => {
    if (!agentId) return;
    setLoading(true);
    setError(null);
    setNotice(null);
    try {
      const data = await api.getAgentSchedule(agentId);
      applySchedule(data);
      await loadRuns();
    } catch (err: any) {
      if (err?.status === 404) {
        // A real answer, not a failure: this agent has no schedule yet.
        setSchedule(null);
        setAbsent(true);
        setRuns([]);
        setCadence("interval");
        setAmount("15");
        setUnit("minutes");
        setCron("");
        setTimezone("");
        setGoalTemplate("");
        setTarget("AGENT");
        setWorkflowId("");
        setSentinel(false);
      } else {
        setError(err?.message || t("loadError"));
      }
    } finally {
      setLoading(false);
    }
  }, [agentId, applySchedule, loadRuns, t]);

  useEffect(() => {
    void load();
  }, [load]);

  // Only fetched when a workflow target is picked; failure just leaves the list empty.
  useEffect(() => {
    if (target !== "WORKFLOW" || workflows.length > 0) return;
    api
      .listWorkflows()
      .then((data: any) => setWorkflows(Array.isArray(data) ? data : []))
      .catch(() => setWorkflows([]));
  }, [target, workflows.length]);

  const amountNumber = Number(amount);
  const intervalValid = validateInterval(amountNumber, unit);
  const zone = timezone.trim() || "UTC";

  const preview = useMemo(() => {
    if (cadence === "interval") {
      if (!intervalValid) return null;
      return t("previewInZone", {
        summary: describeInterval(intervalToSeconds(amountNumber, unit), t),
        zone,
      });
    }
    if (!cron.trim()) return null;
    const summary = describeCron(cron, t);
    return summary ? t("previewInZone", { summary, zone }) : t("previewRawCron", { cron: cron.trim() });
  }, [cadence, intervalValid, amountNumber, unit, cron, zone, t]);

  async function save() {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const payload: Parameters<typeof api.upsertAgentSchedule>[1] = {
        ...(cadence === "interval"
          ? { intervalSeconds: intervalToSeconds(amountNumber, unit) }
          : { cron: cron.trim() }),
        ...(timezone.trim() ? { timezone: timezone.trim() } : {}),
        goalTemplate: goalTemplate.trim(),
        targetType: target,
        ...(target === "WORKFLOW" ? { workflowId } : {}),
        // Sentinel is AGENT-only (server rejects sentinel+WORKFLOW): send the
        // flag only for agent targets, false otherwise.
        sentinel: target === "AGENT" ? sentinel : false,
      };
      applySchedule(await api.upsertAgentSchedule(agentId, payload));
      await loadRuns();
    } catch (err: any) {
      setError(err?.message || t("saveError"));
    } finally {
      setSaving(false);
    }
  }

  async function pause() {
    setSaving(true);
    setError(null);
    try {
      setSchedule(await api.disableAgentSchedule(agentId));
    } catch (err: any) {
      setError(err?.message || t("pauseError"));
    } finally {
      setSaving(false);
    }
  }

  async function resume() {
    setSaving(true);
    setError(null);
    try {
      setSchedule(await api.resumeAgentSchedule(agentId));
    } catch (err: any) {
      setError(err?.message || t("resumeError"));
    } finally {
      setSaving(false);
    }
  }

  async function runNow() {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const run = await api.runAgentScheduleNow(agentId);
      setNotice(run?.status === "SKIPPED" ? t("runNowSkipped", { reason: run.error || "" }) : t("runNowStarted"));
      // Re-read both: run-now updates the counters and adds a history row.
      const fresh = await api.getAgentSchedule(agentId);
      setSchedule(fresh);
      await loadRuns();
    } catch (err: any) {
      setError(err?.message || t("runNowError"));
    } finally {
      setSaving(false);
    }
  }

  if (agents.length === 0) {
    return <EmptyState icon={CalendarClock} title={t("noAgentsTitle")} description={t("noAgentsBody")} />;
  }

  const cadenceOk = cadence === "interval" ? intervalValid : cron.trim().length > 0;
  const targetOk = target === "AGENT" || workflowId.length > 0;
  const canSave = !!agentId && cadenceOk && targetOk && goalTemplate.trim().length > 0 && !saving;
  const isWorkflow = target === "WORKFLOW";

  return (
    <div className="space-y-4">
      <div className="max-w-sm space-y-1.5">
        <Label htmlFor="sched-agent">{t("agent")}</Label>
        <Select
          id="sched-agent"
          value={agentId}
          onValueChange={setAgentId}
          options={agents.map((a: any) => ({ value: a.id, label: a.name }))}
        />
      </div>

      {loading ? (
        <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" /> {t("loading")}
        </div>
      ) : (
        <>
          {schedule && (
            <Card className="gap-3 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <Badge
                  variant="outline"
                  className={
                    schedule.enabled
                      ? "border-[color-mix(in_srgb,var(--state-success)_40%,transparent)] text-[var(--state-success)]"
                      : "border-muted-foreground/30 text-muted-foreground"
                  }
                >
                  {schedule.enabled ? t("enabled") : t("paused")}
                </Badge>
                {schedule.enabled && schedule.nextRunAt && (
                  <span className="text-xs text-muted-foreground">
                    {t("nextRun", { when: formatWhen(schedule.nextRunAt) })}
                  </span>
                )}
                {schedule.sentinel && (
                  <Badge
                    variant="outline"
                    className="border-[color-mix(in_srgb,var(--accent-500)_40%,transparent)] text-[var(--accent-500)]"
                  >
                    {t("sentinelBadge")}
                  </Badge>
                )}
                <div className="ms-auto flex items-center gap-2">
                  <Button size="sm" variant="outline" onClick={runNow} disabled={saving}>
                    <Play className="size-3.5" aria-hidden="true" /> {t("runNow")}
                  </Button>
                  {schedule.enabled ? (
                    <Button size="sm" variant="outline" onClick={pause} disabled={saving}>
                      {t("pause")}
                    </Button>
                  ) : (
                    <Button size="sm" onClick={resume} disabled={saving}>
                      {t("resume")}
                    </Button>
                  )}
                </div>
              </div>
              <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs sm:grid-cols-4">
                <ScheduleMeta label={t("fired")} value={String(schedule.totalFireCount)} />
                <ScheduleMeta label={t("skipped")} value={String(schedule.totalSkipCount)} />
                <ScheduleMeta label={t("lastFired")} value={formatWhen(schedule.lastFiredAt)} />
                <ScheduleMeta
                  label={t("lastJob")}
                  value={schedule.lastJobStatus ? schedule.lastJobStatus.toLowerCase().replace(/_/g, " ") : "—"}
                />
              </dl>
              {schedule.lastJobId && schedule.targetType !== "WORKFLOW" && (
                <Link href={`/jobs/${agentId}/${schedule.lastJobId}`} className="text-xs text-primary underline">
                  {t("openLastRun")}
                </Link>
              )}
              {/* Consecutive skips are the schedule's own health signal - an
                  overlap guard skipping every occurrence usually means one
                  run never finishes before the next is due. */}
              {schedule.consecutiveSkipCount > 0 && (
                <p className="text-xs text-[var(--state-warning)]">
                  {t("skipsInARow", {
                    count: schedule.consecutiveSkipCount,
                    reason: schedule.lastSkipReason
                      ? ` (${schedule.lastSkipReason.toLowerCase().replace(/_/g, " ")})`
                      : "",
                  })}
                </p>
              )}
              {notice && (
                <p className="text-xs text-muted-foreground" role="status">
                  {notice}
                </p>
              )}
            </Card>
          )}

          <Card className="gap-4 p-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="sched-target">{t("targetLabel")}</Label>
                <Select
                  id="sched-target"
                  value={target}
                  onValueChange={(v) => setTarget(v as Target)}
                  options={[
                    { value: "AGENT", label: t("targetAgent") },
                    { value: "WORKFLOW", label: t("targetWorkflow") },
                  ]}
                />
              </div>
              {isWorkflow && (
                <div className="space-y-1.5">
                  <Label htmlFor="sched-workflow">{t("workflowLabel")}</Label>
                  <Select
                    id="sched-workflow"
                    value={workflowId}
                    onValueChange={setWorkflowId}
                    placeholder={t("workflowPlaceholder")}
                    options={workflows.map((w: any) => ({ value: w.id, label: w.name }))}
                  />
                </div>
              )}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="sched-cadence">{t("cadenceLabel")}</Label>
                <Select
                  id="sched-cadence"
                  value={cadence}
                  onValueChange={(v) => setCadence(v as Cadence)}
                  options={[
                    { value: "interval", label: t("cadenceInterval") },
                    { value: "cron", label: t("cadenceCron") },
                  ]}
                />
              </div>

              {cadence === "interval" ? (
                <div className="space-y-1.5">
                  <Label htmlFor="sched-interval">{t("intervalEvery")}</Label>
                  <div className="flex gap-2">
                    <Input
                      id="sched-interval"
                      type="number"
                      min={1}
                      value={amount}
                      onChange={(e) => setAmount(e.target.value)}
                      aria-describedby="sched-interval-hint"
                      aria-invalid={!intervalValid}
                    />
                    <Select
                      aria-label={t("intervalEvery")}
                      value={unit}
                      onValueChange={(v) => setUnit(v as Unit)}
                      options={[
                        { value: "minutes", label: t("unitMinutes") },
                        { value: "hours", label: t("unitHours") },
                        { value: "days", label: t("unitDays") },
                      ]}
                    />
                  </div>
                  <p id="sched-interval-hint" className="text-xs text-muted-foreground">
                    {t("intervalHint")}
                  </p>
                </div>
              ) : (
                <div className="space-y-1.5">
                  <Label htmlFor="sched-cron">{t("cronLabel")}</Label>
                  <Input
                    id="sched-cron"
                    value={cron}
                    onChange={(e) => setCron(e.target.value)}
                    placeholder={tHC("s1")}
                    className="font-mono"
                    aria-describedby="sched-cron-hint"
                  />
                  <p id="sched-cron-hint" className="text-xs text-muted-foreground">
                    {t("cronHint")}
                  </p>
                </div>
              )}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="sched-tz">{t("timezoneLabel")}</Label>
                <Input
                  id="sched-tz"
                  value={timezone}
                  onChange={(e) => setTimezone(e.target.value)}
                  placeholder={browserZone}
                  aria-describedby="sched-tz-hint"
                />
                <p id="sched-tz-hint" className="text-xs text-muted-foreground">
                  {t("timezoneHint")}
                </p>
              </div>
              <div className="space-y-1.5">
                <Label>{t("previewLabel")}</Label>
                <p className="flex h-9 items-center text-sm text-foreground" aria-live="polite">
                  {preview ?? <span className="text-muted-foreground">{t("previewInvalid")}</span>}
                </p>
              </div>
            </div>

            {/* Sentinel mode (AGENT targets only): the cadence becomes a watch
                brief - each firing ends in a ```sentinel verdict the backend
                sweep turns into a notification or a parked proposal. */}
            {!isWorkflow && (
              <label className="flex items-start gap-2.5 rounded-md border border-border px-3 py-2.5">
                <input
                  type="checkbox"
                  checked={sentinel}
                  onChange={(e) => setSentinel(e.target.checked)}
                  className="mt-0.5"
                />
                <span className="space-y-0.5">
                  <span className="block text-sm font-medium text-foreground">{t("sentinelLabel")}</span>
                  <span className="block text-xs text-muted-foreground">{t("sentinelHint")}</span>
                </span>
              </label>
            )}

            <div className="space-y-1.5">
              <Label htmlFor="sched-goal">
                {isWorkflow ? t("inputLabel") : sentinel ? t("sentinelGoalLabel") : t("goalLabel")}
              </Label>
              <Textarea
                id="sched-goal"
                rows={3}
                maxLength={4000}
                value={goalTemplate}
                onChange={(e) => setGoalTemplate(e.target.value)}
                placeholder={isWorkflow ? t("inputPlaceholder") : t("goalPlaceholder")}
              />
            </div>

            {error && (
              <div
                role="alert"
                className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
              >
                {error}
              </div>
            )}

            <div className="flex items-center gap-2">
              <Button onClick={save} disabled={!canSave}>
                {saving && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
                {absent ? t("create") : schedule && !schedule.enabled ? t("saveReenable") : t("save")}
              </Button>
              {!isAdmin && <p className="ms-auto text-xs text-muted-foreground">{t("adminOnly")}</p>}
            </div>
          </Card>

          {schedule && (
            <Card className="gap-2 p-4">
              <h3 className="text-sm font-medium text-foreground">{t("recentRuns")}</h3>
              {runs.length === 0 ? (
                <p className="text-xs text-muted-foreground">{t("noRuns")}</p>
              ) : (
                <ul className="divide-y divide-border text-xs">
                  {runsPager.pageItems.map((r: any) => (
                    <li key={r.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-1.5">
                      <span className="text-muted-foreground">{formatWhen(r.startedAt)}</span>
                      <Badge variant="outline">{t(`trigger_${r.trigger}`)}</Badge>
                      <span className="font-medium text-foreground">
                        {(r.jobStatus || r.status).toLowerCase().replace(/_/g, " ")}
                      </span>
                      {r.sentinelOutcome && (
                        <Badge variant="outline" title={r.sentinelOutcomeNote || undefined}>
                          {t(`outcome_${r.sentinelOutcome}`)}
                        </Badge>
                      )}
                      {r.jobId && (
                        <Link href={`/jobs/${agentId}/${r.jobId}`} className="text-primary underline">
                          {r.jobId.slice(0, 8)}
                        </Link>
                      )}
                      {r.error && <span className="text-destructive">{r.error}</span>}
                    </li>
                  ))}
                </ul>
              )}
              <PaginationControls
                page={runsPager.page}
                totalPages={runsPager.totalPages}
                totalItems={runsPager.totalItems}
                pageSize={runsPager.pageSize}
                onPageChange={runsPager.setPage}
                onPageSizeChange={runsPager.setPageSize}
                itemLabel={t("recentRuns").toLowerCase()}
              />
            </Card>
          )}
        </>
      )}
    </div>
  );
}

function ScheduleMeta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground">{value}</dd>
    </div>
  );
}

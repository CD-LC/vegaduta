"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Loader2, Save, Sparkles } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import {
  DELIVERY_CHANNELS,
  MONTH_DAY_CHOICES,
  SCHEDULE_KINDS,
  WEEKDAY_CODES,
  buildPayload,
  defaultTimezone,
  deliveryNeedsTarget,
  describeSchedule,
  draftFromTask,
  formatRunTime,
  nextRuns,
  normalizeDeliveryTarget,
  validateSchedule,
  type DeliveryChannel,
  type SchedulePreview,
  type ScheduleDraft,
  type ScheduledTask,
  type ScheduledTasksConfig,
} from "../../lib/scheduledTasks";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Select } from "../ui/select";
import { Textarea } from "../ui/textarea";
import { isPersonalEdition } from "../../lib/edition";

/**
 * Create / edit form for a scheduled task. The schedule is a friendly picker (daily, weekdays, weekly, monthly, or
 * a custom cron); the "next runs" preview under it is computed by the pure helpers in lib/scheduledTasks for the
 * friendly kinds and by the server for a custom cron. Everything is validated again server-side.
 */


export interface AgentOption {
  id: string;
  name: string;
}

/** Pre-fill for a NEW task (a starter template or a duplicate): same fields as the form, all optional. */
export interface TaskSeed {
  name?: string;
  prompt?: string;
  agentId?: string;
  schedule?: Partial<ScheduleDraft>;
  delivery?: DeliveryChannel;
  deliveryTo?: string;
}

// IANA zones for the timezone field's suggestions; empty where the runtime cannot list them (the field stays free text).
function timeZoneChoices(): string[] {
  try {
    const list = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone");
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export default function ScheduledTaskForm({
  config,
  agents,
  initial,
  seed = null,
  onSaved,
  onCancel,
}: {
  config: ScheduledTasksConfig;
  agents: AgentOption[];
  initial: ScheduledTask | null;
  seed?: TaskSeed | null;
  onSaved: (task: ScheduledTask) => void;
  onCancel: () => void;
}) {
  const t = useTranslations("scheduledTasks");
  const editing = initial !== null;

  const [name, setName] = useState(initial?.name ?? seed?.name ?? "");
  const [agentId, setAgentId] = useState(
    initial?.agentId ??
      (seed?.agentId && agents.some((a) => a.id === seed.agentId) ? seed.agentId : (agents[0]?.id ?? ""))
  );
  const [prompt, setPrompt] = useState(initial?.prompt ?? seed?.prompt ?? "");
  const [schedule, setSchedule] = useState<ScheduleDraft>(
    initial
      ? draftFromTask(initial)
      : { kind: "weekdays", time: "08:00", day: "MON", cron: "", timezone: defaultTimezone(), ...seed?.schedule }
  );
  const [delivery, setDelivery] = useState<DeliveryChannel>(initial?.delivery ?? seed?.delivery ?? "in_app");
  const [deliveryTo, setDeliveryTo] = useState(initial?.deliveryTo ?? seed?.deliveryTo ?? "");
  const zones = useMemo(timeZoneChoices, []);
  const [maxRuns, setMaxRuns] = useState(initial?.maxRuns ? String(initial.maxRuns) : "");
  const [expiresOn, setExpiresOn] = useState(initial?.expiresAt ? initial.expiresAt.slice(0, 10) : "");
  const [attempted, setAttempted] = useState(false);
  const [busy, setBusy] = useState(false);
  // A 403 from create/update is the plan limit (free cap, Premium-only channel): shown inline with an upgrade link.
  const [planLimit, setPlanLimit] = useState<string | null>(null);
  const [serverPreview, setServerPreview] = useState<SchedulePreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);

  // The agent list can arrive after the form opens: pick the first agent once it does.
  useEffect(() => {
    if (!agentId && agents.length > 0) setAgentId(agents[0].id);
  }, [agentId, agents]);

  // A fixed agent when editing: the server refuses to move a task to another agent.
  const channels = DELIVERY_CHANNELS.filter((c) => c === "in_app" || config.deliveryChannels.includes(c));
  const scheduleProblem = validateSchedule(schedule, config.minIntervalMinutes);
  const targetOk = !deliveryNeedsTarget(delivery) || normalizeDeliveryTarget(delivery, deliveryTo) !== null;

  const localRuns = useMemo(
    () => (scheduleProblem === null ? nextRuns(schedule, new Date(), 3) : []),
    [schedule, scheduleProblem]
  );

  // A custom cron is previewed (and validated) by the server: a cron's grammar is not worth re-implementing here.
  useEffect(() => {
    if (schedule.kind !== "custom" || scheduleProblem !== null) {
      setServerPreview(null);
      setPreviewError(null);
      return undefined;
    }
    let live = true;
    const timer = setTimeout(() => {
      api
        .previewScheduledTaskSchedule({
          frequency: "custom",
          cron: schedule.cron.trim(),
          timezone: schedule.timezone.trim(),
        })
        .then((p: SchedulePreview) => {
          if (!live) return;
          setServerPreview(p);
          setPreviewError(null);
        })
        .catch((e: any) => {
          if (!live) return;
          setServerPreview(null);
          setPreviewError(String(e?.message ?? e));
        });
    }, 400);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [schedule.kind, schedule.cron, schedule.timezone, scheduleProblem]);

  const patch = (over: Partial<ScheduleDraft>) => setSchedule((s) => ({ ...s, ...over }));

  // Keep the day valid for the kind being switched to ("MON" is not a day of the month, "1" is not a weekday).
  const changeKind = (kind: ScheduleDraft["kind"]) =>
    setSchedule((s) => ({
      ...s,
      kind,
      day:
        kind === "monthly"
          ? MONTH_DAY_CHOICES.includes(s.day.toUpperCase())
            ? s.day.toUpperCase()
            : "1"
          : kind === "weekly"
            ? (WEEKDAY_CODES as readonly string[]).includes(s.day.toUpperCase())
              ? s.day.toUpperCase()
              : "MON"
            : s.day,
    }));

  const nameProblem = name.trim() ? null : "problem_nameRequired";
  const agentProblem = agentId ? null : "problem_agentRequired";
  const promptProblem = !prompt.trim()
    ? "problem_promptRequired"
    : prompt.trim().length > config.maxPromptChars
      ? "problem_promptTooLong"
      : null;
  const valid = !nameProblem && !agentProblem && !promptProblem && scheduleProblem === null && targetOk;

  const submit = async () => {
    setAttempted(true);
    if (!valid || busy) return;
    setBusy(true);
    setPlanLimit(null);
    try {
      const payload = buildPayload({ agentId, name, prompt, schedule, delivery, deliveryTo, maxRuns, expiresOn });
      const saved =
        editing && initial
          ? await api.updateScheduledTask(initial.id, payload)
          : await api.createScheduledTask(payload);
      toast.success(t(editing ? "saved" : "created"));
      onSaved(saved);
    } catch (e: any) {
      if (e?.status === 403) {
        setPlanLimit(String(e?.message ?? ""));
      }
      /* any other reason (minimum interval, a gated agent, ...) was already shown by the API layer */
    } finally {
      setBusy(false);
    }
  };

  const description = describeSchedule(schedule);
  const dayText =
    description.params.day === undefined
      ? ""
      : schedule.kind === "weekly"
        ? t(`day_${description.params.day}`)
        : description.params.day;
  const describeText = scheduleProblem === null ? t(description.key, { ...description.params, day: dayText }) : null;
  const previewRuns =
    schedule.kind === "custom" ? (serverPreview?.nextRuns ?? []) : localRuns.map((d) => d.toISOString());
  const previewZone = schedule.timezone.trim() || "UTC";

  return (
    <Card data-testid="scheduled-task-form">
      <CardContent className="flex flex-col gap-4 pt-4">
        <h2 className="text-sm font-semibold text-foreground">{t(editing ? "editTitle" : "createTitle")}</h2>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <Label htmlFor="scheduled-task-name">{t("nameLabel")}</Label>
            <Input
              id="scheduled-task-name"
              value={name}
              maxLength={120}
              placeholder={t("namePlaceholder")}
              autoComplete="off"
              onChange={(e) => setName(e.target.value)}
            />
            {attempted && nameProblem && <p className="mt-1 text-xs text-destructive">{t(nameProblem)}</p>}
          </div>
          <div>
            <Label htmlFor="scheduled-task-agent">{t("agentLabel")}</Label>
            <Select
              id="scheduled-task-agent"
              value={agentId}
              disabled={editing}
              onValueChange={setAgentId}
              placeholder={t("agentPlaceholder")}
              options={agents.map((a) => ({ value: a.id, label: a.name }))}
            />
            {agents.length === 0 && (
              <p className="mt-1 text-xs text-muted-foreground">
                {t("noAgentsHint")}{" "}
                <Link href="/agents" className="text-primary hover:underline">
                  {t("noAgentsLink")}
                </Link>
              </p>
            )}
            {attempted && agentProblem && <p className="mt-1 text-xs text-destructive">{t(agentProblem)}</p>}
          </div>
        </div>

        <div>
          <Label htmlFor="scheduled-task-prompt">{t("promptLabel")}</Label>
          <Textarea
            id="scheduled-task-prompt"
            value={prompt}
            rows={4}
            placeholder={t("promptPlaceholder")}
            onChange={(e) => setPrompt(e.target.value)}
          />
          <p className="mt-1 text-xs text-muted-foreground">
            {t("promptCount", { count: prompt.trim().length, max: config.maxPromptChars })}
          </p>
          {attempted && promptProblem && (
            <p className="mt-1 text-xs text-destructive">{t(promptProblem, { max: config.maxPromptChars })}</p>
          )}
        </div>

        <fieldset className="flex flex-col gap-3 rounded-md border border-border p-3">
          <legend className="px-1 text-xs font-medium text-muted-foreground">{t("scheduleLegend")}</legend>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <div>
              <Label htmlFor="scheduled-task-frequency">{t("frequencyLabel")}</Label>
              <Select
                id="scheduled-task-frequency"
                value={schedule.kind}
                onValueChange={(v) => changeKind(v as ScheduleDraft["kind"])}
                options={SCHEDULE_KINDS.map((k) => ({ value: k, label: t(`freq_${k}`) }))}
              />
            </div>

            {schedule.kind !== "custom" && (
              <div>
                <Label htmlFor="scheduled-task-time">{t("timeLabel")}</Label>
                <Input
                  id="scheduled-task-time"
                  type="time"
                  value={schedule.time}
                  onChange={(e) => patch({ time: e.target.value })}
                />
              </div>
            )}

            {schedule.kind === "weekly" && (
              <div>
                <Label htmlFor="scheduled-task-day">{t("dayLabel")}</Label>
                <Select
                  id="scheduled-task-day"
                  value={schedule.day}
                  onValueChange={(v) => patch({ day: v })}
                  options={WEEKDAY_CODES.map((d) => ({ value: d, label: t(`day_${d}`) }))}
                />
              </div>
            )}

            {schedule.kind === "monthly" && (
              <div>
                <Label htmlFor="scheduled-task-month-day">{t("monthDayLabel")}</Label>
                <Select
                  id="scheduled-task-month-day"
                  value={schedule.day.toUpperCase()}
                  onValueChange={(v) => patch({ day: v })}
                  options={MONTH_DAY_CHOICES.map((d) => ({ value: d, label: d === "LAST" ? t("monthDayLast") : d }))}
                />
              </div>
            )}

            {schedule.kind === "custom" && (
              <div className="md:col-span-2">
                <Label htmlFor="scheduled-task-cron">{t("cronLabel")}</Label>
                <Input
                  id="scheduled-task-cron"
                  value={schedule.cron}
                  placeholder="0 8 * * 1-5"
                  autoComplete="off"
                  spellCheck={false}
                  onChange={(e) => patch({ cron: e.target.value })}
                />
                <p className="mt-1 text-xs text-muted-foreground">
                  {t("cronHint", { min: config.minIntervalMinutes })}
                </p>
              </div>
            )}
          </div>

          <div>
            <Label htmlFor="scheduled-task-timezone">{t("timezoneLabel")}</Label>
            <Input
              id="scheduled-task-timezone"
              value={schedule.timezone}
              placeholder="Asia/Kolkata"
              autoComplete="off"
              spellCheck={false}
              list={zones.length > 0 ? "scheduled-task-timezones" : undefined}
              onChange={(e) => patch({ timezone: e.target.value })}
            />
            {zones.length > 0 && (
              <datalist id="scheduled-task-timezones">
                {zones.map((z) => (
                  <option key={z} value={z} />
                ))}
              </datalist>
            )}
          </div>

          {scheduleProblem !== null ? (
            <p className="text-xs text-destructive" role="alert" data-testid="scheduled-task-schedule-problem">
              {t(`problem_${scheduleProblem}`, { min: config.minIntervalMinutes })}
            </p>
          ) : (
            <div className="text-sm" data-testid="scheduled-task-preview">
              <p className="font-medium text-foreground">{describeText}</p>
              <p className="mt-1 text-xs text-muted-foreground">{t("previewHeading")}</p>
              {previewError ? (
                <p className="text-xs text-destructive" role="alert">
                  {previewError}
                </p>
              ) : previewRuns.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  {t(schedule.kind === "custom" ? "previewCustomPending" : "previewNone")}
                </p>
              ) : (
                <ol className="mt-1 list-decimal pl-5 text-xs text-muted-foreground">
                  {previewRuns.map((iso) => (
                    <li key={iso}>{formatRunTime(iso, previewZone)}</li>
                  ))}
                </ol>
              )}
            </div>
          )}
        </fieldset>

        <fieldset className="flex flex-col gap-3 rounded-md border border-border p-3">
          <legend className="px-1 text-xs font-medium text-muted-foreground">{t("deliveryLegend")}</legend>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <div>
              <Label htmlFor="scheduled-task-delivery">{t("deliveryLabel")}</Label>
              <Select
                id="scheduled-task-delivery"
                value={delivery}
                onValueChange={(v) => setDelivery(v as DeliveryChannel)}
                options={channels.map((c) => ({ value: c, label: t(`delivery_${c}`) }))}
              />
            </div>
            {deliveryNeedsTarget(delivery) && (
              <div>
                <Label htmlFor="scheduled-task-delivery-to">{t("deliveryToLabel")}</Label>
                <Input
                  id="scheduled-task-delivery-to"
                  value={deliveryTo}
                  placeholder={t(`toPlaceholder_${delivery}`)}
                  autoComplete="off"
                  onChange={(e) => setDeliveryTo(e.target.value)}
                />
                {attempted && !targetOk && (
                  <p className="mt-1 text-xs text-destructive">{t(`problem_to_${delivery}`)}</p>
                )}
              </div>
            )}
          </div>
          {deliveryNeedsTarget(delivery) && (
            <p className="text-xs text-muted-foreground">{t("deliveryApprovalNote")}</p>
          )}
        </fieldset>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <Label htmlFor="scheduled-task-max-runs">{t("maxRunsLabel")}</Label>
            <Input
              id="scheduled-task-max-runs"
              type="number"
              min={1}
              value={maxRuns}
              placeholder={t("maxRunsPlaceholder")}
              onChange={(e) => setMaxRuns(e.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="scheduled-task-expires">{t("expiresLabel")}</Label>
            <Input
              id="scheduled-task-expires"
              type="date"
              value={expiresOn}
              onChange={(e) => setExpiresOn(e.target.value)}
            />
          </div>
        </div>

        {planLimit !== null && (
          <div
            role="alert"
            data-testid="scheduled-task-plan-limit"
            className="flex flex-wrap items-center gap-3 rounded-md border border-[color-mix(in_srgb,var(--upgrade)_32%,transparent)] bg-[color-mix(in_srgb,var(--upgrade)_12%,transparent)] px-3 py-2 text-sm text-foreground"
          >
            <span className="min-w-0 flex-1">{planLimit}</span>
            {!isPersonalEdition() && (
              <Link
                href="/admin/billing"
                className="inline-flex shrink-0 items-center gap-1 font-semibold text-[var(--upgrade)] hover:underline"
              >
                <Sparkles size={12} aria-hidden="true" />
                {t("upgradeLink")}
              </Link>
            )}
          </div>
        )}

        <div className="flex gap-2">
          <Button type="button" size="sm" disabled={busy} onClick={() => void submit()}>
            {busy ? (
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            ) : (
              <Save size={14} aria-hidden="true" />
            )}{" "}
            {t(editing ? "saveChanges" : "save")}
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
            {t("cancel")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

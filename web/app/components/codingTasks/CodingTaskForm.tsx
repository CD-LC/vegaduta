"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Loader2, Play, Sparkles } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import {
  buildStartForm,
  validateDraft,
  type CodingTask,
  type CodingTaskDraft,
  type CodingTasksConfig,
} from "../../lib/codingTasks";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";

const SELECT_CLASS =
  "flex h-10 w-full min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] " +
  "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";

export interface AgentOption {
  id: string;
  name: string;
}

/**
 * Create form for a coding task: which agent's model writes the code, what to change, where the code comes from (a
 * public https git URL or an uploaded zip / tar.gz), and the test command (an allowlisted runner plus plain
 * arguments). Everything is validated again by the server, which is what actually enforces the runner allowlist,
 * the URL rules, the archive checks and the budget caps.
 */
export default function CodingTaskForm({
  config,
  agents,
  onStarted,
  onCancel,
}: {
  config: CodingTasksConfig;
  agents: AgentOption[];
  onStarted: (task: CodingTask) => void;
  onCancel: () => void;
}) {
  const t = useTranslations("codingTasks");
  const [draft, setDraft] = useState<CodingTaskDraft>({
    agentId: agents[0]?.id ?? "",
    instruction: "",
    source: "git",
    repoUrl: "",
    archive: null,
    testRunner: config.testRunners[0] ?? "",
    testArgs: "",
    maxMinutes: "",
  });
  const [attempted, setAttempted] = useState(false);
  const [busy, setBusy] = useState(false);
  // 403 (plan), 409 (a task is already running), 412 (no sandbox set up) and 429 (busy) arrive with a ready sentence.
  const [serverProblem, setServerProblem] = useState<{ status: number; message: string } | null>(null);

  // The agent list can arrive after the form opens: pick the first agent once it does.
  useEffect(() => {
    if (!draft.agentId && agents.length > 0) setDraft((d) => ({ ...d, agentId: agents[0].id }));
  }, [draft.agentId, agents]);

  const patch = (over: Partial<CodingTaskDraft>) => setDraft((d) => ({ ...d, ...over }));
  const problem = validateDraft(draft, config);

  const submit = async () => {
    setAttempted(true);
    if (problem || busy) return;
    setBusy(true);
    setServerProblem(null);
    try {
      const started = await api.startCodingTask(buildStartForm(draft));
      toast.success(t("started"));
      onStarted(started);
    } catch (e: any) {
      const status = Number(e?.status ?? 0);
      if (status) setServerProblem({ status, message: String(e?.message ?? "") });
      /* any other failure was already shown by the API layer */
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card data-testid="coding-task-form">
      <CardContent className="flex flex-col gap-4 pt-4">
        <h2 className="text-sm font-semibold text-foreground">{t("createTitle")}</h2>

        <div>
          <Label htmlFor="coding-task-agent">{t("agentLabel")}</Label>
          <select
            id="coding-task-agent"
            className={SELECT_CLASS}
            value={draft.agentId}
            onChange={(e) => patch({ agentId: e.target.value })}
          >
            {agents.length === 0 && <option value="">{t("agentPlaceholder")}</option>}
            {agents.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-muted-foreground">{t("agentHint")}</p>
          {attempted && problem === "problem_agentRequired" && (
            <p className="mt-1 text-xs text-destructive">{t(problem)}</p>
          )}
        </div>

        <div>
          <Label htmlFor="coding-task-instruction">{t("instructionLabel")}</Label>
          <Textarea
            id="coding-task-instruction"
            rows={4}
            value={draft.instruction}
            placeholder={t("instructionPlaceholder")}
            onChange={(e) => patch({ instruction: e.target.value })}
          />
          {attempted && (problem === "problem_instructionRequired" || problem === "problem_instructionTooLong") && (
            <p className="mt-1 text-xs text-destructive">{t(problem)}</p>
          )}
        </div>

        <fieldset className="flex flex-col gap-3">
          <legend className="text-sm font-medium text-foreground">{t("sourceLabel")}</legend>
          <div className="flex flex-wrap gap-4">
            <label className="flex items-center gap-2 text-sm text-foreground">
              <input
                type="radio"
                name="coding-task-source"
                checked={draft.source === "git"}
                onChange={() => patch({ source: "git" })}
              />
              {t("sourceGit")}
            </label>
            <label className="flex items-center gap-2 text-sm text-foreground">
              <input
                type="radio"
                name="coding-task-source"
                checked={draft.source === "archive"}
                onChange={() => patch({ source: "archive" })}
              />
              {t("sourceArchive")}
            </label>
          </div>
          {draft.source === "git" ? (
            <div>
              <Label htmlFor="coding-task-repo">{t("repoLabel")}</Label>
              <Input
                id="coding-task-repo"
                type="url"
                value={draft.repoUrl}
                placeholder="https://github.com/org/repo"
                autoComplete="off"
                onChange={(e) => patch({ repoUrl: e.target.value })}
              />
              <p className="mt-1 text-xs text-muted-foreground">{t("repoHint")}</p>
              {attempted && problem === "problem_repoUrl" && (
                <p className="mt-1 text-xs text-destructive">{t(problem)}</p>
              )}
            </div>
          ) : (
            <div>
              <Label htmlFor="coding-task-archive">{t("archiveLabel")}</Label>
              <Input
                id="coding-task-archive"
                type="file"
                accept=".zip,.tar,.tar.gz,.tgz,application/zip,application/gzip,application/x-tar"
                onChange={(e) => patch({ archive: e.target.files?.[0] ?? null })}
              />
              <p className="mt-1 text-xs text-muted-foreground">
                {t("archiveHint", { mb: Math.floor(config.maxArchiveBytes / (1024 * 1024)) })}
              </p>
              {attempted && (problem === "problem_archiveRequired" || problem === "problem_archiveTooLarge") && (
                <p className="mt-1 text-xs text-destructive">{t(problem)}</p>
              )}
            </div>
          )}
        </fieldset>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div>
            <Label htmlFor="coding-task-runner">{t("runnerLabel")}</Label>
            <select
              id="coding-task-runner"
              className={SELECT_CLASS}
              value={draft.testRunner}
              onChange={(e) => patch({ testRunner: e.target.value })}
            >
              {config.testRunners.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
            {attempted && problem === "problem_testRunner" && (
              <p className="mt-1 text-xs text-destructive">{t(problem)}</p>
            )}
          </div>
          <div className="md:col-span-2">
            <Label htmlFor="coding-task-args">{t("argsLabel")}</Label>
            <Input
              id="coding-task-args"
              value={draft.testArgs}
              placeholder={t("argsPlaceholder")}
              autoComplete="off"
              onChange={(e) => patch({ testArgs: e.target.value })}
            />
            <p className="mt-1 text-xs text-muted-foreground">{t("argsHint")}</p>
            {attempted && problem === "problem_testArgs" && (
              <p className="mt-1 text-xs text-destructive">{t(problem)}</p>
            )}
          </div>
        </div>

        <div className="max-w-xs">
          <Label htmlFor="coding-task-minutes">{t("minutesLabel")}</Label>
          <Input
            id="coding-task-minutes"
            type="number"
            min={1}
            max={Math.floor(config.caps.maxWallSeconds / 60)}
            value={draft.maxMinutes}
            placeholder={String(Math.round(config.defaults.maxWallSeconds / 60))}
            onChange={(e) => patch({ maxMinutes: e.target.value })}
          />
          <p className="mt-1 text-xs text-muted-foreground">
            {t("minutesHint", { max: Math.floor(config.caps.maxWallSeconds / 60) })}
          </p>
          {attempted && problem === "problem_minutes" && <p className="mt-1 text-xs text-destructive">{t(problem)}</p>}
        </div>

        <p className="text-xs text-muted-foreground">
          {t(config.networkEnabled ? "sandboxNoteNetwork" : "sandboxNote")}
        </p>

        {serverProblem !== null && (
          <div
            role="alert"
            data-testid="coding-task-problem"
            className="flex flex-wrap items-center gap-3 rounded-md border border-[color-mix(in_srgb,var(--upgrade)_32%,transparent)] bg-[color-mix(in_srgb,var(--upgrade)_12%,transparent)] px-3 py-2 text-sm text-foreground"
          >
            <span className="min-w-0 flex-1">{serverProblem.message}</span>
            {serverProblem.status === 403 && (
              <Link
                href="/admin/billing"
                className="inline-flex shrink-0 items-center gap-1 font-semibold text-[var(--upgrade)] hover:underline"
              >
                <Sparkles size={12} aria-hidden="true" />
                {t("upgradeLink")}
              </Link>
            )}
            {serverProblem.status === 412 && (
              <Link href="/providers" className="inline-flex shrink-0 items-center gap-1 font-semibold hover:underline">
                {t("providersLink")}
              </Link>
            )}
          </div>
        )}

        <div className="flex gap-2">
          <Button type="button" size="sm" disabled={busy} onClick={() => void submit()}>
            {busy ? (
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            ) : (
              <Play size={14} aria-hidden="true" />
            )}{" "}
            {t("start")}
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
            {t("cancel")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

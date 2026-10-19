"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Rocket, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Label } from "../ui/label";
import { Select } from "../ui/select";
import { useTranslations } from "../../lib/i18n";

// "Ask an agent for something and walk away" - the affordance that makes agent
// jobs reachable without an HTTP client.
//
// Deliberately NOT a chat box. Chat's contract is "wait for the reply"; this
// one's is "we have durably accepted the work and it is emphatically not done
// yet" - which is exactly why the server answers 202 rather than 200. So the
// submit button says Start, not Send, and success navigates to the job's own
// page instead of rendering a result inline: there is no result yet.

export default function JobLauncher({ agents, defaultAgentId }: { agents: any[]; defaultAgentId?: string | null }) {
  const router = useRouter();
  const t = useTranslations("jobsPage");
  const [agentId, setAgentId] = useState<string>(defaultAgentId || agents[0]?.id || "");
  const [goal, setGoal] = useState("");
  const [timeoutMinutes, setTimeoutMinutes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = !!agentId && goal.trim().length > 0 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      // timeoutSeconds is optional - omitted entirely rather than sent as a
      // guessed default, so the server's own AgentJobProperties default (and
      // its clamp) stays the single source of truth for how long a job may run.
      const minutes = Number(timeoutMinutes);
      const job = await api.submitAgentJob(agentId, {
        goal: goal.trim(),
        ...(timeoutMinutes && Number.isFinite(minutes) && minutes > 0
          ? { timeoutSeconds: Math.round(minutes * 60) }
          : {}),
      });
      router.push(`/jobs/${agentId}/${job.id}`);
    } catch (err: any) {
      setError(err?.message || t("startFailed"));
      setSubmitting(false);
    }
  }

  if (agents.length === 0) {
    return null;
  }

  return (
    <Card className="gap-4 p-5">
      <form onSubmit={submit} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_10rem]">
          <div className="space-y-1.5">
            <Label htmlFor="job-agent">{t("agentLabel")}</Label>
            <Select
              id="job-agent"
              value={agentId}
              onValueChange={setAgentId}
              options={agents.map((a: any) => ({ value: a.id, label: a.name }))}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="job-timeout">{t("timeLimitLabel")}</Label>
            <Input
              id="job-timeout"
              type="number"
              min={1}
              inputMode="numeric"
              placeholder={t("timeLimitPlaceholder")}
              value={timeoutMinutes}
              onChange={(e) => setTimeoutMinutes(e.target.value)}
              aria-describedby="job-timeout-hint"
            />
            <p id="job-timeout-hint" className="text-xs text-muted-foreground">
              {t("timeLimitHint")}
            </p>
          </div>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="job-goal">{t("goalLabel")}</Label>
          <Textarea
            id="job-goal"
            rows={4}
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            maxLength={8000}
            placeholder={t("goalPlaceholder")}
          />
          <p className="text-xs text-muted-foreground">{goal.length}/8000</p>
        </div>

        {error && (
          <div
            role="alert"
            className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {error}
          </div>
        )}

        <div className="flex items-center gap-3">
          <Button type="submit" disabled={!canSubmit}>
            {submitting ? (
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <Rocket className="size-4" aria-hidden="true" />
            )}
            {submitting ? t("starting") : t("startButton")}
          </Button>
          <p className="text-xs text-muted-foreground">
            {t("closeTabHint")}
          </p>
        </div>
      </form>
    </Card>
  );
}

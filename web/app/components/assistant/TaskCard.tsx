"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { AlertTriangle, Bot, Check, FileText, Loader2, MessageCircle, MessageSquareText, Phone, Wrench, Zap } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { errorMessage } from "../../lib/errors";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import MediaCard from "../MediaCard";
import WorkedForBlock from "./WorkedForBlock";
import type { AssistantTask, AssistantTurn, TaskChannel } from "./assistantTypes";

const CHANNELS: { key: TaskChannel; icon: typeof Phone }[] = [
  { key: "LOG", icon: FileText },
  { key: "WHATSAPP", icon: MessageCircle },
  { key: "SMS", icon: MessageSquareText },
  { key: "CALL", icon: Phone },
];

export interface TaskCardHandlers {
  onConfirm: (cardId: string) => void;
  onCancel: (cardId: string) => void;
  onChannel: (cardId: string, channel: TaskChannel) => void;
  /** Save the person's delivery number (PUT /api/account/phone); rejects with the reason if it fails. */
  onSavePhone: (phone: string) => Promise<void>;
}

/** The "add your number" prompt on a card that cannot deliver by WhatsApp / SMS / call yet. */
function PhonePrompt({ onSave, disabled }: { onSave: (phone: string) => Promise<void>; disabled: boolean }) {
  const t = useTranslations("assistant");
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave(draft.trim());
    } catch (err) {
      setError(errorMessage(err, t("taskPhoneSaveFailed")));
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-1.5">
      <p className="text-xs text-muted-foreground">{t("taskNeedsPhone")}</p>
      <Label htmlFor="task-card-phone" className="sr-only">
        {t("taskPhoneLabel")}
      </Label>
      <div className="flex gap-2">
        <Input
          id="task-card-phone"
          type="tel"
          autoComplete="tel"
          className="h-8 flex-1 text-xs"
          placeholder={t("taskPhonePlaceholder")}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          required
        />
        <Button type="submit" size="sm" disabled={disabled || saving || !draft.trim()}>
          {t("taskAddPhone")}
        </Button>
      </div>
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}

/**
 * A Quick Task in the transcript (the old /quick-task page's plan card, live trace and result card
 * folded into one entry): which agent will run it, the tools it detected, where the result goes,
 * Confirm / Cancel; then the work trace while it runs and the answer when it is done.
 */
export default function TaskCard({ turn, task, busy, handlers }: { turn: AssistantTurn; task: AssistantTask; busy: boolean; handlers: TaskCardHandlers }) {
  const t = useTranslations("assistant");
  const tools = task.preview?.detectedTools ?? [];
  const running = task.status === "running";
  return (
    <div className="w-full max-w-[85%] space-y-2 rounded-xl border border-border bg-card p-3 text-sm" data-testid="task-card" data-status={task.status}>
      <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        <Zap size={13} className="text-primary" aria-hidden="true" />
        {t("taskCardTitle")}
        {task.taskId && <span className="ml-auto font-mono text-[10px] normal-case tracking-normal">{task.taskId.slice(0, 8)}</span>}
      </div>

      {task.status === "previewing" && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" />
          {t("taskPlanning")}
        </p>
      )}

      {task.preview && task.status !== "failed" && (
        <dl className="grid gap-1.5 text-xs">
          <div className="flex items-start gap-2">
            <Bot size={14} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="min-w-0">
              <dt className="sr-only">{t("taskAgent")}</dt>
              <dd className="font-medium text-foreground">
                {task.preview.agentName || t("taskAgentUnknown")}
                {!task.agentId && <span className="ml-1 font-normal text-muted-foreground">{t("taskAutoPicked")}</span>}
              </dd>
              {(task.preview.providerName || task.preview.model) && (
                <dd className="text-muted-foreground">{[task.preview.providerName, task.preview.model].filter(Boolean).join(" · ")}</dd>
              )}
            </div>
          </div>
          <div className="flex items-start gap-2">
            <Wrench size={14} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="min-w-0">
              <dt className="sr-only">{t("taskTools")}</dt>
              <dd className="text-muted-foreground">
                {tools.length === 0 ? t("taskNoTools") : t("taskTools_n", { n: tools.length, names: tools.map((x) => x.name).join(", ") })}
              </dd>
            </div>
          </div>
        </dl>
      )}

      {task.status === "confirm" && (
        <>
          <div className="flex flex-wrap items-center gap-1" role="radiogroup" aria-label={t("taskDeliverLabel")}>
            {CHANNELS.map(({ key, icon: Icon }) => {
              const locked = key !== "LOG" && task.needsPhone;
              return (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={task.channel === key}
                  disabled={busy || locked}
                  title={locked ? t("taskNeedsPhone") : undefined}
                  onClick={() => handlers.onChannel(turn.id, key)}
                  className={cn(
                    "inline-flex h-7 items-center gap-1 rounded-full border px-2 text-xs",
                    task.channel === key ? "border-primary bg-primary/10 text-foreground" : "border-border text-muted-foreground",
                    locked && "opacity-50"
                  )}
                >
                  <Icon size={12} aria-hidden="true" />
                  {t(`taskChannel_${key}`)}
                </button>
              );
            })}
          </div>
          {task.needsPhone && <PhonePrompt onSave={handlers.onSavePhone} disabled={busy} />}
          {task.preview?.thirdPartyNumber && <p className="text-xs text-muted-foreground">{t("taskThirdParty", { number: task.preview.thirdPartyNumber })}</p>}
          <div className="flex items-center gap-2">
            <Button type="button" size="sm" onClick={() => handlers.onConfirm(turn.id)} disabled={busy} data-testid="task-confirm">
              <Check size={14} aria-hidden="true" />
              {t("taskConfirm")}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => handlers.onCancel(turn.id)} disabled={busy}>
              {t("taskCancel")}
            </Button>
          </div>
        </>
      )}

      {(running || task.status === "done") && <WorkedForBlock steps={turn.steps} startedAt={turn.startedAt} endedAt={running ? null : turn.endedAt} className="max-w-full" />}
      {running && !(turn.steps?.length ?? 0) && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" />
          {t("taskRunning")}
        </p>
      )}

      {task.status === "done" && (
        <div className="space-y-1">
          <MediaCard text={task.resultText || t("taskNoResult")} />
          {task.deliveryNote && <p className="text-xs text-muted-foreground">{task.deliveryNote}</p>}
          {task.taskId && (
            <Link href={`/quick-task/history`} className="text-xs text-muted-foreground underline-offset-2 hover:underline">
              {t("taskHistory")}
            </Link>
          )}
        </div>
      )}

      {task.status === "failed" && (
        <p className="flex items-start gap-2 text-destructive" role="alert">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
          {task.error || t("taskFailedPlain")}
        </p>
      )}
    </div>
  );
}

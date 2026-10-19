"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Label } from "../ui/label";
import Select from "../Select";
import type { AgentOption } from "./ChannelInspectorCards";
import { WHATSAPP_TASKS, qualityTone, whatsAppTaskLabelKey } from "./whatsappPolicy";

/**
 * "Which agent answers WhatsApp" plus the task declaration WhatsApp's Business Solution Terms make
 * relevant: a general-purpose AI assistant is restricted, a task-specific agent is not. The server
 * refuses a bind without a task (400) and answers an open-ended-looking agent with 409 until the
 * tenant confirms, which the server records as an override.
 */
export function WhatsAppBindingCard({
  agents,
  savedAgentId,
  loaded,
  busy,
  setBusy,
  setError,
  setSuccess,
  refreshStatus,
  t,
}: {
  agents: AgentOption[];
  savedAgentId?: string;
  loaded: boolean;
  busy: boolean;
  setBusy: (busy: boolean) => void;
  setError: (message: string) => void;
  setSuccess: (message: string) => void;
  refreshStatus: () => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  const [agentId, setAgentId] = useState("");
  const [task, setTask] = useState("");
  const [warning, setWarning] = useState<string[] | null>(null);

  useEffect(() => {
    if (savedAgentId) setAgentId(savedAgentId);
  }, [savedAgentId]);

  async function save(acknowledge: boolean) {
    if (!agentId || !task) return;
    setBusy(true);
    try {
      const result = await api.setWhatsAppDefaultAgent(agentId, task, acknowledge);
      if (result.ok) {
        setWarning(null);
        setSuccess(t("waDefaultAgentUpdatedSuccess"));
        refreshStatus();
      } else if (result.code === "WHATSAPP_OPEN_ENDED_AGENT") {
        setWarning(result.reasons ?? []);
      } else {
        setError(t("waTaskRequiredError"));
      }
    } catch (err) {
      setError(errorMessage(err, t("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }

  const savedAgent = savedAgentId ? agents.find((a) => a.id === savedAgentId) : undefined;

  return (
    <div className="w-full space-y-3 rounded-xl border border-border bg-muted/40 p-4 text-left" data-testid="wa-binding">
      <div className="flex items-center justify-between gap-2">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {t("inboundRoutingTitle")}
        </div>
        {loaded && savedAgent && (
          <Badge variant="secondary" className="max-w-[60%] truncate font-mono text-[10px]">
            {t("inboundRoutingRoutedTo", { agent: savedAgent.name })}
          </Badge>
        )}
      </div>

      <p className="rounded-md border border-[var(--warning-border)] bg-[var(--warning-bg)] p-2 text-xs text-[var(--warning-text)]">
        {t("waPolicyNotice")}
      </p>

      <form
        className="space-y-2"
        onSubmit={(e) => {
          e.preventDefault();
          void save(false);
        }}
      >
        <Label htmlFor="wa-route-agent" className="text-xs text-muted-foreground">
          {t("routeInboundToLabel")}
        </Label>
        <Select
          id="wa-route-agent"
          className="select-inline"
          value={agentId}
          onChange={(e) => {
            setAgentId(e.target.value);
            setWarning(null);
          }}
          options={[
            { value: "", label: t("chooseAgentPlaceholder") },
            ...agents.map((a) => ({ value: a.id, label: a.name })),
          ]}
        />
        <Label htmlFor="wa-route-task" className="text-xs text-muted-foreground">
          {t("waTaskLabel")}
        </Label>
        <div className="flex items-center gap-2">
          <Select
            id="wa-route-task"
            className="select-inline"
            value={task}
            onChange={(e) => setTask(e.target.value)}
            options={[
              { value: "", label: t("waTaskPlaceholder") },
              ...WHATSAPP_TASKS.map((v) => ({ value: v, label: t(whatsAppTaskLabelKey(v)) })),
            ]}
          />
          <Button type="submit" variant="outline" size="sm" disabled={busy || !agentId || !task}>
            {t("saveButton")}
          </Button>
        </div>
      </form>

      {warning && (
        <div
          role="alert"
          className="space-y-2 rounded-md border border-[var(--warning-border)] bg-[var(--warning-bg)] p-3 text-xs text-[var(--warning-text)]"
          data-testid="wa-open-ended-warning"
        >
          <div className="font-medium">{t("waOpenEndedTitle")}</div>
          <p>{t("waOpenEndedBody")}</p>
          {warning.length > 0 && (
            <ul className="list-disc pl-4">
              {warning.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          )}
          <div className="flex flex-wrap gap-2">
            <Button asChild size="sm" variant="outline">
              <Link href="/agents/templates?channel=whatsapp">{t("waUseTaskTemplate")}</Link>
            </Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void save(true)}>
              {t("waProceedAnyway")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Phone number quality as the Cloud API reports it; renders nothing when it does not. */
export function WhatsAppQualityCard({ t }: { t: (key: string) => string }) {
  const [quality, setQuality] = useState<{ rating: string | null; status: string | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .whatsAppQuality()
      .then((q) => {
        if (!cancelled && q?.available) setQuality({ rating: q.qualityRating, status: q.status });
      })
      .catch(() => {
        // Optional card: Twilio, a disconnected number or a token without the permission.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!quality) return null;
  const tone = qualityTone(quality.rating);
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border bg-muted/40 p-3 text-xs" data-testid="wa-quality">
      <span className="font-semibold uppercase tracking-wide text-muted-foreground">{t("waQualityTitle")}</span>
      <Badge variant={tone === "good" ? "secondary" : "outline"} className="font-mono text-[10px]">
        {quality.rating || t("waQualityUnknown")}
      </Badge>
      {quality.status && <span className="text-muted-foreground">{quality.status}</span>}
      <span className="text-muted-foreground">{t("waQualityHelp")}</span>
    </div>
  );
}

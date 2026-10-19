"use client";

import { Bot, MessageCircle, Send, ShieldCheck, Wrench } from "lucide-react";
import Select from "../Select";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import BrandText from "../BrandText";
import PoweredByChip, { inferSource } from "../PoweredByChip";
import { CHANNELS, type RunPower } from "./model";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Quick Task's plan card: the agent the preview resolved (with the agent
 * picker and the engine it will run on), the detected tools, the delivery
 * channel picker, and - once "Get it done" is pressed - the confirm step.
 * Nothing runs until `onConfirm`.
 */
export default function QuickTaskPlanCard({
  preview,
  agents,
  agentId,
  setAgentId,
  plannedPower,
  channel,
  setChannel,
  phoneNumber,
  attachmentCount,
  busy,
  showConfirm,
  onCancelConfirm,
  onConfirm,
}: {
  preview: any;
  agents: any[];
  agentId: string;
  setAgentId: (id: string) => void;
  plannedPower: RunPower | null;
  channel: string;
  setChannel: (channel: string) => void;
  phoneNumber: string | null;
  attachmentCount: number;
  busy: boolean;
  showConfirm: boolean;
  onCancelConfirm: () => void;
  onConfirm: () => void;
}) {
  const t = useTranslations("simpleTaskRunner");
  const ChannelIcon = CHANNELS.find((c) => c.key === channel)?.icon || MessageCircle;
  return (
    <Card className="gap-4 p-5">
      <div className="flex flex-wrap items-center gap-2">
        <Badge className="gap-1.5 border-primary/30 bg-primary/10 text-primary">
          <span className="size-1.5 rounded-full bg-primary" aria-hidden="true" />
          {t("taskReadyBadge")}
        </Badge>
        <span className="text-sm text-muted-foreground">
          <BrandText text={t("planLead")} />
        </span>
      </div>

      <div className="flex items-start gap-3">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Bot aria-hidden="true" size={15} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-foreground">
            {t("runsThroughPrefix")} <strong className="font-semibold">{preview.agentName}</strong>
            {!agentId && (
              <span className="text-muted-foreground">
                {" "}
                {t("autoSelectedSuffix")}
              </span>
            )}
          </div>
          {!agentId && <p className="mt-0.5 text-xs text-muted-foreground">{t("autoSelectRule")}</p>}
          {/* Pre-run engine disclosure - "Will run on", not
           * "powered by": nothing has executed yet, so this names
           * the agent's CONFIGURED engine (the same one the run
           * will actually use - the preview resolves the same
           * agent run() picks). */}
          {plannedPower && (
            <div className="mt-1">
              <PoweredByChip
                source={inferSource(plannedPower.providerName)}
                provider={plannedPower.providerName}
                model={plannedPower.model}
                prefix="Will run on"
                title={
                  inferSource(plannedPower.providerName) === "platform"
                    ? "This task will run on VegaDūta's hosted model pool - included with your workspace."
                    : "This task will run on a provider key configured by your workspace."
                }
              />
            </div>
          )}
          {/* "customers should be able to select the agents" - Auto-select
           * stays the default (empty value); picking a specific agent
           * re-runs the preview against it immediately. */}
          {agents.length > 1 && (
            <div className="mt-2 max-w-[220px]">
              <Select
                value={agentId}
                onChange={(e) => setAgentId(e.target.value)}
                options={[
                  { value: "", label: t("autoSelectRecommended") },
                  ...agents.map((a) => ({ value: a.id, label: a.name })),
                ]}
                disabled={busy}
              />
            </div>
          )}
        </div>
        <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("agentLabel")}
        </span>
      </div>

      <div className="flex items-start gap-3">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Wrench aria-hidden="true" size={14} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-foreground">
            {preview.detectedTools?.length > 0
              ? t("toolsDetected", {
                  count: preview.detectedTools.length,
                  plural: preview.detectedTools.length === 1 ? "" : "s",
                })
              : t("noExtraToolsNeeded")}
          </div>
          <div className="mt-0.5 text-xs text-muted-foreground">
            {preview.detectedTools?.length > 0
              ? preview.detectedTools.map((tool: { name: string }) => tool.name).join(", ")
              : t("justAgentsKnowledge")}
          </div>
        </div>
        <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("toolsLabel")}
        </span>
      </div>

      <div className="flex items-start gap-3">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
          <ChannelIcon size={14} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-foreground">
            {channel === "LOG"
              ? t("loggedOnlyDelivery")
              : preview.thirdPartyNumber
                ? t("sendingToApproval", { number: preview.thirdPartyNumber })
                : t("deliveredTo", { phoneNumber })}
          </div>
          {preview.thirdPartyNumber && (
            <div className="mt-0.5 text-xs text-muted-foreground">{t("differentNumberApprovalHint")}</div>
          )}
          {attachmentCount > 0 && (
            <div className="mt-0.5 text-xs text-muted-foreground">
              {t("attachedDocs", { count: attachmentCount, plural: attachmentCount === 1 ? "" : "s" })}
            </div>
          )}
          <div className="mt-2 flex flex-wrap gap-1.5">
            {CHANNELS.map((c) => {
              const Icon = c.icon;
              const active = channel === c.key;
              return (
                <Button
                  key={c.key}
                  type="button"
                  size="sm"
                  variant={active ? "default" : "outline"}
                  className="h-8 rounded-full"
                  aria-pressed={active}
                  onClick={() => setChannel(c.key)}
                  disabled={busy}
                >
                  <Icon size={13} strokeWidth={2.2} />
                  {t(c.labelKey)}
                </Button>
              );
            })}
          </div>
        </div>
        <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("deliveryLabel")}
        </span>
      </div>

      {/* Validate-before-run confirmation - "ask user how to trigger
       * it, validate it, ask him these are the tools available and
       * used to accomplish that." Appears only after Get it done is
       * pressed; the actual API call is the runner's confirmRun(). */}
      {showConfirm && (
        <div className="flex flex-wrap items-center gap-2 border-t border-border pt-4">
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <ShieldCheck aria-hidden="true" size={14} /> {t("confirmBeforeRunning")}
          </span>
          <div className="flex-1" />
          <Button variant="outline" onClick={onCancelConfirm}>
            {t("cancel")}
          </Button>
          <Button onClick={onConfirm}>
            <Send aria-hidden="true" size={14} /> {t("confirmAndRun")}
          </Button>
        </div>
      )}
    </Card>
  );
}

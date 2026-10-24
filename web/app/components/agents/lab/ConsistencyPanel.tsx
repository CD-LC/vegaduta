"use client";

// Agent Lab - the Consistency check: ask one question k times and see whether
// the answers mean the same thing. This file is the form (what to ask, how
// many samples, what it will cost) and the run lifecycle; the grouping is
// consistencyMath and the read-out is ConsistencyResults.
//
// AgentLab renders the scope note above this panel and keeps it mounted
// (hidden) across tab switches, so the form survives a look at the run graph.
import { useId, useMemo, useState, type ReactElement } from "react";
import { useTranslations } from "../../../lib/i18n";
import { Input } from "../../ui/input";
import { Select } from "../../ui/select";
import { Textarea } from "../../ui/textarea";
import ConsistencyResults from "./ConsistencyResults";
import { LabEstimate, LabRunButton, LabRunStatus } from "./LabRunControls";
import LabWithToolsToggle, { useLabWithTools } from "./LabWithToolsToggle";
import { buildConsistencyRequest, historyBefore, type ConsistencyContext } from "./consistencyPlan";
import { LAB_DEFAULT_MAX_CALLS, LAB_MAX_MESSAGE_CHARS, plannedCalls } from "./labApi";
import { clampText, listExchanges, type LabExchange } from "./labTurns";
import type { ConsistencyPanelProps } from "./types";
import { useLabRun } from "./useLabRun";

const MIN_SAMPLES = 3;
const MAX_SAMPLES = 8;
const DEFAULT_SAMPLES = 5;
const DEFAULT_TTL_MINUTES = 60;
const TURN_PREVIEW_CHARS = 72;

/** A turn can be sampled when its question reached the server: the reply
 * carries a server ref and the user message is not blank. */
function canSample(exchange: LabExchange): boolean {
  return exchange.isServer && exchange.user != null && exchange.user.content.trim() !== "";
}

export default function ConsistencyPanel({ agent, turns, capabilities }: ConsistencyPanelProps): ReactElement {
  const t = useTranslations("agentLab");
  const lab = useLabRun<ConsistencyContext>(agent.id, "CONSISTENCY");
  const tools = useLabWithTools(lab, capabilities);
  const fieldId = useId();

  const exchanges = useMemo(() => listExchanges(turns).filter(canSample), [turns]);
  const [source, setSource] = useState<"turn" | "custom">(() => (exchanges.length > 0 ? "turn" : "custom"));
  // null follows the latest turn; a number pins the exchange whose user
  // message sits at that transcript index (until the transcript loses it).
  const [pinnedUserIndex, setPinnedUserIndex] = useState<number | null>(null);
  const [includeHistory, setIncludeHistory] = useState(true);
  const [custom, setCustom] = useState("");
  const [samplesDraft, setSamplesDraft] = useState(String(DEFAULT_SAMPLES));

  const selected =
    exchanges.find((exchange) => exchange.userIndex === pinnedUserIndex) ?? exchanges[exchanges.length - 1] ?? null;
  const priorMessages = useMemo(
    () => (selected ? historyBefore(turns, selected.userIndex) : []),
    [turns, selected]
  );

  const maxSamples = Math.max(MIN_SAMPLES, Math.min(MAX_SAMPLES, capabilities?.maxSimilarityCells ?? MAX_SAMPLES));
  const parsedSamples = Number.parseInt(samplesDraft, 10);
  const k = Math.min(maxSamples, Math.max(MIN_SAMPLES, Number.isFinite(parsedSamples) ? parsedSamples : DEFAULT_SAMPLES));

  const fromTurn = source === "turn";
  const message = (fromTurn ? selected?.user?.content ?? "" : custom).trim();
  const usedTools = fromTurn && (selected?.assistant?.steps ?? []).some((step) => step.type === "tool_call");
  const ctx: ConsistencyContext = {
    message,
    history: fromTurn && includeHistory ? priorMessages : [],
    k,
    usedTools,
    // Only present when set, so the stored context of an ordinary run is unchanged.
    ...(tools.withTools ? { withTools: true } : {}),
  };
  const request = buildConsistencyRequest(ctx);
  // Nothing to ask yet = nothing to run, whatever k says.
  const calls = message ? plannedCalls(request) : 0;
  const cap = capabilities?.maxCallsPerRun ?? LAB_DEFAULT_MAX_CALLS;

  // A playground message can be longer than a lab run accepts (the textarea
  // cannot: maxLength). Say so here rather than let core answer 400.
  const tooLong = message.length > LAB_MAX_MESSAGE_CHARS;
  const blockedReason = tooLong ? t("koItemTooLong", { n: 1, max: LAB_MAX_MESSAGE_CHARS }) : undefined;
  // "lost" counts as live: the run may well still be going server-side, and
  // a second start would only be refused (one live run per user).
  const live = lab.phase === "starting" || lab.phase === "polling" || lab.phase === "lost";
  // A verdict is only shown for a run that has stopped changing: while it is
  // live the comparisons are still arriving, and a half-filled matrix would
  // read as "Inconclusive".
  const settled = lab.phase === "done" || lab.phase === "expired";

  return (
    <div className="space-y-5">
      <p className="text-sm text-foreground">{t("ccIntro")}</p>

      <div className="max-w-2xl space-y-4">
        <div role="radiogroup" aria-label={t("ccQuestionLabel")} className="flex flex-wrap gap-x-5 gap-y-2">
          <label className="flex items-center gap-2 text-sm text-foreground">
            <input
              type="radio"
              name={`${fieldId}-source`}
              checked={fromTurn}
              onChange={() => setSource("turn")}
            />
            {t("ccSourceTurn")}
          </label>
          <label className="flex items-center gap-2 text-sm text-foreground">
            <input
              type="radio"
              name={`${fieldId}-source`}
              checked={!fromTurn}
              onChange={() => setSource("custom")}
            />
            {t("ccSourceCustom")}
          </label>
        </div>

        {fromTurn ? (
          selected ? (
            <div className="space-y-2">
              <label htmlFor={`${fieldId}-turn`} className="block text-xs font-medium text-foreground">
                {t("ccTurnLabel")}
              </label>
              <Select
                id={`${fieldId}-turn`}
                value={String(selected.userIndex)}
                onValueChange={(v) => setPinnedUserIndex(Number(v))}
                options={exchanges.map((exchange) => ({
                  value: String(exchange.userIndex),
                  label: t("graphTurnOption", {
                    n: exchange.n,
                    preview: clampText(exchange.user?.content.trim() ?? "", TURN_PREVIEW_CHARS),
                  }),
                }))}
              />
              {priorMessages.length > 0 && (
                <label className="flex items-center gap-2 text-xs text-foreground">
                  <input
                    type="checkbox"
                    checked={includeHistory}
                    onChange={(e) => setIncludeHistory(e.target.checked)}
                  />
                  {t("ccIncludeHistory", { count: priorMessages.length })}
                </label>
              )}
              {usedTools && !tools.withTools && <p className="text-xs text-muted-foreground">{t("ccToolsWarning")}</p>}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("ccNoTurns")}</p>
          )
        ) : (
          <div className="space-y-2">
            <label htmlFor={`${fieldId}-question`} className="block text-xs font-medium text-foreground">
              {t("ccQuestionLabel")}
            </label>
            <Textarea
              id={`${fieldId}-question`}
              rows={3}
              maxLength={LAB_MAX_MESSAGE_CHARS}
              placeholder={t("ccQuestionPlaceholder")}
              value={custom}
              onChange={(e) => setCustom(e.target.value)}
            />
          </div>
        )}

        <div className="space-y-2">
          <label htmlFor={`${fieldId}-samples`} className="block text-xs font-medium text-foreground">
            {t("ccSamplesLabel")}
          </label>
          <Input
            id={`${fieldId}-samples`}
            type="number"
            inputMode="numeric"
            className="w-24"
            min={MIN_SAMPLES}
            max={maxSamples}
            step={1}
            value={samplesDraft}
            onChange={(e) => setSamplesDraft(e.target.value)}
            // The draft may be out of range (or empty) mid-edit; `k` is always
            // the clamped value, and leaving the field shows what will run.
            onBlur={() => setSamplesDraft(String(k))}
          />
          {agent.temperature === 0 && <p className="text-xs text-muted-foreground">{t("ccZeroTempNote")}</p>}
        </div>

        <LabWithToolsToggle state={tools} disabled={live} />

        <LabEstimate
          calls={calls}
          cap={cap}
          breakdown={calls > 0 ? t("ccBreakdown", { samples: k, comparisons: (k * (k - 1)) / 2 }) : undefined}
        />
        <div className="space-y-1">
          <LabRunButton
            label={t("ccRun")}
            calls={calls}
            capabilities={capabilities}
            disabled={tooLong || live}
            disabledReason={blockedReason}
            onRun={() => void lab.start(tools.withTools ? { ...request, withTools: true } : request, ctx)}
          />
          {blockedReason && (
            <p className="text-xs text-destructive" role="alert">
              {blockedReason}
            </p>
          )}
        </div>
      </div>

      <LabRunStatus lab={lab} ttlMinutes={capabilities?.runTtlMinutes ?? DEFAULT_TTL_MINUTES} />

      {lab.run && settled && <ConsistencyResults key={lab.run.runId} run={lab.run} context={lab.context} />}
    </div>
  );
}

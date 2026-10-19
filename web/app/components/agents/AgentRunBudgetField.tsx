"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import {
  formatRunBudgetUsd,
  normalizeRunBudget,
  runBudgetInputText,
  validateRunBudgetInput,
  type AgentRunBudget,
} from "./agentRunBudget";

/**
 * "Spending cap per run" for one agent (trust T5), mounted in the agent
 * workbench under the autonomy selector.
 *
 * Reads and writes core's /api/agents/{id}/run-budget. What the cap really
 * does is narrower than the name suggests, and the copy says so: it bounds
 * team/delegation tools and deep research within one turn, measured as an
 * estimate from text length. It is not billing and it does not bound an
 * ordinary single-agent tool loop.
 *
 * Self-hiding: an older core has no such route (404), and a deployment can
 * switch per-agent caps off (overrideEnabled false). Either way nothing
 * renders - an absent optional control must not look like an error.
 */
export default function AgentRunBudgetField({
  agentId,
  locked = false,
  lockedTooltip,
}: {
  agentId: string | null | undefined;
  /** The agent is edit-locked (free-tier lock); the form is read-only. */
  locked?: boolean;
  lockedTooltip?: string;
}) {
  const t = useTranslations("agentRunBudget");
  // Keyed by the agent it was fetched for, so switching agents can never show
  // (or save against) the previous agent's cap while the new fetch is in flight.
  const [loaded, setLoaded] = useState<{ agentId: string; budget: AgentRunBudget } | null>(null);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "error" | "saved"; text: string } | null>(null);

  useEffect(() => {
    setMessage(null);
    if (!agentId) {
      setLoaded(null);
      return undefined;
    }
    let cancelled = false;
    const requestedFor: string = agentId;
    api
      .getAgentRunBudget(requestedFor)
      .then((raw: unknown) => {
        if (cancelled) return;
        const budget = normalizeRunBudget(raw);
        setLoaded(budget ? { agentId: requestedFor, budget } : null);
        setText(budget ? runBudgetInputText(budget.agentCapUsd) : "");
      })
      .catch(() => {
        // 404 (older core / agent not visible) or a transient failure: hide.
        if (!cancelled) setLoaded(null);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const budget = loaded && loaded.agentId === agentId ? loaded.budget : null;
  if (!agentId || !budget || !budget.overrideEnabled) return null;

  const disabled = busy || locked;
  const validation = validateRunBudgetInput(text, budget.maxAgentCapUsd);
  const validationError = "error" in validation ? validation.error : null;
  const unchanged = validation.ok && budget.agentCapUsd != null && validation.value === budget.agentCapUsd;
  // An empty box is not an error to shout about - Save is simply unavailable.
  const inlineError =
    validationError == null || validationError === "empty"
      ? null
      : validationError === "aboveMax" && budget.maxAgentCapUsd != null
        ? t("errorAboveMax", { amount: formatRunBudgetUsd(budget.maxAgentCapUsd) })
        : t(
            validationError === "notPositive"
              ? "errorNotPositive"
              : validationError === "tooManyDecimals"
                ? "errorTooManyDecimals"
                : validationError === "tooLarge"
                  ? "errorTooLarge"
                  : "errorNotNumber"
          );

  async function submit(value: number | null) {
    if (!agentId || disabled) return;
    const requestedFor: string = agentId;
    setBusy(true);
    setMessage(null);
    try {
      const next = normalizeRunBudget(await api.setAgentRunBudget(requestedFor, value));
      if (next) {
        setLoaded({ agentId: requestedFor, budget: next });
        setText(runBudgetInputText(next.agentCapUsd));
      }
      setMessage({ kind: "saved", text: value == null ? t("cleared") : t("saved") });
    } catch (err: any) {
      setMessage({ kind: "error", text: err?.message || t("saveFailed") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mb-4" data-testid="agent-run-budget">
      <label htmlFor="agent-run-budget-input" className="mb-1 block text-xs text-muted-foreground">
        {t("label")}
      </label>
      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (validation.ok && !unchanged) void submit(validation.value);
        }}
      >
        <Input
          id="agent-run-budget-input"
          data-testid="agent-run-budget-input"
          className="w-36"
          inputMode="decimal"
          autoComplete="off"
          value={text}
          placeholder={t("placeholder")}
          disabled={disabled}
          title={locked ? lockedTooltip : undefined}
          aria-invalid={inlineError ? true : undefined}
          aria-describedby="agent-run-budget-facts"
          onChange={(e) => {
            setText(e.target.value);
            setMessage(null);
          }}
        />
        <Button
          type="submit"
          size="sm"
          data-testid="agent-run-budget-save"
          disabled={disabled || !validation.ok || unchanged}
          title={locked ? lockedTooltip : undefined}
        >
          {t("save")}
        </Button>
        {budget.agentCapUsd != null && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            data-testid="agent-run-budget-clear"
            disabled={disabled}
            title={locked ? lockedTooltip : undefined}
            onClick={() => void submit(null)}
          >
            {t("clear")}
          </Button>
        )}
      </form>
      {inlineError && (
        <p className="mt-1 text-xs text-destructive" role="alert" data-testid="agent-run-budget-invalid">
          {inlineError}
        </p>
      )}
      {message && (
        <p
          className={message.kind === "error" ? "mt-1 text-xs text-destructive" : "mt-1 text-xs text-muted-foreground"}
          role={message.kind === "error" ? "alert" : "status"}
          data-testid={`agent-run-budget-${message.kind}`}
        >
          {message.text}
        </p>
      )}
      <ul id="agent-run-budget-facts" className="mt-2 space-y-0.5 text-xs text-muted-foreground">
        <li data-testid="agent-run-budget-effective">
          {budget.effectiveCapUsd > 0
            ? t("effective", { amount: formatRunBudgetUsd(budget.effectiveCapUsd) })
            : t("effectiveNone")}
        </li>
        <li data-testid="agent-run-budget-platform">
          {budget.globalCapUsd > 0
            ? t("platformCap", { amount: formatRunBudgetUsd(budget.globalCapUsd) })
            : budget.maxAgentCapUsd != null
              ? t("platformNoneWithMax", { amount: formatRunBudgetUsd(budget.maxAgentCapUsd) })
              : t("platformNone")}
        </li>
        <li data-testid="agent-run-budget-estimate">
          {t("perBranch", { amount: formatRunBudgetUsd(budget.perBranchEstimateUsd) })}
        </li>
      </ul>
      <p className="mt-2 text-xs text-muted-foreground">{t("scopeNote")}</p>
    </div>
  );
}

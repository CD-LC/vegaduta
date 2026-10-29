"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Check, Loader2, X } from "lucide-react";
import { api } from "../../lib/api";
import type { AgentSamplingFields } from "../../lib/api/agents";
import { useTranslations } from "../../lib/i18n";
import { normalizeSamplingParam, type SamplingParam } from "./playground/runSettingsModel";

// Advanced sampling controls for one agent: top-K, stop sequences, frequency /
// presence penalty and seed. Saves through PATCH /api/agents/{id}/generation-params
// (the same call as the max-tokens / temperature / top-p sliders, which are
// re-sent unchanged because that endpoint takes them together).

export const MAX_STOP_SEQUENCES = 8;
export const MAX_STOP_LENGTH = 64;

type SaveState = "idle" | "saving" | "saved" | "error";

interface AgentLike extends AgentSamplingFields {
  id: string;
  maxTokens?: number;
  temperature?: number;
  topP?: number;
}

export interface AgentSamplingControlsProps {
  agent: AgentLike;
  /** Shows the controls but makes them non-interactive. */
  disabled?: boolean;
  /** Line shown when disabled, e.g. the edit-lock explanation. */
  disabledReason?: string;
  /** Called with the updated agent returned by the server after each save. */
  onSaved?: (updated: any) => void;
  /**
   * Parameter names (any common spelling: top_k, topK...) the agent's provider ignores for its model, from the
   * model catalog. A field in this list shows a warning that changing it has no effect. Absent or empty: no
   * warning is shown - nothing is guessed.
   */
  ignoredParams?: readonly string[] | null;
}

/** Parses a numeric text field. "" -> null (clears); invalid -> undefined. */
export function parseBounded(text: string, min: number, max: number, integer: boolean): number | null | undefined {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const n = Number(trimmed);
  if (!Number.isFinite(n) || n < min || n > max) return undefined;
  if (integer && !Number.isInteger(n)) return undefined;
  return n;
}

function fieldText(v: number | null | undefined): string {
  return v === null || v === undefined ? "" : String(v);
}

type NumKey = "topK" | "frequencyPenalty" | "presencePenalty" | "seed";

export default function AgentSamplingControls({
  agent,
  disabled,
  disabledReason,
  onSaved,
  ignoredParams,
}: AgentSamplingControlsProps) {
  const t = useTranslations("agentWorkbench");
  const uid = useId();
  const ignored = new Set<SamplingParam>(
    (ignoredParams ?? []).map((n) => normalizeSamplingParam(n)).filter((p): p is SamplingParam => p !== null)
  );
  const [state, setState] = useState<Record<string, SaveState>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [topK, setTopK] = useState(fieldText(agent.topK));
  const [freq, setFreq] = useState(fieldText(agent.frequencyPenalty));
  const [pres, setPres] = useState(fieldText(agent.presencePenalty));
  const [seed, setSeed] = useState(fieldText(agent.seed));
  const [stops, setStops] = useState<string[]>(agent.stopSequences ?? []);
  const [stopDraft, setStopDraft] = useState("");
  const timers = useRef<number[]>([]);

  // Re-sync when the parent hands us a different or freshly saved agent.
  const stopsKey = JSON.stringify(agent.stopSequences ?? []);
  useEffect(() => {
    setTopK(fieldText(agent.topK));
    setFreq(fieldText(agent.frequencyPenalty));
    setPres(fieldText(agent.presencePenalty));
    setSeed(fieldText(agent.seed));
    setStops(agent.stopSequences ?? []);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id, agent.topK, agent.frequencyPenalty, agent.presencePenalty, agent.seed, stopsKey]);

  useEffect(() => {
    const t = timers.current;
    return () => t.forEach((id) => window.clearTimeout(id));
  }, []);

  async function save(key: string, patch: AgentSamplingFields) {
    if (disabled) return;
    setState((s) => ({ ...s, [key]: "saving" }));
    setMessage(null);
    const clears = (Object.keys(patch) as (keyof AgentSamplingFields)[]).some(
      (k) => k !== "stopSequences" && patch[k] === null && agent[k] != null
    );
    try {
      const updated = await api.setAgentGenerationParams(agent.id, {
        ...(clears ? { clearSamplingParams: true } : {}),
        maxTokens: agent.maxTokens ?? null,
        temperature: agent.temperature ?? null,
        topP: agent.topP ?? null,
        topK: agent.topK ?? null,
        stopSequences: agent.stopSequences ?? [],
        frequencyPenalty: agent.frequencyPenalty ?? null,
        presencePenalty: agent.presencePenalty ?? null,
        seed: agent.seed ?? null,
        ...patch,
      });
      setState((s) => ({ ...s, [key]: "saved" }));
      timers.current.push(window.setTimeout(() => setState((s) => ({ ...s, [key]: "idle" })), 2000));
      onSaved?.(updated);
    } catch (e: unknown) {
      setState((s) => ({ ...s, [key]: "error" }));
      setMessage(e instanceof Error && e.message ? e.message : t("samplingSaveFailed"));
    }
  }

  function clearError(key: string) {
    setState((s) => (s[key] === "error" ? { ...s, [key]: "idle" } : s));
    setMessage(null);
  }

  function commitNumber(
    key: NumKey,
    text: string,
    min: number,
    max: number,
    integer: boolean,
    current: number | null | undefined,
    reset: (v: string) => void,
    label: string
  ) {
    const parsed = parseBounded(text, min, max, integer);
    if (parsed === undefined) {
      setState((s) => ({ ...s, [key]: "error" }));
      setMessage(t(integer ? "samplingErrorWhole" : "samplingErrorNumber", { label, min, max }));
      reset(fieldText(current));
      return;
    }
    if (parsed === (current ?? null)) return;
    void save(key, { [key]: parsed });
  }

  function addStop() {
    const v = stopDraft;
    if (v.trim() === "") return;
    if (v.length > MAX_STOP_LENGTH) {
      setMessage(t("samplingStopTooLong", { max: MAX_STOP_LENGTH }));
      return;
    }
    if (stops.length >= MAX_STOP_SEQUENCES) {
      setMessage(t("samplingStopTooMany", { max: MAX_STOP_SEQUENCES }));
      return;
    }
    if (stops.includes(v)) {
      setStopDraft("");
      return;
    }
    const next = [...stops, v];
    setStops(next);
    setStopDraft("");
    void save("stopSequences", { stopSequences: next });
  }

  function removeStop(v: string) {
    const next = stops.filter((s) => s !== v);
    setStops(next);
    void save("stopSequences", { stopSequences: next });
  }

  const inputCls =
    "w-full rounded-md border border-border bg-card px-3 py-2 text-sm text-foreground tabular-nums outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:opacity-60";

  const status = (key: string) => {
    const s = state[key] ?? "idle";
    return (
      <span className="inline-flex h-4 items-center text-[11px] text-muted-foreground" role="status" aria-live="polite">
        {s === "saving" && (
          <>
            <Loader2 className="me-1 size-3 animate-spin" aria-hidden="true" />
            {t("samplingSaving")}
          </>
        )}
        {s === "saved" && (
          <>
            <Check className="me-1 size-3" aria-hidden="true" />
            {t("samplingSaved")}
          </>
        )}
        {s === "error" && <span className="text-destructive">{t("samplingNotSaved")}</span>}
      </span>
    );
  };

  const numberField = (
    key: NumKey,
    label: string,
    help: string,
    value: string,
    setValue: (v: string) => void,
    current: number | null | undefined,
    min: number,
    max: number,
    integer: boolean,
    placeholder: string
  ) => {
    const id = `${uid}-${key}`;
    return (
      <div className="min-w-0">
        <div className="mb-1 flex items-center justify-between gap-2">
          <label htmlFor={id} className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
            {label}
          </label>
          {status(key)}
        </div>
        <input
          id={id}
          type="text"
          inputMode={integer ? "numeric" : "decimal"}
          className={inputCls}
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          aria-describedby={`${id}-help`}
          onChange={(e) => {
            setValue(e.target.value);
            clearError(key);
          }}
          onFocus={() => clearError(key)}
          onBlur={() => commitNumber(key, value, min, max, integer, current, setValue, label)}
          onKeyDown={(e) => {
            if (e.key === "Enter") (e.target as HTMLInputElement).blur();
          }}
        />
        <p id={`${id}-help`} className="mt-1 text-[12.5px] text-muted-foreground">
          {help}
        </p>
        {ignored.has(key) && (
          <p className="mt-1 text-[12.5px] text-[var(--state-warning)]" data-testid={`sampling-ignored-${key}`}>
            {t("samplingIgnored")}
          </p>
        )}
      </div>
    );
  };

  return (
    <section
      aria-label={t("samplingTitle")}
      data-testid="agent-sampling-controls"
      className={disabled ? "opacity-70" : ""}
    >
      <p className="text-[12.5px] text-muted-foreground">{t("samplingIntro")}</p>
      {disabled && disabledReason && <p className="mt-2 text-[12.5px] text-muted-foreground">{disabledReason}</p>}

      <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
        {numberField(
          "topK",
          t("samplingTopK"),
          t("samplingTopKHelp"),
          topK,
          setTopK,
          agent.topK,
          1,
          1000,
          true,
          t("samplingPlaceholderDefault")
        )}
        {numberField(
          "seed",
          t("samplingSeed"),
          t("samplingSeedHelp"),
          seed,
          setSeed,
          agent.seed,
          -2147483648,
          2147483647,
          true,
          t("samplingPlaceholderRandom")
        )}
        {numberField(
          "frequencyPenalty",
          t("samplingFrequency"),
          t("samplingFrequencyHelp"),
          freq,
          setFreq,
          agent.frequencyPenalty,
          -2,
          2,
          false,
          t("samplingPlaceholderZero")
        )}
        {numberField(
          "presencePenalty",
          t("samplingPresence"),
          t("samplingPresenceHelp"),
          pres,
          setPres,
          agent.presencePenalty,
          -2,
          2,
          false,
          t("samplingPlaceholderZero")
        )}
      </div>

      <div className="mt-4">
        <div className="mb-1 flex items-center justify-between gap-2">
          <label
            htmlFor={`${uid}-stop`}
            className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground"
          >
            {t("samplingStop")}
          </label>
          {status("stopSequences")}
        </div>
        <div className="flex gap-2">
          <input
            id={`${uid}-stop`}
            type="text"
            className={inputCls}
            value={stopDraft}
            maxLength={MAX_STOP_LENGTH}
            placeholder={t("samplingStopPlaceholder")}
            disabled={disabled || stops.length >= MAX_STOP_SEQUENCES}
            aria-describedby={`${uid}-stop-help`}
            onChange={(e) => setStopDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addStop();
              }
            }}
          />
          <button
            type="button"
            className="shrink-0 rounded-md border border-border bg-card px-3 py-2 text-sm hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
            disabled={disabled || stopDraft.trim() === "" || stops.length >= MAX_STOP_SEQUENCES}
            onClick={addStop}
          >
            {t("samplingAdd")}
          </button>
        </div>
        <p id={`${uid}-stop-help`} className="mt-1 text-[12.5px] text-muted-foreground">
          {t("samplingStopHelp", { count: MAX_STOP_SEQUENCES, max: MAX_STOP_LENGTH })}
        </p>
        {ignored.has("stopSequences") && (
          <p className="mt-1 text-[12.5px] text-[var(--state-warning)]" data-testid="sampling-ignored-stopSequences">
            {t("samplingIgnored")}
          </p>
        )}
        {stops.length > 0 && (
          <ul className="mt-2 flex flex-wrap gap-2" aria-label={t("samplingStopCurrent")}>
            {stops.map((s) => (
              <li
                key={s}
                className="inline-flex max-w-full items-center gap-1 rounded-full border border-border bg-background px-2 py-1 text-[12.5px]"
              >
                <code className="truncate">{JSON.stringify(s).slice(1, -1)}</code>
                <button
                  type="button"
                  aria-label={t("samplingStopRemove", { value: s })}
                  className="rounded-full p-0.5 hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
                  disabled={disabled}
                  onClick={() => removeStop(s)}
                >
                  <X className="size-3" aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {message && (
        <div role="alert" className="ax-kb-error mt-3">
          {message}
        </div>
      )}
    </section>
  );
}

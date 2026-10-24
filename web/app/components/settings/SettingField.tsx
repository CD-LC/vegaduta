"use client";

import { useId, useState, type KeyboardEvent, type ReactNode } from "react";
import { History, Lock, RotateCcw, ShieldAlert, TriangleAlert } from "lucide-react";
import type { EffectiveSetting, SettingCatalogEntry, SettingScope, SettingsFieldError } from "../../lib/api/settings";
import { cn } from "../../lib/utils";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import { Switch } from "../ui/switch";
import { Textarea } from "../ui/textarea";
import SourceBadge from "./SourceBadge";
import {
  DURATION_UNITS,
  bestDurationUnit,
  catalogDurationUnit,
  convertDuration,
  draftFromValue,
  isDirty,
  numericValue,
  overrideSource,
  parseDraft,
  type Draft,
  type DurationUnit,
  type ReadOnlyReason,
  type ValidationError,
} from "./settingsModel";
import { useSettingsText, type SettingsText } from "./useSettingsText";

export interface SettingFieldProps {
  entry: SettingCatalogEntry;
  /** What the server says is in force right now; absent means "never loaded", treated as the catalog default. */
  effective?: EffectiveSetting;
  /** The value the setting returns to if its override is removed (shown next to the reset button). */
  inheritedValue?: unknown;
  scope: SettingScope;
  /** The in-progress edit; undefined when the field is showing the saved value. */
  draft?: Draft;
  onDraftChange: (key: string, draft: Draft | undefined) => void;
  /** Remove the override. Resolve true when it worked so focus can return to the control. */
  onReset: (key: string) => Promise<boolean> | boolean | void;
  onOpenHistory: (key: string) => void;
  /** Failure of the last save/reset of this key, from the server. */
  error?: SettingsFieldError | null;
  pending?: boolean;
  /** Why the viewer cannot edit (none = editable). */
  blocked?: ReadOnlyReason;
}

const round6 = (n: number) => Math.round(n * 1e6) / 1e6;

function validationMessage(ui: SettingsText["ui"], text: SettingsText, entry: SettingCatalogEntry, err: ValidationError): string {
  const unit = entry.type === "string" || entry.type === "text" ? text.unitLabel("chars") : entry.type === "percent" ? "%" : text.unitLabel(entry.unit);
  const fmt = (n?: number) => (n == null ? "" : `${n}${unit ? ` ${unit}` : ""}`.trim());
  switch (err.code) {
    case "belowMin":
      return ui("errors.belowMin", { min: fmt(err.min) });
    case "aboveMax":
      return ui("errors.aboveMax", { max: fmt(err.max) });
    case "tooShort":
      return ui("errors.tooShort", { min: fmt(err.min) });
    case "tooLong":
      return ui("errors.tooLong", { max: fmt(err.max) });
    default:
      return ui(`errors.${err.code}`);
  }
}

function serverErrorMessage(ui: SettingsText["ui"], e: SettingsFieldError): string {
  const range =
    e.min != null && e.max != null
      ? ui("errors.serverRange", { min: e.min, max: e.max })
      : e.enumValues?.length
        ? ui("errors.serverOptions", { options: e.enumValues.join(", ") })
        : "";
  return `${ui(`errors.server.${e.kind}`, { message: e.message })}${range ? ` ${range}` : ""}`;
}

function rangeHint(ui: SettingsText["ui"], text: SettingsText, entry: SettingCatalogEntry): string {
  if (entry.type === "bool" || entry.type === "enum") return "";
  const isText = entry.type === "string" || entry.type === "text";
  const unit = isText ? text.unitLabel("chars") : entry.type === "percent" ? "%" : text.unitLabel(entry.unit);
  const f = (n: number) => {
    if (entry.type === "duration") {
      const base = catalogDurationUnit(entry);
      const u = bestDurationUnit(entry, n);
      return `${round6(convertDuration(n, base, u))} ${text.unitLabel(u)}`;
    }
    return `${n}${unit ? ` ${unit}` : ""}`;
  };
  if (entry.min != null && entry.max != null) return ui("hint.range", { min: f(entry.min), max: f(entry.max) });
  if (entry.min != null) return ui("hint.min", { min: f(entry.min) });
  if (entry.max != null) return ui("hint.max", { max: f(entry.max) });
  return "";
}

/**
 * One catalog entry rendered by its type: int/long/decimal (number box with unit, Up/Down keys step it),
 * duration (number + unit picker), bool (switch), enum (select), string/text, percent (slider + number).
 *
 * Shows the value in force and where it comes from, validates against the catalog's own bounds as the user
 * types, explains locked / role-limited / out-of-scope states, and offers one-click reset to the inherited value
 * and the change history. The field only edits a draft; saving is the group's job (SettingsGroup).
 */
export default function SettingField({
  entry,
  effective,
  inheritedValue,
  scope,
  draft,
  onDraftChange,
  onReset,
  onOpenHistory,
  error,
  pending = false,
  blocked = "none",
}: SettingFieldProps) {
  const text = useSettingsText();
  const { ui, label, description, help, formatValue, optionLabel, unitLabel, strictnessHint } = text;
  const uid = useId();
  const ids = {
    input: `${uid}-input`,
    label: `${uid}-label`,
    desc: `${uid}-desc`,
    meta: `${uid}-meta`,
    err: `${uid}-err`,
    help: `${uid}-help`,
    unit: `${uid}-unit`,
  };
  const [helpOpen, setHelpOpen] = useState(false);

  const current = effective ? effective.value : entry.default;
  const source = effective?.source ?? "default";
  const locked = effective?.locked === true;
  const readOnly = blocked !== "none" || pending;
  const baseUnit = catalogDurationUnit(entry);

  // What the control shows: the draft if there is one, else the saved value in the most readable duration unit.
  const shown: Draft = draft ?? draftFromValue(entry, current, entry.type === "duration" ? bestDurationUnit(entry, current) : undefined);
  const dirty = isDirty(entry, draft, current);
  const parsed = draft ? parseDraft(entry, draft) : null;
  const invalid: ValidationError | null = parsed && "error" in parsed ? parsed.error : null;
  const invalidText = invalid ? validationMessage(ui, text, entry, invalid) : "";
  const serverText = error ? serverErrorMessage(ui, error) : "";

  const canReset = !locked && !readOnly && source === overrideSource(scope);
  const help_ = help(entry);
  const desc = description(entry);
  const name = label(entry);

  const setText = (value: string, unit?: DurationUnit) => {
    if (readOnly) return; // a read-only input must never turn into a savable draft
    onDraftChange(entry.key, { value, ...(entry.type === "duration" ? { unit: unit ?? shown.unit ?? baseUnit } : {}) });
  };

  const onStepKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
    e.preventDefault();
    if (readOnly) return;
    const dir = e.key === "ArrowUp" ? 1 : -1;
    const step = entry.step ?? (entry.type === "decimal" ? 0.1 : 1);
    const unit = shown.unit ?? baseUnit;
    const lo = entry.min != null ? (entry.type === "duration" ? convertDuration(entry.min, baseUnit, unit) : entry.min) : -Infinity;
    const hi = entry.max != null ? (entry.type === "duration" ? convertDuration(entry.max, baseUnit, unit) : entry.max) : Infinity;
    const typed = Number(String(shown.value).trim());
    const from = String(shown.value).trim() !== "" && Number.isFinite(typed) ? typed : Number.isFinite(lo) ? lo - dir * step : 0;
    const next = Math.min(hi, Math.max(lo, round6(from + dir * step)));
    setText(String(next), shown.unit);
  };

  const isNumeric = ["int", "long", "decimal", "percent", "duration"].includes(entry.type);
  const describedBy = [ids.desc, ids.meta, invalidText ? ids.err : null, isNumeric ? ids.unit : null].filter(Boolean).join(" ");
  const ariaNow = parsed?.ok && typeof parsed.value === "number" ? parsed.value : numericValue(entry, current) ?? undefined;
  const commonNumberProps = {
    id: ids.input,
    "aria-describedby": describedBy,
    "aria-invalid": invalidText ? true : undefined,
    readOnly,
    autoComplete: "off",
  } as const;

  const unitText = entry.type === "percent" ? "%" : entry.type === "duration" ? "" : entry.type === "string" || entry.type === "text" ? "" : unitLabel(entry.unit);

  let control: ReactNode;
  if (entry.type === "bool") {
    control = (
      <div className="flex items-center gap-2">
        <Switch
          id={ids.input}
          aria-labelledby={ids.label}
          aria-describedby={describedBy}
          checked={shown.value === true}
          disabled={readOnly}
          onCheckedChange={(v) => onDraftChange(entry.key, { value: v })}
        />
        <span className="text-sm text-muted-foreground" aria-hidden="true">
          {shown.value === true ? ui("bool.on") : ui("bool.off")}
        </span>
      </div>
    );
  } else if (entry.type === "enum") {
    control = (
      <div className="w-full max-w-sm">
        <Select
          id={ids.input}
          aria-labelledby={ids.label}
          value={String(shown.value)}
          disabled={readOnly}
          placeholder={ui("select.placeholder")}
          options={(entry.enumValues ?? []).map((v) => ({ value: v, label: optionLabel(entry, v) }))}
          onValueChange={(v) => onDraftChange(entry.key, { value: v })}
        />
      </div>
    );
  } else if (entry.type === "text") {
    control = (
      <Textarea
        id={ids.input}
        rows={4}
        value={String(shown.value)}
        readOnly={readOnly}
        aria-describedby={describedBy}
        aria-invalid={invalidText ? true : undefined}
        onChange={(e) => setText(e.target.value)}
      />
    );
  } else if (entry.type === "string") {
    control = (
      <Input
        id={ids.input}
        value={String(shown.value)}
        readOnly={readOnly}
        autoComplete="off"
        aria-describedby={describedBy}
        aria-invalid={invalidText ? true : undefined}
        onChange={(e) => setText(e.target.value)}
      />
    );
  } else {
    const unit = shown.unit ?? baseUnit;
    control = (
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        {entry.type === "percent" ? (
          <input
            type="range"
            className="h-2 w-full max-w-xs accent-[var(--primary)] disabled:opacity-60"
            aria-label={ui("slider.label", { label: name })}
            aria-describedby={describedBy}
            min={entry.min ?? 0}
            max={entry.max ?? 100}
            step={entry.step ?? 1}
            value={typeof ariaNow === "number" ? ariaNow : entry.min ?? 0}
            disabled={readOnly}
            onChange={(e) => setText(e.target.value)}
          />
        ) : null}
        <div className="flex items-center gap-2">
          <Input
            {...commonNumberProps}
            role="spinbutton"
            aria-valuemin={entry.type === "duration" ? undefined : entry.min}
            aria-valuemax={entry.type === "duration" ? undefined : entry.max}
            aria-valuenow={entry.type === "duration" ? undefined : ariaNow}
            inputMode={entry.type === "int" || entry.type === "long" ? "numeric" : "decimal"}
            className="w-32"
            value={String(shown.value)}
            onKeyDown={onStepKey}
            onChange={(e) => setText(e.target.value, shown.unit)}
          />
          {entry.type === "duration" ? (
            <div className="w-28">
              <Select
                aria-label={ui("duration.unitLabel", { label: name })}
                value={unit}
                disabled={readOnly}
                options={DURATION_UNITS.map((u) => ({ value: u, label: unitLabel(u) }))}
                onValueChange={(next) => {
                  const n = Number(String(shown.value).trim());
                  const converted = String(shown.value).trim() !== "" && Number.isFinite(n) ? String(convertDuration(n, unit, next as DurationUnit)) : String(shown.value);
                  onDraftChange(entry.key, { value: converted, unit: next as DurationUnit });
                }}
              />
            </div>
          ) : null}
          <span id={ids.unit} className="text-sm text-muted-foreground">
            {unitText}
          </span>
        </div>
      </div>
    );
  }

  const handleReset = async () => {
    const ok = await onReset(entry.key);
    // The reset button disappears once the override is gone; hand focus to the control instead of dropping it.
    if (ok !== false) document.getElementById(ids.input)?.focus();
  };

  const RiskIcon = entry.risk === "security" ? ShieldAlert : TriangleAlert;
  const inheritedText = inheritedValue !== undefined ? formatValue(entry, inheritedValue) : "";

  return (
    <div
      role="group"
      aria-labelledby={ids.label}
      data-testid={`setting-${entry.key}`}
      data-dirty={dirty || undefined}
      className={cn(
        "rounded-lg border border-border bg-card p-4 text-card-foreground",
        dirty && "border-primary/60",
        pending && "opacity-80"
      )}
      aria-busy={pending || undefined}
    >
      <div className="mb-1 flex flex-wrap items-center gap-x-2 gap-y-1">
        <label id={ids.label} htmlFor={ids.input} className="text-sm font-medium">
          {name}
        </label>
        <SourceBadge source={source} scope={scope} locked={locked} />
        {entry.risk !== "low" ? (
          <Badge
            variant="state"
            state={entry.risk === "medium" ? "var(--state-idle)" : entry.risk === "high" ? "var(--state-warning)" : "var(--state-error)"}
            data-testid="setting-risk"
          >
            {entry.risk !== "medium" ? <RiskIcon aria-hidden="true" /> : null}
            {ui(`risk.${entry.risk}`)}
          </Badge>
        ) : null}
        {entry.restartRequired ? <Badge variant="outline">{ui("restartRequired")}</Badge> : null}
        {dirty ? (
          <span className="text-xs font-medium text-primary" data-testid="setting-unsaved">
            {ui("unsaved")}
          </span>
        ) : null}
      </div>

      {desc ? (
        <p id={ids.desc} className="mb-2 text-xs text-muted-foreground">
          {desc}
        </p>
      ) : (
        <span id={ids.desc} hidden />
      )}

      <div className="mb-2">{control}</div>

      {/* Validation messages live in a region that always exists so screen readers announce changes. */}
      <div id={ids.err} aria-live="polite" className="min-h-4 text-xs text-destructive" data-testid="setting-validation">
        {invalidText}
      </div>
      {serverText ? (
        <p role="alert" className="mt-1 text-xs text-destructive" data-testid="setting-server-error">
          {serverText}
        </p>
      ) : null}

      <ul id={ids.meta} className="mt-1 space-y-0.5 text-xs text-muted-foreground">
        <li data-testid="setting-effective">{ui("effective", { value: formatValue(entry, current) })}</li>
        {rangeHint(ui, text, entry) ? <li>{rangeHint(ui, text, entry)}</li> : null}
        {source !== "default" ? <li>{ui("hint.default", { value: formatValue(entry, entry.default) })}</li> : null}
        {entry.risk === "security" ? <li>{ui("hint.securityStricter")}</li> : null}
        {entry.risk === "security" && strictnessHint(entry) ? <li data-testid="setting-strictness">{strictnessHint(entry)}</li> : null}
        {canReset && inheritedText ? <li data-testid="setting-inherited">{ui("hint.inherited", { value: inheritedText })}</li> : null}
      </ul>

      {locked ? (
        <p className="mt-2 flex items-start gap-1.5 text-xs" data-testid="setting-locked">
          <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span>{effective?.lockedReason ? ui("locked.withReason", { reason: effective.lockedReason }) : ui("locked.noReason")}</span>
        </p>
      ) : blocked === "role" ? (
        <p className="mt-2 text-xs text-muted-foreground" data-testid="setting-blocked">
          {ui(entry.minRole === "TENANT_ADMIN" && scope === "agent" ? "blocked.adminOnly" : "blocked.role")}
        </p>
      ) : blocked === "scope" ? (
        <p className="mt-2 text-xs text-muted-foreground" data-testid="setting-blocked">
          {ui("blocked.scope")}
        </p>
      ) : blocked === "readOnly" ? (
        <p className="mt-2 text-xs text-muted-foreground" data-testid="setting-blocked">
          {ui("blocked.readOnly")}
        </p>
      ) : null}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        {canReset ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            data-testid="setting-reset"
            aria-label={ui("reset.aria", { label: name })}
            onClick={() => void handleReset()}
          >
            <RotateCcw aria-hidden="true" />
            {ui("reset.button")}
          </Button>
        ) : null}
        <Button
          type="button"
          size="sm"
          variant="ghost"
          data-testid="setting-history-open"
          aria-label={ui("history.open", { label: name })}
          onClick={() => onOpenHistory(entry.key)}
        >
          <History aria-hidden="true" />
          {ui("history.button")}
        </Button>
        {dirty ? (
          <Button type="button" size="sm" variant="ghost" onClick={() => onDraftChange(entry.key, undefined)}>
            {ui("discardOne")}
          </Button>
        ) : null}
        {help_ ? (
          <Button
            type="button"
            size="sm"
            variant="link"
            aria-expanded={helpOpen}
            aria-controls={ids.help}
            onClick={() => setHelpOpen((v) => !v)}
          >
            {ui(helpOpen ? "help.hide" : "help.show")}
          </Button>
        ) : null}
      </div>
      {help_ && helpOpen ? (
        <p id={ids.help} className="mt-2 rounded-md bg-secondary/40 p-2 text-xs" data-testid="setting-help">
          {help_}
        </p>
      ) : null}
    </div>
  );
}

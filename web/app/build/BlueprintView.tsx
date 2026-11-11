"use client";

// The Blueprint screen body: every setting as a row (value + where it came from
// + why it matters), grouped in sections, filtered by Simple / Standard / Advanced
// - all views over the SAME Blueprint object - plus the visual workflow editor.

import { useState } from "react";
import { ArrowDown, ArrowUp, ChevronDown, Cpu, Lock, Plus, Sparkles, Trash2, User, Wand2 } from "lucide-react";
import Link from "next/link";
import Select from "../components/Select";
import CommitInput from "./CommitInput";
import { radioGroupKeyDown } from "./radioNav";
import { Textarea } from "../components/ui/textarea";
import { Switch } from "../components/ui/switch";
import { cn } from "../lib/utils";
import {
  FIELDS,
  FLOW_KIND_LABEL,
  SECTIONS,
  addStep,
  editStep,
  fieldCount,
  fieldsForLevel,
  moveStep,
  removeStep,
  withWorkflow,
  type Blueprint,
  type FieldSource,
  type FieldSpec,
  type FlowKind,
  type Level,
  type OutputKind,
} from "../lib/build/blueprint";
import { useTranslations } from "../lib/i18n";

const SOURCE_META: Record<FieldSource, { label: string; icon: typeof User; tone: string }> = {
  default: { label: "Safe default", icon: Lock, tone: "text-muted-foreground" },
  goal: { label: "From your words", icon: Wand2, tone: "text-primary" },
  "device-ai": { label: "Drafted on your device", icon: Cpu, tone: "text-primary" },
  "cloud-ai": { label: "Written by AI from your words", icon: Sparkles, tone: "text-primary" },
  you: { label: "You set this", icon: User, tone: "text-foreground" },
};

const LEVELS: { id: Level; label: string; blurb: string }[] = [
  { id: "simple", label: "Simple", blurb: "The essentials" },
  { id: "standard", label: "Standard", blurb: "Most people stop here" },
  { id: "advanced", label: "Advanced", blurb: "Every dial" },
];

export function LevelPicker({
  level,
  output = "agent",
  onChange,
}: {
  level: Level;
  output?: OutputKind;
  onChange: (l: Level) => void;
}) {
    const tHC = useTranslations("hcBuildBlueprintView");
  return (
    // eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the radios; arrow keys bubble to the group
    <div
      role="radiogroup"
      aria-label={tHC("s7")}
      onKeyDown={radioGroupKeyDown}
      className="inline-flex flex-wrap rounded-xl border border-border bg-card p-1"
    >
      {LEVELS.map((l) => (
        <button
          key={l.id}
          type="button"
          role="radio"
          aria-checked={level === l.id}
          tabIndex={level === l.id ? 0 : -1}
          onClick={() => onChange(l.id)}
          className={cn(
            "rounded-lg px-3 py-1.5 text-start transition-colors",
            level === l.id
              ? "bg-primary text-primary-foreground"
              : "text-muted-foreground hover:bg-accent hover:text-foreground"
          )}
        >
          <span className="block text-xs font-semibold">{l.label}</span>
          <span
            className={cn(
              "hidden text-xs sm:block",
              level === l.id ? "text-primary-foreground/80" : "text-muted-foreground"
            )}
          >
            {l.blurb} · {fieldCount(l.id, output)} settings
          </span>
        </button>
      ))}
    </div>
  );
}

function SourceTag({ source }: { source: FieldSource }) {
  const m = SOURCE_META[source];
  const Icon = m.icon;
  return (
    <span className={cn("inline-flex items-center gap-1 text-[10px] font-medium", m.tone)}>
      <Icon size={10} /> {m.label}
    </span>
  );
}

function Control({ spec, bp, onChange }: { spec: FieldSpec; bp: Blueprint; onChange: (next: Blueprint) => void }) {
    const tHC = useTranslations("hcBuildBlueprintView");
  const c = spec.control;
  const value = spec.read(bp);
  const write = (v: string | number | boolean) => spec.write && onChange(spec.write(bp, v));
  if (c.kind === "readonly") {
    return (
      <span className="text-sm text-foreground">
        {value}
        {spec.href && (
          <Link href={spec.href} className="ms-2 text-xs text-primary underline-offset-2 hover:underline">
            {tHC("s6")}
          </Link>
        )}
      </span>
    );
  }
  if (c.kind === "toggle") {
    return (
      <div className="flex items-center gap-2">
        <Switch checked={value === "On"} onCheckedChange={(v) => write(v)} aria-label={spec.label} />
        <span className="text-sm text-foreground">{value}</span>
      </div>
    );
  }
  if (c.kind === "multiselect") {
    const chosen = new Set(
      value
        .split(",")
        .map((s) => s.trim().toUpperCase())
        .filter(Boolean)
    );
    return (
      <div className="flex flex-wrap gap-1.5">
        {c.options.map((o) => {
          const on = chosen.has(o.value);
          return (
            <button
              key={o.value}
              type="button"
              aria-pressed={on}
              onClick={() => {
                const next = new Set(chosen);
                if (on) next.delete(o.value);
                else next.add(o.value);
                write([...next].join(", "));
              }}
              className={cn(
                "rounded-full border px-2.5 py-1 text-xs transition-colors",
                on
                  ? "border-primary bg-primary/10 text-foreground"
                  : "border-border text-muted-foreground hover:bg-accent"
              )}
            >
              {o.label}
            </button>
          );
        })}
      </div>
    );
  }
  if (c.kind === "select") {
    const current = c.options.find((o) => o.label === value || o.value === value)?.value ?? "";
    return (
      <div className="w-full max-w-xs">
        <Select
          aria-label={spec.label}
          value={current}
          onChange={(e: any) => write(e.target.value)}
          options={c.options.map((o) => ({ value: o.value, label: o.hint ? `${o.label} - ${o.hint}` : o.label }))}
        />
      </div>
    );
  }
  if (c.kind === "range") {
    const numeric = Number(value);
    return (
      <div className="flex w-full max-w-xs items-center gap-3">
        <input
          type="range"
          min={c.min}
          max={c.max}
          step={c.step}
          value={Number.isFinite(numeric) ? numeric : c.min}
          onChange={(e) => write(Number(e.target.value))}
          aria-label={spec.label}
          className="h-1.5 flex-1 cursor-pointer accent-[var(--primary)]"
        />
        <span className="w-12 text-end font-mono text-xs text-foreground">{value}</span>
      </div>
    );
  }
  if (c.kind === "textarea") {
    return (
      <Textarea
        value={value}
        onChange={(e) => write(e.target.value)}
        className="min-h-32 text-sm"
        aria-label={spec.label}
      />
    );
  }
  // Free-text rows whose "empty" reads as a label ("Provider default", "None") edit as empty.
  const display = /^(Provider default|Random|None)$/.test(value) ? "" : value;
  return (
    <CommitInput
      value={display}
      onCommit={write}
      maxLength={c.max}
      placeholder={display === "" ? value : undefined}
      aria-label={spec.label}
      className="max-w-xs"
      onKeyDown={(e) => {
        if (e.key === "Enter") (e.target as HTMLInputElement).blur();
      }}
    />
  );
}

function Row({ spec, bp, onChange }: { spec: FieldSpec; bp: Blueprint; onChange: (next: Blueprint) => void }) {
  const [open, setOpen] = useState(false);
  const source = bp.sources[spec.key] ?? "default";
  return (
    <div className="grid gap-2 border-t border-border py-3 first:border-t-0 sm:grid-cols-[13rem_1fr]">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-medium text-foreground">{spec.label}</span>
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            aria-label={`Why ${spec.label} matters`}
            className="flex size-6 shrink-0 items-center justify-center rounded-full text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <ChevronDown aria-hidden="true" size={13} className={cn("transition-transform", open && "rotate-180")} />
          </button>
        </div>
        <SourceTag source={spec.applied ? source : "default"} />
      </div>
      <div className="min-w-0 space-y-1.5">
        <Control spec={spec} bp={bp} onChange={onChange} />
        {/* The "why" is one tap away everywhere, and open by default for the
            three Simple-level essentials so a first-timer is never guessing. */}
        {(open || (spec.level === "simple" && spec.control.kind !== "text")) && (
          <p className="text-xs leading-relaxed text-muted-foreground">{spec.help}</p>
        )}
      </div>
    </div>
  );
}

export function SettingsSections({
  bp,
  level,
  onChange,
}: {
  bp: Blueprint;
  level: Level;
  onChange: (next: Blueprint) => void;
}) {
  const visible = new Set(fieldsForLevel(level, bp.output ?? "agent").map((f) => f.key));
  return (
    <div className="space-y-4">
      {SECTIONS.map((section) => {
        const rows = FIELDS.filter((f) => f.section === section.id && visible.has(f.key));
        if (rows.length === 0) return null;
        return (
          <section key={section.id} className="rounded-xl border border-border bg-card p-4">
            <h3 className="text-sm font-semibold text-foreground">{section.title}</h3>
            <p className="mb-1 text-xs text-muted-foreground">{section.blurb}</p>
            {rows.map((spec) => (
              <Row key={spec.key} spec={spec} bp={bp} onChange={onChange} />
            ))}
          </section>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Workflow: a real visual flow, editable in place
// ---------------------------------------------------------------------------

const KIND_STYLE: Record<FlowKind, string> = {
  trigger: "border-primary/50 bg-primary/5",
  agent: "border-border bg-card",
  branch: "border-amber-500/40 bg-amber-500/5",
  approval: "border-emerald-500/40 bg-emerald-500/5",
  action: "border-border bg-card",
  output: "border-primary/50 bg-primary/5",
};

const ADDABLE: FlowKind[] = ["agent", "branch", "approval", "action"];

export function FlowEditor({ bp, onChange }: { bp: Blueprint; onChange: (next: Blueprint) => void }) {
    const tHC = useTranslations("hcBuildBlueprintView");
  const wf = bp.workflow;
  return (
    <section className="rounded-xl border border-border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-foreground">Workflow</h3>
          <p className="text-xs text-muted-foreground">
            {tHC("s5")}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Switch
            checked={!!wf}
            onCheckedChange={(v) => onChange(withWorkflow(bp, v))}
            aria-label={tHC("s4")}
          />
          <span className="text-sm text-foreground">{wf ? "Build one" : "Agent only"}</span>
        </div>
      </div>

      {wf && (
        <>
          <div className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
            <SourceTag source={bp.sources["workflow.steps"] ?? "default"} />
            <span>Named &ldquo;{wf.name}{tHC("s3")}</span>
          </div>
          <ol className="mt-3">
            {wf.steps.map((s, i) => (
              <li key={s.id} className="relative ps-8">
                {i < wf.steps.length - 1 && (
                  <span className="absolute start-[11px] top-8 h-[calc(100%-1rem)] w-px bg-border" aria-hidden />
                )}
                <span
                  className="absolute left-0 top-2 flex size-6 items-center justify-center rounded-full border border-border bg-background text-[10px] font-semibold text-muted-foreground"
                  aria-hidden
                >
                  {i + 1}
                </span>
                <div className={cn("mb-2 rounded-lg border p-3", KIND_STYLE[s.kind])}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                      {FLOW_KIND_LABEL[s.kind]}
                    </span>
                    <span className="flex items-center gap-0.5">
                      <button
                        type="button"
                        aria-label={`Move step ${i + 1} up`}
                        disabled={i === 0}
                        onClick={() => onChange(moveStep(bp, i, -1))}
                        className="flex size-8 items-center justify-center rounded text-muted-foreground hover:bg-accent disabled:opacity-30"
                      >
                        <ArrowUp aria-hidden="true" size={14} />
                      </button>
                      <button
                        type="button"
                        aria-label={`Move step ${i + 1} down`}
                        disabled={i === wf.steps.length - 1}
                        onClick={() => onChange(moveStep(bp, i, 1))}
                        className="flex size-8 items-center justify-center rounded text-muted-foreground hover:bg-accent disabled:opacity-30"
                      >
                        <ArrowDown aria-hidden="true" size={14} />
                      </button>
                      <button
                        type="button"
                        aria-label={`Remove step ${i + 1}`}
                        disabled={wf.steps.length <= 2}
                        onClick={() => onChange(removeStep(bp, i))}
                        className="flex size-8 items-center justify-center rounded text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-30"
                      >
                        <Trash2 aria-hidden="true" size={14} />
                      </button>
                    </span>
                  </div>
                  <CommitInput
                    key={s.id}
                    value={s.label}
                    onCommit={(v) => onChange(editStep(bp, i, { label: v }))}
                    aria-label={`Step ${i + 1} title`}
                    className="mt-1 h-8 border-transparent bg-transparent px-1 text-sm font-medium shadow-none focus-visible:border-input"
                  />
                  <CommitInput
                    key={`${s.id}-d`}
                    value={s.detail}
                    onCommit={(v) => onChange(editStep(bp, i, { detail: v }))}
                    aria-label={`Step ${i + 1} description`}
                    className="h-7 border-transparent bg-transparent px-1 text-xs text-muted-foreground shadow-none focus-visible:border-input"
                  />
                </div>
              </li>
            ))}
          </ol>
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">{tHC("s2")}</span>
            {ADDABLE.map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => onChange(addStep(bp, k))}
                className="inline-flex items-center gap-1 rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <Plus aria-hidden="true" size={11} /> {FLOW_KIND_LABEL[k]}
              </button>
            ))}
          </div>
        </>
      )}
      {!wf && (
        <p className="mt-3 text-xs text-muted-foreground">
          <Sparkles aria-hidden="true" size={11} className="me-1 inline text-primary" />
          {tHC("s1")}
        </p>
      )}
    </section>
  );
}

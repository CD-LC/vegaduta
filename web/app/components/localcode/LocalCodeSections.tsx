"use client";

import Link from "next/link";
import { Cpu, Loader2, Send, ShieldCheck, Sparkles, StopCircle, Wand2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import type { LocalFileRef } from "../../lib/localcode/types";
import type { LocalEditReadiness, ProposedEdit } from "../../lib/localcode/edit";
import LocalCodeDiffView, { type ApplyOutcome } from "./LocalCodeDiffView";
import type { ModelGate } from "./localCodeSupport";

// The local code assistant's cards: the model notice, Ask, Suggest a change,
// and the privacy band. LocalCodePanel owns the state and the actions.

/** Shown when asking is impossible: on-device AI is off for this build, or no model is on this device. */
export function ModelGateNotice({ modelGate }: { modelGate: ModelGate }) {
  const t = useTranslations("localCode");
  if (!(modelGate.kind === "no-model" || modelGate.kind === "edge-off")) return null;
  return (
    <section className="rounded-xl border border-border bg-secondary/30 p-4">
      <div className="flex items-start gap-3">
        <Cpu size={16} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-foreground">
            {modelGate.kind === "edge-off" ? t("model.edgeOffTitle") : t("model.notReadyTitle")}
          </h2>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">
            {modelGate.kind === "edge-off" ? t("model.edgeOffBody") : t("model.notReadyBody")}
          </p>
          {/* No /edge link when the flag is off: that console is gated on
              the same NEXT_PUBLIC_EDGE_AGENTS value, so sending someone
              there would be sending them somewhere that cannot help. */}
          {modelGate.kind === "no-model" && (
            <Button size="sm" variant="secondary" className="mt-3" asChild>
              <Link href="/edge">{t("model.openEdge")}</Link>
            </Button>
          )}
        </div>
      </div>
    </section>
  );
}

export function AskSection({
  question,
  setQuestion,
  canAsk,
  asking,
  indexReady,
  modelGate,
  onAsk,
  onStop,
}: {
  question: string;
  setQuestion: (q: string) => void;
  canAsk: boolean;
  asking: boolean;
  indexReady: boolean;
  modelGate: ModelGate;
  onAsk: () => void;
  onStop: () => void;
}) {
  const t = useTranslations("localCode");
  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <h2 className="text-base font-semibold text-foreground">{t("ask.title")}</h2>
      <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted-foreground">{t("ask.body")}</p>
      <Textarea
        value={question}
        onChange={(event) => setQuestion(event.target.value)}
        placeholder={t("ask.placeholder")}
        rows={3}
        disabled={!canAsk}
        className="mt-3"
      />
      <div className="mt-3 flex flex-wrap items-center gap-3">
        {asking ? (
          <Button size="sm" variant="secondary" onClick={onStop}>
            <StopCircle size={13} aria-hidden="true" />
            {t("ask.stop")}
          </Button>
        ) : (
          <Button size="sm" onClick={onAsk} disabled={!canAsk || !question.trim()}>
            <Send size={13} aria-hidden="true" />
            {t("ask.submit")}
          </Button>
        )}
        {!indexReady && <span className="text-xs text-muted-foreground">{t("ask.needIndex")}</span>}
        {indexReady && modelGate.kind === "no-model" && (
          <span className="text-xs text-muted-foreground">{t("ask.needModel")}</span>
        )}
        {indexReady && modelGate.kind === "edge-off" && (
          <span className="text-xs text-muted-foreground">{t("model.edgeOffTitle")}</span>
        )}
        {modelGate.kind === "checking" && (
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" />
            {t("model.checking")}
          </span>
        )}
      </div>
    </section>
  );
}

/** The single-file "suggest a change": target path, instruction, proposal and its diff/apply step. */
export function SuggestChangeSection({
  files,
  editPath,
  onEditPath,
  instruction,
  setInstruction,
  canEdit,
  indexReady,
  editReadiness,
  proposing,
  onPropose,
  editError,
  edit,
  applyOutcome,
  applying,
  onApply,
  onDiscard,
}: {
  files: LocalFileRef[] | null;
  editPath: string;
  /** A path the person typed (stops citations from overwriting it). */
  onEditPath: (path: string) => void;
  instruction: string;
  setInstruction: (text: string) => void;
  canEdit: boolean;
  indexReady: boolean;
  editReadiness: LocalEditReadiness | null;
  proposing: boolean;
  onPropose: () => void;
  editError: string | null;
  edit: ProposedEdit | null;
  applyOutcome: ApplyOutcome;
  applying: boolean;
  onApply: () => void;
  onDiscard: () => void;
}) {
  const t = useTranslations("localCode");
  const tp = useTranslations("consolePolish");
  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <h2 className="text-base font-semibold text-foreground">{t("edit.title")}</h2>
      <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted-foreground">{t("edit.body")}</p>
      <div className="mt-3 space-y-2">
        <label className="block text-xs font-medium text-muted-foreground" htmlFor="localcode-edit-path">
          {t("edit.pathLabel")}
        </label>
        <Input
          id="localcode-edit-path"
          list="localcode-file-paths"
          value={editPath}
          onChange={(event) => onEditPath(event.target.value)}
          placeholder={t("edit.pathPlaceholder")}
          disabled={!canEdit}
          className="font-mono text-xs"
        />
        {/* Plain <datalist> rather than a combobox: the file list can be
            thousands of entries, and the browser's own datalist filtering
            handles that without rendering them all. */}
        <datalist id="localcode-file-paths">
          {(files || []).slice(0, 1000).map((file) => (
            <option key={file.path} value={file.path} />
          ))}
        </datalist>
        {(files?.length ?? 0) > 1000 && (
          <p className="text-xs text-muted-foreground">
            {tp("localcodeFileHint", { shown: (1000).toLocaleString(), total: (files?.length ?? 0).toLocaleString() })}
          </p>
        )}
        <Textarea
          value={instruction}
          onChange={(event) => setInstruction(event.target.value)}
          placeholder={t("edit.instructionPlaceholder")}
          rows={2}
          disabled={!canEdit}
        />
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button
          size="sm"
          variant="secondary"
          onClick={onPropose}
          disabled={!canEdit || proposing || !instruction.trim() || !editPath.trim()}
        >
          {proposing ? (
            <Loader2 size={13} className="animate-spin" aria-hidden="true" />
          ) : (
            <Wand2 size={13} aria-hidden="true" />
          )}
          {proposing ? t("edit.proposing") : t("edit.propose")}
        </Button>
        {!indexReady && <span className="text-xs text-muted-foreground">{t("edit.needIndex")}</span>}
        {indexReady && editReadiness?.ready === false && (
          <span className="text-xs text-muted-foreground">{editReadiness.detail}</span>
        )}
      </div>
      {editError && <p className="mt-3 text-xs text-destructive">{editError}</p>}
      {edit && (
        <div className="mt-4">
          <LocalCodeDiffView
            edit={edit}
            outcome={applyOutcome}
            applying={applying}
            onApply={onApply}
            onDiscard={onDiscard}
          />
        </div>
      )}
    </section>
  );
}

export function PrivacyBand() {
  const t = useTranslations("localCode");
  return (
    <section className="rounded-xl border border-border bg-secondary/30 p-5">
      <div className="flex items-start gap-3">
        <ShieldCheck size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-foreground">{t("privacy.title")}</h2>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">{t("privacy.body")}</p>
          <p className="mt-2 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <Sparkles size={12} className="shrink-0 text-primary" aria-hidden="true" />
            {t("privacy.free")}
          </p>
          {/* The page needs no login; the console around it does. Saying so
              here is the correction to the old "works signed out" claim —
              /code is not in PUBLIC_ROUTES and AppShell requires a session. */}
          <p className="mt-1.5 max-w-3xl text-xs leading-relaxed text-muted-foreground">{t("privacy.signIn")}</p>
        </div>
      </div>
    </section>
  );
}


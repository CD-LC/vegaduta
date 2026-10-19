"use client";

import Link from "next/link";
import { useState } from "react";
import { Brain, Check, Pencil, Sparkles, Trash2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import type { MemoryFact } from "../../memory/memoryTypes";
import SkillSelfCheckBadge from "../skillLibrary/SkillSelfCheckBadge";
import LearnedProposals from "./LearnedProposals";
import LearnedWeekSummary from "./LearnedWeekSummary";
import type { Learned } from "./useLearned";

/** A fact the assistant remembered, with edit and forget inline. */
function FactRow({ fact, learned }: { fact: MemoryFact; learned: Learned }) {
  const t = useTranslations("assistant");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(fact.content);
  const [busy, setBusy] = useState(false);
  const run = async (job: () => Promise<void>) => {
    setBusy(true);
    try {
      await job();
      setEditing(false);
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className="rounded-lg border border-border px-2.5 py-2 text-xs" data-testid={`learned-fact-${fact.id}`}>
      {editing ? (
        <>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            rows={3}
            aria-label={t("learnedEditFact")}
            className="w-full resize-none rounded-md border border-border bg-background p-1.5 text-xs"
          />
          <div className="mt-1.5 flex gap-1.5">
            <Button type="button" size="sm" className="h-7 px-2 text-xs" disabled={busy || !draft.trim()} onClick={() => void run(() => learned.editFact(fact, draft.trim()))}>
              {t("learnedSave")}
            </Button>
            <Button type="button" size="sm" variant="outline" className="h-7 px-2 text-xs" onClick={() => { setEditing(false); setDraft(fact.content); }}>
              {t("learnedCancel")}
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="break-words text-foreground">{fact.content}</p>
          <div className="mt-1.5 flex gap-1.5">
            <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" onClick={() => setEditing(true)}>
              <Pencil size={11} aria-hidden="true" />
              {t("learnedEdit")}
            </Button>
            <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" disabled={busy} onClick={() => void run(() => learned.forgetFact(fact))}>
              <Trash2 size={11} aria-hidden="true" />
              {t("learnedForget")}
            </Button>
          </div>
        </>
      )}
    </li>
  );
}

/**
 * The work rail's "Learned" section (the design's Hermes /journey): skills the assistant drafted from
 * its own work (approve or reject; nothing is used before the yes), skills already approved, and
 * facts it remembered about the person (edit or forget). Renders nothing until something exists or
 * the first read has finished with nothing to show, so a stack without memory or skills stays quiet.
 */
export default function LearnedSection({ learned }: { learned: Learned }) {
  const t = useTranslations("assistant");
  const [busyId, setBusyId] = useState<string | null>(null);
  const act = async (id: string, job: () => Promise<void>) => {
    setBusyId(id);
    try {
      await job();
    } finally {
      setBusyId(null);
    }
  };
  if (!learned.loaded) return null;
  const empty = learned.proposals.length + learned.pending.length + learned.active.length + learned.facts.length === 0;
  return (
    <section data-testid="work-learned">
      <h3 className="flex items-center gap-1.5 px-1 pt-3 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
        <Sparkles size={12} aria-hidden="true" />
        {t("learnedTitle")}
      </h3>
      <LearnedWeekSummary refreshKey={learned.proposals.length} />
      {empty && <p className="px-1 text-xs text-muted-foreground">{t("learnedEmpty")}</p>}
      <LearnedProposals learned={learned} />
      {learned.pending.length > 0 && (
        <ul className="mt-1 space-y-2" aria-label={t("learnedPending")}>
          {learned.pending.map((s) => (
            <li key={s.id} className="rounded-lg border border-border p-2.5 text-xs" data-testid={`learned-skill-${s.id}`}>
              <p className="flex flex-wrap items-center gap-1.5 font-medium text-foreground">
                {s.name}
                <SkillSelfCheckBadge check={s.selfCheck} />
              </p>
              {s.description && <p className="mt-0.5 line-clamp-3 text-muted-foreground">{s.description}</p>}
              <p className="mt-1 text-muted-foreground">{t("learnedPendingHint")}</p>
              <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                <Button type="button" size="sm" className="h-7 gap-1 px-2 text-xs" disabled={busyId === s.id} onClick={() => void act(s.id, () => learned.approve(s.id))}>
                  <Check size={11} aria-hidden="true" />
                  {t("learnedApprove")}
                </Button>
                <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" disabled={busyId === s.id} onClick={() => void act(s.id, () => learned.reject(s.id))}>
                  <X size={11} aria-hidden="true" />
                  {t("learnedReject")}
                </Button>
                <Link href="/skills" className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
                  {t("learnedReview")}
                </Link>
              </div>
            </li>
          ))}
        </ul>
      )}
      {learned.active.length > 0 && (
        <ul className="mt-2 space-y-1.5" aria-label={t("learnedSkills")}>
          {learned.active.slice(0, 5).map((s) => (
            <li key={s.id} className="flex items-start gap-2 rounded-lg border border-border px-2.5 py-2 text-xs" data-testid={`learned-active-${s.id}`}>
              <Sparkles size={12} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
              <span className="min-w-0">
                <span className="block truncate font-medium text-foreground" title={s.description}>{s.name}</span>
                <span className="block text-muted-foreground">{t("learnedUses", { n: s.uses })}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {learned.facts.length > 0 && (
        <>
          <h4 className="flex items-center gap-1.5 px-1 pt-3 text-[11px] font-medium text-muted-foreground">
            <Brain size={12} aria-hidden="true" />
            {t("learnedFacts")}
          </h4>
          <ul className="mt-1 space-y-1.5">
            {learned.facts.map((f) => (
              <FactRow key={`${f.scope}:${f.id}`} fact={f} learned={learned} />
            ))}
          </ul>
        </>
      )}
      <p className="mt-1.5 flex flex-wrap gap-x-3 px-1 text-xs">
        <Link href="/memory" className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
          {t("learnedOpenMemory")}
        </Link>
        <Link href="/skills" className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
          {t("learnedOpenSkills")}
        </Link>
      </p>
    </section>
  );
}

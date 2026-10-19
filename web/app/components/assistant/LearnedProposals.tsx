"use client";

import { useState } from "react";
import { Check, Pencil, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { LearningProposal } from "../../lib/api/assistant";
import { Button } from "../ui/button";
import type { Learned } from "./useLearned";

/** Kinds in the order the rail lists them; any other kind a newer server sends is shown after these. */
const KIND_ORDER = ["LESSON", "PREFERENCE", "ROUTINE", "CONSOLIDATION"];

export function groupProposals(proposals: LearningProposal[]): [string, LearningProposal[]][] {
  const kinds = [...new Set(proposals.map((p) => p.kind))].sort((a, b) => {
    const ia = KIND_ORDER.indexOf(a);
    const ib = KIND_ORDER.indexOf(b);
    return (ia < 0 ? KIND_ORDER.length : ia) - (ib < 0 ? KIND_ORDER.length : ib);
  });
  return kinds.map((k) => [k, proposals.filter((p) => p.kind === k)]);
}

/** One proposal: the text can be edited before it is approved; a refusal (409) is shown in place. */
function ProposalRow({ proposal, learned }: { proposal: LearningProposal; learned: Learned }) {
  const t = useTranslations("assistant");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(proposal.body);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (job: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await job();
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t("learnedProposalFailed"));
    } finally {
      setBusy(false);
    }
  };
  const edited = editing && draft.trim() !== proposal.body ? draft.trim() : undefined;
  return (
    <li className="rounded-lg border border-border p-2.5 text-xs" data-testid={`learned-proposal-${proposal.id}`}>
      {proposal.title && proposal.title !== proposal.body && <p className="font-medium text-foreground">{proposal.title}</p>}
      {editing ? (
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          rows={3}
          aria-label={t("learnedEditProposal")}
          className="mt-1 w-full resize-none rounded-md border border-border bg-background p-1.5 text-xs"
        />
      ) : (
        <p className="mt-0.5 break-words text-foreground">{proposal.body}</p>
      )}
      <p className="mt-1 text-muted-foreground">{proposal.applicable ? t("learnedProposalHint") : t("learnedProposalNotYet")}</p>
      {error && (
        <p role="alert" className="mt-1 text-destructive">
          {error}
        </p>
      )}
      <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
        <Button
          type="button"
          size="sm"
          className="h-7 gap-1 px-2 text-xs"
          disabled={busy || !proposal.applicable || !draft.trim()}
          onClick={() => void run(() => learned.approveProposal(proposal.id, edited))}
        >
          <Check size={11} aria-hidden="true" />
          {t("learnedApprove")}
        </Button>
        <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" disabled={busy} onClick={() => void run(() => learned.rejectProposal(proposal.id))}>
          <X size={11} aria-hidden="true" />
          {t("learnedReject")}
        </Button>
        {!editing && (
          <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" onClick={() => setEditing(true)}>
            <Pencil size={11} aria-hidden="true" />
            {t("learnedEdit")}
          </Button>
        )}
      </div>
    </li>
  );
}

/** Proposals the assistant filed to learn something, grouped by kind. Nothing is applied before Approve. */
export default function LearnedProposals({ learned }: { learned: Learned }) {
  const t = useTranslations("assistant");
  if (learned.proposals.length === 0) return null;
  return (
    <>
      {groupProposals(learned.proposals).map(([kind, rows]) => (
        <div key={kind} data-testid={`learned-proposals-${kind}`}>
          <h4 className="px-1 pt-3 text-[11px] font-medium text-muted-foreground">{t(`learnedKind_${KIND_ORDER.includes(kind) ? kind : "OTHER"}`, { n: rows.length })}</h4>
          <ul className="mt-1 space-y-2">
            {rows.map((p) => (
              <ProposalRow key={p.id} proposal={p} learned={learned} />
            ))}
          </ul>
        </div>
      ))}
    </>
  );
}

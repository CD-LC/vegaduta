"use client";

import { useRef } from "react";
import { X as CloseIcon } from "lucide-react";
import { Button } from "../ui/button";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import { useTranslations } from "../../lib/i18n";

export default function WorkflowAiPendingDialog({
  aiPendingGraph,
  applyAiGraph,
  setAiPendingGraph,
  t,
}: any) {
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, () => setAiPendingGraph(null), { enabled: !!aiPendingGraph });

  // nlflow W4: the pending graph may carry the server's plan (workflowPlan.ts).
  // Its explanation is shown before the overwrite; absent against an older core.
  const tDescribe = useTranslations("workflowDescribe");
  const steps: { nodeId: string; sentence: string }[] = aiPendingGraph?.plan?.steps || [];
  const isEdit = aiPendingGraph?.plan?.mode === "edit";
  return (
    <>
      {/* AI workflow generator confirm-overwrite (#392) - only shown when
       * the canvas already had real content (more than the lone starter
       * Trigger node) before generating, so this never gets in the way of
       * the common "build my first workflow with AI" case. */}
      {aiPendingGraph && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          role="presentation"
          onClick={(e) => { if (e.target === e.currentTarget) setAiPendingGraph(null); }}
        >
          <div
            className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
            ref={dialogRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">
                {isEdit ? tDescribe("pendingEditTitle") : t("replaceGraphTitle")}
              </h2>
              <button
                type="button"
                className="-mr-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                onClick={() => setAiPendingGraph(null)}
                aria-label={t("close")}
              >
                <CloseIcon size={16} />
              </button>
            </div>
            <p className="mt-1.5 text-sm text-muted-foreground">
              {isEdit
                ? tDescribe("pendingEditBody", { count: aiPendingGraph.nodes.length })
                : t("replaceGraphBody", { count: aiPendingGraph.nodes.length, plural: aiPendingGraph.nodes.length === 1 ? "" : "s" })}
            </p>
            {steps.length > 0 && (
              <div className="mt-4" data-testid="ai-pending-explanation">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {tDescribe("pendingExplanationTitle")}
                </h3>
                <ol className="mt-2 space-y-1.5">
                  {steps.map((step, i) => (
                    <li key={`${step.nodeId}-${i}`} className="flex items-start gap-2 text-sm text-foreground">
                      <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-[11px] font-medium text-foreground">
                        {i + 1}
                      </span>
                      <span className="min-w-0">{step.sentence}</span>
                    </li>
                  ))}
                </ol>
              </div>
            )}
            {aiPendingGraph.notes.length > 0 && (
              <ul className="mt-4 space-y-1.5">
                {aiPendingGraph.notes.map((note: string, i: number) => (
                  <li key={i} className="flex gap-2 text-sm text-muted-foreground">
                    <span className="mt-1.5 size-1 shrink-0 rounded-full bg-primary" />
                    {note}
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-5 flex justify-end gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => setAiPendingGraph(null)}>
                {t("cancel")}
              </Button>
              <Button type="button" size="sm" onClick={applyAiGraph}>
                {isEdit ? tDescribe("pendingEditApply") : t("replaceGraph")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

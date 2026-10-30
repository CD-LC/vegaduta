"use client";

import { useRef } from "react";
import { X as CloseIcon } from "lucide-react";
import ShareWorkflowPanel from "./ShareWorkflowPanel";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import McpExposureField from "../McpExposureField";
import { isPersonalEdition } from "../../lib/edition";
import { useTranslations } from "../../lib/i18n";

export default function WorkflowShareModal({ setShowShareModal, showShareModal, t, workflow }: any) {
    const tHC = useTranslations("hcComponentsWorkflowWorkflowShareModal");
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, () => setShowShareModal(false), { enabled: !!(showShareModal && workflow) });

  return (
    <>
      {/* Public share link modal - owner-side mint/inspect/revoke, workflow
       * counterpart of ShareAgentPanel. State machine lives entirely inside
       * ShareWorkflowPanel; this wrapper only owns the open/close boolean. */}
      {showShareModal && workflow && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          role="presentation"
          onClick={(e) => {
            if (e.target === e.currentTarget) setShowShareModal(false);
          }}
        >
          <div
            className="w-full max-w-md max-h-[90vh] overflow-y-auto rounded-xl border border-border bg-card shadow-xl"
            ref={dialogRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-start justify-between gap-3 px-5 pb-1 pt-5">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">{tHC("s1")}</h2>
              <button
                type="button"
                className="-me-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                onClick={() => setShowShareModal(false)}
                aria-label={t("close")}
              >
                <CloseIcon size={16} />
              </button>
            </div>
            <ShareWorkflowPanel workflowId={workflow.id} workflowName={workflow.name} />
            {!isPersonalEdition() && (
              <div className="px-5 pb-5">
                <McpExposureField kind="workflows" resourceId={workflow.id} />
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}

"use client";

import { useRef } from "react";
import { Paperclip, X as CloseIcon } from "lucide-react";
import { Label } from "../ui/label";
import { Input } from "../ui/input";
import { Button } from "../ui/button";
import { TEXTAREA_CLASS } from "./workflowCanvasHelpers";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

export default function WorkflowRunModal({
  addRunParam,
  attachRunFile,
  busy,
  removeRunAttachment,
  removeRunParam,
  runAttachBusy,
  runAttachments,
  runComment,
  runFileInputRef,
  runInput,
  runParams,
  runWithRuntimeInputs,
  pinCount,
  dryRunNote,
  setRunComment,
  setRunInput,
  setShowRunModal,
  showRunModal,
  t,
  updateRunParam,
}: any) {
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, () => { if (!busy) setShowRunModal(false); }, { enabled: !!showRunModal });

  return (
    <>
      {/* Run modal (#345) - "prompt users for required inputs at runtime:
       * files, run comments, execution prompts, any required runtime
       * parameters. Do not require these details during workflow
       * creation." Everything here is per-run only; nothing gets persisted
       * to the workflow's own definition. */}
      {showRunModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          role="presentation"
          onClick={(e) => { if (e.target === e.currentTarget && !busy) setShowRunModal(false); }}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
            ref={dialogRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("runModalTitle")}</h2>
              <button
                type="button"
                className="-mr-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                onClick={() => setShowRunModal(false)}
                aria-label={t("close")}
              >
                <CloseIcon size={16} />
              </button>
            </div>
            <p className="mt-1.5 text-sm text-muted-foreground">{t("runModalHint")}</p>
            {/* Test run (nlflow W4/W7): sends are simulated on the server for this run. */}
            {dryRunNote && (
              <p className="mt-2 rounded-md border border-border bg-muted px-3 py-2 text-sm text-foreground" data-testid="run-modal-dry-run-note">
                {dryRunNote}
              </p>
            )}

            <div className="mt-5 space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="wf-run-prompt">{t("executionPromptLabel")}</Label>
                <textarea
                  id="wf-run-prompt"
                  rows={4}
                  className={TEXTAREA_CLASS}
                  placeholder={t("executionPromptPlaceholder")}
                  value={runInput}
                  onChange={(e) => setRunInput(e.target.value)}
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="wf-run-comment">{t("runCommentLabel")}</Label>
                <Input
                  id="wf-run-comment"
                  type="text"
                  placeholder={t("runCommentPlaceholder")}
                  value={runComment}
                  onChange={(e) => setRunComment(e.target.value)}
                />
              </div>

              <div className="space-y-1.5">
                <Label>{t("attachFileLabel")}</Label>
                <div className="flex flex-wrap items-center gap-2">
                  <input ref={runFileInputRef} type="file" onChange={attachRunFile} className="hidden" />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => runFileInputRef.current?.click()}
                    disabled={runAttachBusy}
                  >
                    <Paperclip size={14} /> {runAttachBusy ? t("uploading") : t("attachDocument")}
                  </Button>
                  {runAttachments.map((a: any, i: any) => (
                    <span
                      key={i}
                      className="inline-flex items-center gap-1.5 rounded-full border border-border bg-secondary px-2.5 py-1 text-xs text-secondary-foreground"
                    >
                      {a.name}
                      <button
                        type="button"
                        onClick={() => removeRunAttachment(i)}
                        aria-label={t("removeAttachmentAriaLabel", { name: a.name })}
                        className="text-muted-foreground transition-colors hover:text-foreground"
                      >
                        <CloseIcon size={11} />
                      </button>
                    </span>
                  ))}
                </div>
              </div>

              <div className="space-y-2">
                <Label>{t("runtimeParamsLabel")}</Label>
                {runParams.map((p: any, i: any) => (
                  <div key={i} className="flex items-center gap-2">
                    <Input
                      type="text"
                      placeholder={t("keyPlaceholder")}
                      value={p.key}
                      onChange={(e) => updateRunParam(i, "key", e.target.value)}
                    />
                    <Input
                      type="text"
                      placeholder={t("valuePlaceholder")}
                      value={p.value}
                      onChange={(e) => updateRunParam(i, "value", e.target.value)}
                    />
                    {runParams.length > 1 && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        onClick={() => removeRunParam(i)}
                        aria-label={t("removeParamAriaLabel")}
                      >
                        <CloseIcon size={13} />
                      </Button>
                    )}
                  </div>
                ))}
                <Button type="button" variant="outline" size="sm" onClick={addRunParam}>
                  {t("addParameter")}
                </Button>
              </div>
            </div>

            <div className="mt-6 flex items-center justify-end gap-2">
              {/* Saves-draft disclosure (audit finding): runWithRuntimeInputs
               * calls persistGraph("DRAFT") before dispatching, which on an
               * ACTIVE workflow silently reverts it to DRAFT - the topbar
               * button carries the same warning as a tooltip. */}
              <p className="mr-auto text-xs text-muted-foreground">
                {t("runSavesDraftHint")}
                {pinCount > 0 ? ` ${pinCount === 1 ? t("pinRunModalHintOne") : t("pinRunModalHint", { count: pinCount })}` : ""}
              </p>
              <Button type="button" variant="outline" size="sm" onClick={() => setShowRunModal(false)} disabled={busy}>
                {t("cancel")}
              </Button>
              <Button type="button" size="sm" onClick={runWithRuntimeInputs} disabled={busy}>
                {busy ? t("running") : t("runButton")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

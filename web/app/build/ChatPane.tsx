// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).
import { useEffect, useMemo, useRef, useState } from "react";
import { Sparkles, Loader2, Send, ImagePlus, LayoutTemplate, Paperclip, Archive, Mic, MicOff } from "lucide-react";
import { Button } from "../components/ui/button";
import { Textarea } from "../components/ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { useTranslations } from "../lib/i18n";
import { appBuilder } from "./lib";
import FigmaImport from "./FigmaImport";
import SketchPad from "./SketchPad";
import { composeContext, designInputFromFile, serializeSketch, revokeDesignInput } from "./designInputs";
import { generateFilesOnDevice } from "./edgeFiles";
import { inferSource } from "../components/PoweredByChip";
import {
  appendStored,
  buildUndo,
  canUndo,
  loadDraft,
  loadThread,
  saveDraft,
  saveThread,
  subscribeThread,
} from "./chatStore";
import { suggestionsFor } from "./suggestions";
import { BUILD_STRINGS as S } from "./buildStrings";
import { uploadAssets } from "./uploadAssets";
import { ChatResultEntry } from "./ChatResultEntry";
import { DesignInputChips } from "./DesignInputChips";
import { useDictation } from "./useDictation";

/* ------------------------------------------------------------------ */
/* Chat thread                                                        */

export default function ChatPane({
  project,
  setProject,
  onError,
  onClearError,
  onFiles,
  assets,
  onAssetsChanged,
  fixRequest,
  onBusyChange,
  prefill,
  fileSet,
  disabled,
}: any) {
  const t = useTranslations("appBuilder");
  const pid = project.project_id;
  // The thread and the unsent draft survive a surface-tab switch / reload
  // (ProjectView unmounts on both): they are read back from localStorage here.
  const [thread, setThread] = useState(() => loadThread(pid));
  const [instruction, setInstruction] = useState(() => loadDraft(pid));
  const [busy, setBusy] = useState(false);
  const [busyLabel, setBusyLabel] = useState("");
  // Mid-turn detail from /generate/stream: the model's own summary and the
  // files it just wrote - shown live so the turn is not a bare spinner.
  const [liveSummary, setLiveSummary] = useState<string | null>(null);
  const [liveFiles, setLiveFiles] = useState<string[] | null>(null);
  const handledFix = useRef(0);
  // Design inputs attached to the NEXT message: dropped images/SVGs and
  // the SketchPad's wireframe. Composed into `context` at send time.
  const [designInputs, setDesignInputs] = useState([]);
  const [sketchBlocks, setSketchBlocks] = useState([]);
  const [sketchOpen, setSketchOpen] = useState(false);
  const designFileRef = useRef(null);
  const assetFileRef = useRef(null);
  const bottomRef = useRef(null);
  const composerRef = useRef(null);
  const fileSetRef = useRef(fileSet);
  fileSetRef.current = fileSet;
  const unmounted = useRef(false);
  const draftRef = useRef(instruction);
  draftRef.current = instruction;
  const [undoing, setUndoing] = useState(null);
  const [copiedPath, setCopiedPath] = useState(null);

  const { speechOk, listening, voiceNote, toggleVoice, stopDictation } = useDictation(instruction, setInstruction);

  useEffect(() => {
    unmounted.current = false;
    return () => {
      unmounted.current = true;
    };
  }, []);

  // Persist the thread on every change, the draft debounced + on unmount.
  useEffect(() => {
    saveThread(pid, thread);
  }, [pid, thread]);
  useEffect(() => {
    const h = setTimeout(() => saveDraft(pid, instruction), 300);
    return () => clearTimeout(h);
  }, [pid, instruction]);
  useEffect(() => () => saveDraft(pid, draftRef.current), [pid]);
  // A turn that finished while this pane was unmounted lands in storage; pick
  // it up if we have mounted again since.
  useEffect(() => subscribeThread(pid, () => setThread(loadThread(pid))), [pid]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [thread]);

  useEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);

  // "Fix with AI" from the preview: one turn per request id. A request is only
  // honoured when it is acted on right away - one that lands mid-turn, on an
  // archived project, or that is already stale is dropped, never queued to
  // fire later against a project that has since changed.
  useEffect(() => {
    if (!fixRequest || fixRequest.id === handledFix.current) return;
    handledFix.current = fixRequest.id;
    if (busy || disabled || Date.now() - fixRequest.id > 15_000) return;
    void send(fixRequest.text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fixRequest, busy, disabled]);

  // "Use in app" from the Generate image dialog: fill the composer (the user
  // reviews and sends; nothing is auto-submitted) and bring it into view -
  // on a phone the composer is a full scroll above the Design tab.
  useEffect(() => {
    if (!prefill?.text) return;
    setInstruction(prefill.text);
    const el = composerRef.current;
    if (el) {
      el.focus({ preventScroll: true });
      el.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [prefill?.nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  // "/" focuses the composer unless the user is already typing somewhere.
  useEffect(() => {
    const onKey = (e) => {
      if (e.key !== "/" || e.metaKey || e.ctrlKey || e.altKey || e.isComposing) return;
      const el = e.target;
      const tag = el?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el?.isContentEditable) return;
      if (document.querySelector('[role="dialog"],[role="alertdialog"]')) return;
      const c = composerRef.current;
      if (!c || c.disabled) return;
      e.preventDefault();
      c.focus();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const askedPrompts = useMemo(() => thread.filter((e) => e.kind === "user").map((e) => e.text), [thread]);
  const suggestions = useMemo(
    () =>
      suggestionsFor(
        project.template_id,
        (fileSet || []).map((f) => f.path),
        askedPrompts
      ),
    [project.template_id, fileSet, askedPrompts]
  );

  const pushEntry = (entry) => {
    if (unmounted.current) appendStored(pid, entry);
    else setThread((cur) => [...cur, entry]);
  };

  const addDesignFiles = async (fileList) => {
    if (disabled) return;
    for (const file of Array.from(fileList || [])) {
      const input = await designInputFromFile(file);
      if (input) {
        setDesignInputs((cur) => [...cur, input]);
      } else {
        onError(`${file.name}: unsupported file type — drop an image or an SVG`);
      }
    }
  };

  const uploadAssetFiles = (fileList) =>
    disabled ? Promise.resolve() : uploadAssets(project.project_id, fileList, onError, onAssetsChanged);

  const copyFile = async (path) => {
    const content = (fileSetRef.current || []).find((f) => f.path === path)?.content;
    if (content == null) return;
    try {
      await navigator.clipboard.writeText(content);
      setCopiedPath(path);
      setTimeout(() => setCopiedPath((c) => (c === path ? null : c)), 1500);
    } catch {
      onError("Couldn't copy to the clipboard - open the file in your repo instead.");
    }
  };

  /** Restore the files this turn modified to their pre-turn content, through
   * the same apply endpoint the Design panel's token edits use. */
  const undoEntry = async (index) => {
    const entry = thread[index];
    if (!entry?.undo || undoing != null || busy || disabled || !canUndo(thread, index)) return;
    setUndoing(index);
    try {
      const r = await appBuilder.applyEdits(pid, {
        instruction: `Undo: ${entry.prompt || "previous change"}`,
        files: entry.undo.files,
      });
      if (!r) throw new Error("The builder returned an empty response. Nothing was changed.");
      if (r.success === false) {
        throw new Error(r.check_output || "The build check failed after restoring, so nothing was changed.");
      }
      onFiles?.(r.files?.length ? r.files : entry.undo.files);
      setThread((cur) => cur.map((e, k) => (k === index ? { ...e, undone: true } : e)));
      onClearError?.();
    } catch (e) {
      onError(e.message);
    } finally {
      setUndoing(null);
    }
  };

  /** One full generate turn: on-device model first (free, private), hosted
   * platform/BYOK as the fallback — and when the on-device edit fails the
   * project's own build check, one corrective retry driven client-side
   * with the check output (the server never retries the edge path — the
   * model lives here). */
  const send = async (override?: string) => {
    const text = (typeof override === "string" ? override : instruction).trim();
    if ((!text && designInputs.length === 0 && sketchBlocks.length === 0) || busy || disabled) return;
    stopDictation();
    const attachments = [...designInputs];
    const sketchText = serializeSketch(sketchBlocks);
    const context = composeContext(
      [
        ...attachments,
        ...(sketchText ? [{ id: "sketch", kind: "sketch", label: "Wireframe", contextText: sketchText }] : []),
      ],
      (assets || []).map((a) => a.path)
    );
    const images = attachments.filter((d) => d.image).map((d) => d.image);
    const shown = text || `Apply the attached design${attachments.length > 1 ? "s" : ""}.`;
    // Pre-turn snapshot for "Undo this change".
    const snapshot = fileSetRef.current ? new Map(fileSetRef.current.map((f) => [f.path, f.content])) : null;

    if (typeof override !== "string") setInstruction("");
    setDesignInputs([]);
    setSketchBlocks([]);
    setSketchOpen(false);
    setThread((cur) => [...cur, { kind: "user", text: shown, attachments: attachments.map((a) => a.label) }]);
    setBusy(true);
    // True once a streamed "files" event already updated the preview —
    // on a failed or aborted turn the preview must be put back, since those
    // files were never promoted to the project.
    let previewFilesShown = false;
    const revertPreview = () => {
      if (previewFilesShown && snapshot) {
        onFiles?.(Array.from(snapshot, ([path, content]) => ({ path, content })));
        previewFilesShown = false;
      }
    };
    try {
      let result;
      let via = "hosted";
      // Files the on-device path applied (used when the server echoes none).
      let appliedEdge = null;
      // The on-device model's own explanation of the change — forwarded to
      // the server in applyEdits and echoed back; kept here as the fallback
      // for backends that predate the field.
      let edgeSummary = null;
      // Which engine produced this change, shown on the result entry below.
      // On-device path: the tier the edge layer resolved plus the manifest
      // model name. Hosted path: app-builder forwards core's resolved
      // providerName/model (absent on older backends - never fabricated).
      let poweredBy = null;
      if (images.length === 0) {
        // Free path: try the on-device model first. Vision attachments
        // force the hosted path — WebLLM's text models can't see pixels.
        setBusyLabel("On-device model…");
        // The local model needs the files it edits; without them (older
        // app-builder, retrieval down) it would rewrite files blind, so
        // the hosted path takes the turn instead.
        const existing = await appBuilder.editContext(project.project_id, shown).catch((): null => null);
        const edge = existing
          ? await generateFilesOnDevice({
              instruction: shown,
              stack: project.template_id,
              context,
              existing,
            })
          : { ok: false as const, reason: "no-local-tier" as const };
        if (edge.ok) {
          via = "on-device";
          poweredBy = { source: "device", model: edge.modelName || null };
          edgeSummary = edge.summary ?? null;
          setBusyLabel("Applying + checking…");
          result = await appBuilder.applyEdits(project.project_id, {
            instruction: shown,
            files: edge.files,
            summary: edge.summary ?? undefined,
          });
          appliedEdge = edge.files;
          if (result?.success === false && result?.check_output) {
            setBusyLabel("Fixing the build (on-device)…");
            // The retry sees the files the first attempt wrote (they are
            // what failed the check), in full.
            const written = new Set(edge.files.map((f) => f.path));
            const retry = await generateFilesOnDevice({
              instruction: shown,
              stack: project.template_id,
              context,
              priorFailure: result.check_output,
              existing: {
                project_files: existing?.project_files || [],
                files: [
                  ...edge.files.map((f) => ({ path: f.path, content: f.content, full: true })),
                  ...(existing?.files || []).filter((f) => !written.has(f.path)),
                ],
              },
            });
            if (retry.ok) {
              poweredBy = { source: "device", model: retry.modelName || null };
              edgeSummary = retry.summary ?? edgeSummary;
              result = await appBuilder.applyEdits(project.project_id, {
                instruction: shown,
                files: retry.files,
                summary: retry.summary ?? undefined,
              });
              appliedEdge = retry.files;
            }
          }
        } else {
          // No local tier eligible (no model, hosted-only pref, edge off)
          // or it produced unusable output — the hosted path takes over.
          via = "hosted";
        }
      }
      if (!result) {
        setBusyLabel("Platform model…");
        const onStage = (stage, evt) => {
          if (stage === "context") setBusyLabel("Reading your app…");
          else if (stage === "writing")
            setBusyLabel(evt?.attempt > 1 ? `Rewriting the change (try ${evt.attempt})…` : "Writing your change…");
          else if (stage === "summary") setLiveSummary(evt?.summary || null);
          else if (stage === "files") {
            // The applied set arrives before the build/test check - name the
            // files it touches in the busy card while the preview renders.
            setLiveFiles(Array.isArray(evt?.files_changed) ? evt.files_changed : (evt?.files || []).map((f) => f.path));
            if (evt?.files?.length) {
              previewFilesShown = true;
              onFiles?.(evt.files);
            }
            setBusyLabel("Checking it builds…");
          } else if (stage === "checking")
            setBusyLabel(evt?.attempt > 1 ? `Checking the fix (try ${evt.attempt})…` : "Checking it builds…");
          else if (stage === "publishing") setBusyLabel("Publishing the change…");
        };
        const generateBody = {
          context: context || undefined,
          images: images.length ? images : undefined,
        };
        try {
          result = await appBuilder.generateStream(project.project_id, shown, generateBody, onStage);
        } catch (e) {
          // Pre-stream app-builder builds have no /generate/stream — the
          // turn still works via the sync route, just without live stages.
          if (e?.status !== 404 && e?.status !== 405) throw e;
          result = await appBuilder.generate(project.project_id, shown, generateBody);
        }
        if (!result) {
          // A null body is not "success with zero files".
          throw new Error("The builder returned an empty response, so nothing was changed. Please try again.");
        }
        if (result?.powered_by?.provider || result?.powered_by?.model) {
          poweredBy = {
            source: inferSource(result.powered_by.provider),
            provider: result.powered_by.provider,
            model: result.powered_by.model,
          };
        }
      }
      const success = result?.success !== false;
      // A streamed turn's "files" event already showed the candidate files
      // in the preview; if the check then failed, nothing was promoted —
      // put the pre-turn files back so the preview reflects the project.
      if (!success) revertPreview();
      // Prefer what the server says it wrote; for an on-device edit that the
      // server did not echo, fall back to the files we sent it.
      const applied = result?.files?.length ? result.files : via === "on-device" && success ? appliedEdge : null;
      pushEntry({
        kind: "result",
        success,
        prompt: shown,
        // The model's own plain-English explanation (hosted: core's summary
        // field; on-device: the edge model's, echoed by the server or kept
        // locally when the backend predates the field).
        summary: result?.summary || edgeSummary,
        filesChanged: result?.files_changed?.length ? result.files_changed : (applied || []).map((f) => f.path),
        touched: (applied || []).map((f) => f.path),
        diff: result?.diff || "",
        branch: result?.branch || null,
        prUrl: result?.pr_url || null,
        prNumber: result?.pr_number || null,
        via,
        poweredBy,
        skippedFiles: result?.skipped_files || [],
        undo: success ? buildUndo(snapshot, applied) : null,
        error: result?.success === false ? result?.check_output || "the build/test check failed" : undefined,
      });
      onFiles?.(applied);
      if (success) onClearError?.();
      // BYO-repo pivot: persist the latest known branch/PR onto the project
      // itself so the GitHub tab (which a tenant may not have open right now)
      // reflects this turn's activity too, not just the chat entry. Optional
      // fields — a pre-pivot backend response simply omits them and this is a
      // no-op merge.
      if (result?.branch || result?.pr_url) {
        setProject((cur) => ({
          ...cur,
          github: {
            ...cur?.github,
            branch: result?.branch ?? cur?.github?.branch ?? null,
            pr_url: result?.pr_url ?? cur?.github?.pr_url ?? null,
            pr_number: result?.pr_number ?? cur?.github?.pr_number ?? null,
            pushed_at: new Date().toISOString(),
          },
        }));
      }
    } catch (e) {
      revertPreview();
      pushEntry({ kind: "result", success: false, error: e.message });
      onError(e.message);
      // Give the user their draft back instead of making them retype it.
      if (unmounted.current) {
        if (typeof override !== "string" && !loadDraft(pid)) saveDraft(pid, text);
      } else {
        if (typeof override !== "string") setInstruction((cur) => cur || text);
        setDesignInputs((cur) => (cur.length ? cur : attachments));
        setSketchBlocks((cur) => (cur.length ? cur : sketchBlocks));
      }
    } finally {
      if (!unmounted.current) {
        setBusy(false);
        setBusyLabel("");
        setLiveSummary(null);
        setLiveFiles(null);
      }
    }
  };

  const showChips =
    !busy &&
    !disabled &&
    suggestions.length > 0 &&
    (!instruction.trim() || suggestions.some((s) => s.prompt === instruction));
  const iconBtn = "size-10 px-0";

  return (
    <Card className="flex h-[min(560px,85dvh)] flex-col">
      <CardHeader className="pb-3">
        <CardTitle className="text-sm">{t("chatHeading")}</CardTitle>
      </CardHeader>
      <CardContent className="flex min-h-0 flex-1 flex-col pt-0">
        <div className="mb-3 min-h-0 flex-1 space-y-3 overflow-auto pe-1">
          {thread.length === 0 && <p className="text-sm text-muted-foreground">{t("chatEmptyText")}</p>}
          {thread.map((entry, i) =>
            entry.kind === "user" ? (
              <div key={i} className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-sm text-foreground">
                <span className="font-medium text-muted-foreground">{t("youAskedPrefix")}</span> {entry.text}
              </div>
            ) : (
              <ChatResultEntry
                key={i}
                entry={entry}
                index={i}
                undoable={canUndo(thread, i)}
                fileSet={fileSet}
                copiedPath={copiedPath}
                onCopyFile={(path) => void copyFile(path)}
                busy={busy}
                disabled={disabled}
                undoing={undoing}
                onUndo={() => void undoEntry(i)}
              />
            )
          )}
          {busy && (
            <div className="space-y-1.5 text-xs text-muted-foreground" role="status">
              <div className="flex items-center gap-2">
                <Loader2 aria-hidden="true" size={13} className="animate-spin" /> {busyLabel || t("generatingText")}
              </div>
              {liveSummary && <p className="ps-5 italic">{liveSummary}</p>}
              {liveFiles && liveFiles.length > 0 && (
                <div className="flex max-h-20 flex-wrap gap-1 overflow-y-auto ps-5" data-testid="live-file-chips">
                  {liveFiles.map((p) => (
                    <span key={p} className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px]">
                      {p}
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}
          <div ref={bottomRef} />
        </div>

        {/* Design inputs queued for the next message (dropped images, an
            SVG/Figma export, the sketch pad's wireframe). */}
        {sketchOpen && (
          <div className="mb-2 shrink-0">
            <SketchPad blocks={sketchBlocks} onChange={setSketchBlocks} onClose={() => setSketchOpen(false)} />
          </div>
        )}
        {(designInputs.length > 0 || sketchBlocks.length > 0) && (
          <DesignInputChips
            designInputs={designInputs}
            sketchBlockCount={sketchBlocks.length}
            onOpenSketch={() => setSketchOpen(true)}
            onRemove={(d) => {
              revokeDesignInput(d);
              setDesignInputs((cur) => cur.filter((x) => x.id !== d.id));
            }}
          />
        )}

        {disabled && (
          <p
            role="status"
            className="mb-2 flex shrink-0 items-start gap-1.5 rounded-md border border-border bg-muted/40 px-2.5 py-1.5 text-xs text-muted-foreground"
          >
            <Archive size={13} className="mt-0.5 shrink-0" aria-hidden="true" /> {t("archivedBanner")}
          </p>
        )}

        {/* Ideas for the next change, from the template + the files that exist. */}
        {showChips && (
          <div role="group" aria-label={S.suggestedChanges} className="mb-2 flex shrink-0 gap-1.5 overflow-x-auto pb-1">
            {suggestions.map((s) => (
              <button
                key={s.id}
                type="button"
                onClick={() => {
                  setInstruction(s.prompt);
                  composerRef.current?.focus();
                }}
                className="inline-flex min-h-9 shrink-0 items-center gap-1 whitespace-nowrap rounded-full border border-border bg-muted/40 px-3 text-xs text-foreground transition-colors hover:bg-accent/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
              >
                <Sparkles size={11} className="text-primary" aria-hidden="true" /> {s.label}
              </button>
            ))}
          </div>
        )}

        <div className="shrink-0">
          <Textarea
            ref={composerRef}
            className="min-h-[52px] resize-none"
            placeholder={t("chatPlaceholder")}
            aria-label={t("chatHeading")}
            value={instruction}
            disabled={busy || disabled}
            onChange={(e) => setInstruction(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.nativeEvent?.isComposing && (e.metaKey || e.ctrlKey || !e.shiftKey)) {
                e.preventDefault();
                void send();
              }
            }}
            onDrop={(e) => {
              e.preventDefault();
              void addDesignFiles(e.dataTransfer.files);
            }}
            onDragOver={(e) => e.preventDefault()}
          />
          <div className="mt-2 flex flex-wrap items-center gap-1">
            <input
              ref={designFileRef}
              type="file"
              accept="image/*,.svg"
              multiple
              className="hidden"
              onChange={(e) => {
                void addDesignFiles(e.target.files);
                e.target.value = "";
              }}
            />
            <input
              ref={assetFileRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => {
                void uploadAssetFiles(e.target.files);
                e.target.value = "";
              }}
            />
            <Button
              variant="ghost"
              size="icon"
              className={iconBtn}
              title={S.attachDesignTitle}
              aria-label={S.attachDesignLabel}
              disabled={busy || disabled}
              onClick={() => designFileRef.current?.click()}
            >
              <ImagePlus aria-hidden="true" size={16} />
            </Button>
            <div className="inline-flex [&>button]:size-10 [&>button]:px-0">
              <FigmaImport
                projectId={project.project_id}
                disabled={busy || disabled}
                onImported={(input) => setDesignInputs((cur) => [...cur, input])}
                onError={onError}
              />
            </div>
            <Button
              variant="ghost"
              size="icon"
              className={iconBtn}
              title={S.uploadAssetTitle}
              aria-label={S.uploadAssetLabel}
              disabled={busy || disabled}
              onClick={() => assetFileRef.current?.click()}
            >
              <Paperclip aria-hidden="true" size={16} />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className={`${iconBtn} ${sketchOpen ? "bg-accent" : ""}`}
              title={S.sketchTitle}
              aria-label={S.sketchLabel}
              aria-pressed={sketchOpen}
              disabled={busy || disabled}
              onClick={() => setSketchOpen((v) => !v)}
            >
              <LayoutTemplate aria-hidden="true" size={16} />
            </Button>
            {speechOk && (
              <Button
                variant="ghost"
                size="icon"
                className={`${iconBtn} ${listening ? "bg-accent text-destructive" : ""}`}
                title={listening ? S.dictationStopTitle : S.dictationStartTitle}
                aria-label={listening ? S.dictationStopLabel : S.dictationStartLabel}
                aria-pressed={listening}
                disabled={busy || disabled}
                onClick={toggleVoice}
              >
                {listening ? <MicOff aria-hidden="true" size={16} /> : <Mic aria-hidden="true" size={16} />}
              </Button>
            )}
            <Button
              size="icon"
              className={`${iconBtn} ms-auto`}
              disabled={
                busy || disabled || (!instruction.trim() && designInputs.length === 0 && sketchBlocks.length === 0)
              }
              onClick={() => void send()}
              aria-label={S.sendLabel}
              title={S.sendTitle}
            >
              {busy ? (
                <Loader2 aria-hidden="true" size={16} className="animate-spin" />
              ) : (
                <Send aria-hidden="true" size={16} />
              )}
            </Button>
          </div>
          <p className="mt-1 text-[11px] text-muted-foreground" role="status" aria-live="polite">
            {listening
              ? "Listening… speak your request."
              : voiceNote || (
                  <span className="hidden sm:inline">Enter to send · Shift+Enter for a new line · / to focus</span>
                )}
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, Download, FileUp, Trash2 } from "lucide-react";
import { api, triggerBrowserDownload } from "../lib/api";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { useLocale, useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import {
  EXPORT_FORMATS,
  IMPORTED_CHAT_LIST_LIMIT,
  defaultSelection,
  formatBytes,
  groupSkipped,
  roomLeft,
  selectNone,
  selectedIds,
  selectionCap,
  shortDate,
  skipReasonKey,
  sourceLabelKey,
  toggleSelection,
  validateUpload,
  type ChatImportFeatures,
  type ChatImportPreview,
  type ChatImportResult,
  type ImportedChatSummary,
  type ImportedChatView,
  type UploadProblem,
} from "../lib/chatPortability";
import {
  defaultSelection as defaultFactSelection,
  isSelectable as isFactSelectable,
  selectedFacts,
  toggleSelection as toggleFactSelection,
  type ImportPreview as MemoryPreview,
} from "./memoryImport";

type Stage = "pick" | "review" | "done";

/** Rows rendered from one preview; a file can hold thousands, and one import takes at most a few hundred. */
const DISPLAY_LIMIT = 500;

/**
 * "Import chats from ChatGPT or Claude": upload the data export, pick conversations, import them as read-only
 * records, optionally suggest memories from them. Plus the list of what was imported, with view / export /
 * delete. Backed by core's /api/chat-import: the preview writes nothing, the import makes no model call and
 * writes no memory. The card renders nothing when the feature is off (the probe says so, or any later call 404s).
 */
export default function ImportChatsCard({
  className,
  onMemoriesSaved,
}: {
  className?: string;
  /** Called after memories suggested from imported chats were saved, so the page can reload its facts. */
  onMemoriesSaved?: () => void;
}) {
  const t = useTranslations("chatPortability");
  const locale = useLocale();
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [features, setFeatures] = useState<ChatImportFeatures | null>(null);
  const [gone, setGone] = useState(false);
  const [open, setOpen] = useState(false);
  const [stage, setStage] = useState<Stage>("pick");
  const [file, setFile] = useState<File | null>(null);
  const [problem, setProblem] = useState<UploadProblem | null>(null);
  const [preview, setPreview] = useState<ChatImportPreview | null>(null);
  const [selection, setSelection] = useState<Set<string>>(new Set<string>());
  const [result, setResult] = useState<ChatImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [memPreview, setMemPreview] = useState<MemoryPreview | null>(null);
  const [memSelection, setMemSelection] = useState<Set<number>>(new Set<number>());
  const [memBusy, setMemBusy] = useState(false);
  const [memSaved, setMemSaved] = useState<number | null>(null);
  const [memGone, setMemGone] = useState(false);
  const [chats, setChats] = useState<ImportedChatSummary[] | null>(null);
  const [listFailed, setListFailed] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [openChat, setOpenChat] = useState<ImportedChatView | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .getChatPortabilityFeatures()
      .then((f: ChatImportFeatures) => {
        if (alive) setFeatures(f);
      })
      .catch(() => {
        if (alive) setGone(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  const importEnabled = Boolean(features?.importEnabled);

  const loadList = useCallback(async () => {
    try {
      const rows: ImportedChatSummary[] = await api.listImportedChats();
      setChats(rows);
      setListFailed(false);
    } catch (err: any) {
      if (err?.status === 404) setGone(true);
      else setListFailed(true);
    }
  }, []);

  useEffect(() => {
    if (importEnabled) void loadList();
  }, [importEnabled, loadList]);

  const shown = useMemo(() => (preview?.conversations ?? []).slice(0, DISPLAY_LIMIT), [preview]);
  const cap = selectionCap(preview?.capacity);
  const chosenIds = useMemo(() => selectedIds(shown, selection), [shown, selection]);
  const chosenFacts = useMemo(() => selectedFacts(memPreview?.facts, memSelection), [memPreview, memSelection]);

  if (gone || !features || !features.importEnabled) return null;

  const reset = () => {
    setFile(null);
    setProblem(null);
    setPreview(null);
    setSelection(selectNone());
    setResult(null);
    setMemPreview(null);
    setMemSelection(new Set<number>());
    setMemSaved(null);
    setStage("pick");
    if (fileInput.current) fileInput.current.value = "";
  };

  const close = () => {
    reset();
    setOpen(false);
  };

  const onPick = (picked: File | null) => {
    setFile(picked);
    setProblem(picked ? validateUpload(picked, features.maxBytes) : null);
  };

  const review = async () => {
    if (busy || !file || problem) return;
    setBusy(true);
    try {
      const p: ChatImportPreview = await api.previewChatImport(file);
      setPreview(p);
      setSelection(defaultSelection((p.conversations ?? []).slice(0, DISPLAY_LIMIT), selectionCap(p.capacity)));
      setStage("review");
    } catch (err: any) {
      // 404: switched off since the page loaded. Anything else was already shown by toJson.
      if (err?.status === 404) setGone(true);
    } finally {
      setBusy(false);
    }
  };

  const runImport = async () => {
    if (busy || !file || chosenIds.length === 0) return;
    setBusy(true);
    try {
      const r: ChatImportResult = await api.importChats(file, chosenIds);
      setResult(r);
      setStage("done");
      void loadList();
    } catch (err: any) {
      if (err?.status === 404) setGone(true);
    } finally {
      setBusy(false);
    }
  };

  const suggestMemories = async () => {
    if (memBusy || !result || result.chats.length === 0) return;
    setMemBusy(true);
    try {
      const p: MemoryPreview = await api.getChatMemoryCandidates(result.chats.map((c) => c.id));
      setMemPreview(p);
      setMemSelection(defaultFactSelection(p.facts));
    } catch (err: any) {
      if (err?.status === 404) setMemGone(true);
    } finally {
      setMemBusy(false);
    }
  };

  const saveMemories = async () => {
    if (memBusy || !memPreview || chosenFacts.length === 0) return;
    setMemBusy(true);
    try {
      const saved: { saved: number } = await api.importMemory(
        chosenFacts.map((f) => ({ text: f.text, scope: "shared" as const })),
        memPreview.source || "other"
      );
      setMemSaved(saved.saved ?? 0);
      setMemPreview(null);
      onMemoriesSaved?.();
    } catch (err: any) {
      if (err?.status === 404) setMemGone(true);
    } finally {
      setMemBusy(false);
    }
  };

  const download = async (job: Promise<{ blob: Blob; filename: string }>) => {
    try {
      const { blob, filename } = await job;
      triggerBrowserDownload(blob, filename);
    } catch (err: any) {
      // The error toast was already shown; a vanished chat just drops out of the list.
      if (err?.status === 404) void loadList();
    }
  };

  const toggleView = async (id: string) => {
    if (openId === id) {
      setOpenId(null);
      setOpenChat(null);
      return;
    }
    setOpenId(id);
    setOpenChat(null);
    try {
      const view: ImportedChatView = await api.getImportedChat(id);
      setOpenChat(view);
    } catch {
      setOpenId(null);
    }
  };

  const remove = async (id: string) => {
    setConfirmId(null);
    try {
      await api.deleteImportedChat(id);
      if (openId === id) {
        setOpenId(null);
        setOpenChat(null);
      }
    } catch {
      // toast already shown
    }
    void loadList();
  };

  const skippedGroups = groupSkipped(result?.skipped);
  const room = roomLeft(preview?.capacity);

  return (
    <Card elevation="raised" data-testid="chat-import" className={cn(className)}>
      <CardHeader>
        <CardTitle>{t("importHeading")}</CardTitle>
        <CardDescription>{t("importIntro")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {!open && (
          <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
            <FileUp size={14} aria-hidden="true" /> {t("importStart")}
          </Button>
        )}

        {open && stage === "pick" && (
          <div className="space-y-4">
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">{t("step1Title")}</p>
              <p className="text-xs text-muted-foreground">{t("step1Chatgpt")}</p>
              <p className="text-xs text-muted-foreground">{t("step1Claude")}</p>
            </div>
            <div className="space-y-2 border-t border-border pt-4">
              <p className="text-sm font-medium text-foreground">{t("step2Title")}</p>
              <p className="text-xs text-muted-foreground">{t("step2Body")}</p>
              <div className="flex flex-wrap items-center gap-2">
                <input
                  ref={fileInput}
                  type="file"
                  accept=".zip,.json,application/zip,application/json"
                  className="sr-only"
                  data-testid="chat-import-file"
                  aria-label={t("chooseFile")}
                  onChange={(e) => onPick(e.target.files && e.target.files.length > 0 ? e.target.files[0] : null)}
                />
                <Button type="button" size="sm" variant="outline" onClick={() => fileInput.current?.click()}>
                  <FileUp size={14} aria-hidden="true" /> {t("chooseFile")}
                </Button>
                {file && (
                  <span className="min-w-0 break-words text-xs text-foreground">
                    {t("fileSelected", { name: file.name, size: formatBytes(file.size) })}
                  </span>
                )}
              </div>
              <p className="text-xs text-muted-foreground">
                {t("limitHint", { size: formatBytes(features.maxBytes) })}
              </p>
              {problem && (
                <p className="text-xs text-destructive" role="alert">
                  {problem === "tooLarge"
                    ? t("errTooLarge", { size: formatBytes(features.maxBytes) })
                    : problem === "wrongType"
                      ? t("errWrongType")
                      : t("errEmpty")}
                </p>
              )}
              <div className="flex items-center justify-end gap-2">
                <Button type="button" size="sm" variant="ghost" onClick={close}>
                  {t("close")}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  disabled={busy || !file || Boolean(problem)}
                  onClick={() => void review()}
                >
                  {busy ? t("reviewing") : t("reviewButton")}
                </Button>
              </div>
            </div>
          </div>
        )}

        {open && stage === "review" && preview && (
          <div className="space-y-3">
            <p className="text-sm font-medium text-foreground">{t("step3Title")}</p>
            <p className="text-xs text-muted-foreground">
              {t("statsLine", { listed: preview.stats.listed, skipped: preview.stats.skipped })}
            </p>
            {preview.stats.scanCapReached && (
              <p className="text-xs text-muted-foreground">{t("scanCapNote", { count: preview.stats.listed })}</p>
            )}
            {preview.conversations.length > shown.length && (
              <p className="text-xs text-muted-foreground">
                {t("showingFirst", { shown: shown.length, total: preview.conversations.length })}
              </p>
            )}
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => setSelection(defaultSelection(shown, cap))}
              >
                {t("selectAll")}
              </Button>
              <Button type="button" size="sm" variant="outline" onClick={() => setSelection(selectNone())}>
                {t("selectNone")}
              </Button>
              <span className="text-xs text-muted-foreground">{t("selectedCount", { count: chosenIds.length })}</span>
            </div>
            <p className="text-xs text-muted-foreground">
              {t("capNote", { max: preview.capacity.maxPerImport })} {t("roomWarning", { room })}
            </p>

            <ul
              className="max-h-96 space-y-1 overflow-y-auto rounded-lg border border-border p-2"
              data-testid="chat-import-list"
            >
              {shown.map((c, i) => {
                const selectable = !c.alreadyImported;
                const ticked = selectable && selection.has(c.id);
                return (
                  <li key={c.id} className="flex items-start gap-2 rounded px-1.5 py-1">
                    <input
                      id={`chat-import-row-${i}`}
                      type="checkbox"
                      className="mt-1"
                      checked={ticked}
                      disabled={!selectable || (!ticked && selection.size >= cap)}
                      onChange={() => setSelection((s) => toggleSelection(s, c.id, cap))}
                    />
                    <label
                      htmlFor={`chat-import-row-${i}`}
                      className={cn(
                        "min-w-0 flex-1 break-words text-sm",
                        selectable ? "text-foreground" : "text-muted-foreground"
                      )}
                    >
                      <span className="font-medium">{c.title}</span>
                      <Badge variant="outline" className="ms-2">
                        {t(sourceLabelKey(c.source))}
                      </Badge>
                      {c.alreadyImported && (
                        <Badge variant="secondary" className="ms-2">
                          {t("alreadyImported")}
                        </Badge>
                      )}
                      {c.truncated && (
                        <Badge variant="outline" className="ms-2">
                          {t("shortened")}
                        </Badge>
                      )}
                      <span className="block text-xs text-muted-foreground">
                        {t("messagesCount", { count: c.messageCount })}
                        {c.createdAt ? ` · ${shortDate(c.createdAt, locale)}` : ""}
                      </span>
                      <span className="mt-0.5 block text-xs text-muted-foreground">{c.snippet || t("noSnippet")}</span>
                    </label>
                  </li>
                );
              })}
            </ul>

            <div className="flex flex-wrap items-center justify-end gap-2">
              <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={reset}>
                {t("back")}
              </Button>
              <Button
                type="button"
                size="sm"
                disabled={busy || chosenIds.length === 0}
                onClick={() => void runImport()}
              >
                <FileUp size={14} aria-hidden="true" />{" "}
                {busy ? t("importing") : t("importButton", { count: chosenIds.length })}
              </Button>
            </div>
          </div>
        )}

        {open && stage === "done" && result && (
          <div className="space-y-3" data-testid="chat-import-result">
            <Alert variant={result.imported > 0 ? "success" : "warning"}>
              {result.imported === 0 && <AlertTriangle aria-hidden="true" />}
              <AlertDescription>
                <p className="font-medium">{t("doneTitle")}</p>
                <p>{t("doneBody", { imported: result.imported, skipped: result.skipped.length })}</p>
              </AlertDescription>
            </Alert>
            {skippedGroups.length > 0 && (
              <ul className="list-disc space-y-0.5 ps-5 text-xs text-muted-foreground">
                {skippedGroups.map((g) => (
                  <li key={g.reason}>{t("skippedGroup", { count: g.count, reason: t(skipReasonKey(g.reason)) })}</li>
                ))}
              </ul>
            )}

            {result.imported > 0 && !memGone && (
              <div className="space-y-2 border-t border-border pt-3" data-testid="chat-import-memories">
                <p className="text-sm font-medium text-foreground">{t("memTitle")}</p>
                <p className="text-xs text-muted-foreground">{t("memBody")}</p>
                {memSaved !== null && <p className="text-xs text-foreground">{t("memSaved", { saved: memSaved })}</p>}
                {!memPreview && memSaved === null && (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={memBusy}
                    onClick={() => void suggestMemories()}
                  >
                    {memBusy ? t("memSuggesting") : t("memSuggest")}
                  </Button>
                )}
                {memPreview && memPreview.facts.length === 0 && (
                  <p className="text-xs text-muted-foreground">{t("memNone")}</p>
                )}
                {memPreview && memPreview.facts.length > 0 && (
                  <>
                    <ul className="max-h-72 space-y-1 overflow-y-auto rounded-lg border border-border p-2">
                      {memPreview.facts.map((f) => {
                        const selectable = isFactSelectable(f);
                        return (
                          <li key={f.id} className="flex items-start gap-2 rounded px-1.5 py-1">
                            <input
                              id={`chat-import-mem-${f.id}`}
                              type="checkbox"
                              className="mt-1"
                              checked={selectable && memSelection.has(f.id)}
                              disabled={!selectable}
                              onChange={() => setMemSelection((s) => toggleFactSelection(s, f.id))}
                            />
                            <label
                              htmlFor={`chat-import-mem-${f.id}`}
                              className={cn(
                                "min-w-0 flex-1 break-words text-sm",
                                selectable ? "text-foreground" : "text-muted-foreground"
                              )}
                            >
                              {f.text}
                              {f.duplicateOf && (
                                <Badge variant="secondary" className="ms-2">
                                  {t("memDuplicate")}
                                </Badge>
                              )}
                              {f.rejectedReason && (
                                <Badge variant="outline" className="ms-2">
                                  {t("memRejected")}
                                </Badge>
                              )}
                            </label>
                          </li>
                        );
                      })}
                    </ul>
                    <div className="flex justify-end">
                      <Button
                        type="button"
                        size="sm"
                        disabled={memBusy || chosenFacts.length === 0}
                        onClick={() => void saveMemories()}
                      >
                        {memBusy ? t("memSaving") : t("memSave", { count: chosenFacts.length })}
                      </Button>
                    </div>
                  </>
                )}
              </div>
            )}
            {result.imported > 0 && memGone && <p className="text-xs text-muted-foreground">{t("memUnavailable")}</p>}

            <div className="flex flex-wrap items-center justify-end gap-2">
              <Button type="button" size="sm" variant="ghost" onClick={close}>
                {t("close")}
              </Button>
              <Button type="button" size="sm" variant="outline" onClick={reset}>
                {t("importAnother")}
              </Button>
            </div>
          </div>
        )}

        {chats && chats.length > 0 && (
          <div className="space-y-2 border-t border-border pt-4" data-testid="imported-chats">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm font-medium text-foreground">{t("yourChatsHeading")}</p>
              <div className="flex flex-wrap items-center gap-1">
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => void download(api.exportAllImportedChats("md"))}
                >
                  <Download size={14} aria-hidden="true" /> {t("exportAllMd")}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => void download(api.exportAllImportedChats("json"))}
                >
                  <Download size={14} aria-hidden="true" /> {t("exportAllJson")}
                </Button>
              </div>
            </div>
            <ul className="space-y-2">
              {chats.map((c) => (
                <li key={c.id} className="rounded-lg border border-border p-3">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <p className="break-words text-sm font-medium text-foreground">{c.title}</p>
                      <p className="text-xs text-muted-foreground">
                        {t(sourceLabelKey(c.source))} {"·"} {t("messagesCount", { count: c.messageCount })}
                        {c.importedAt ? ` · ${t("importedOn", { date: shortDate(c.importedAt, locale) })}` : ""}
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-1">
                      <Button type="button" size="sm" variant="ghost" onClick={() => void toggleView(c.id)}>
                        {openId === c.id ? t("hide") : t("view")}
                      </Button>
                      <span className="text-xs text-muted-foreground">{t("exportOne")}</span>
                      {EXPORT_FORMATS.map((f) => (
                        <Button
                          key={f.id}
                          type="button"
                          size="sm"
                          variant="ghost"
                          aria-label={`${t("exportOne")} ${t(f.labelKey)}`}
                          onClick={() => void download(api.exportImportedChat(c.id, f.id))}
                        >
                          {f.id}
                        </Button>
                      ))}
                      {confirmId === c.id ? (
                        <>
                          <Button type="button" size="sm" variant="destructive" onClick={() => void remove(c.id)}>
                            {t("confirmDelete")}
                          </Button>
                          <Button type="button" size="sm" variant="ghost" onClick={() => setConfirmId(null)}>
                            {t("cancel")}
                          </Button>
                        </>
                      ) : (
                        <Button type="button" size="sm" variant="ghost" onClick={() => setConfirmId(c.id)}>
                          <Trash2 size={14} aria-hidden="true" /> {t("delete")}
                        </Button>
                      )}
                    </div>
                  </div>
                  {openId === c.id && (
                    <div
                      className="mt-3 max-h-96 space-y-3 overflow-y-auto rounded-md border border-border bg-muted/30 p-3"
                      data-testid="imported-chat-transcript"
                    >
                      {!openChat && <p className="text-xs text-muted-foreground">{t("viewLoading")}</p>}
                      {openChat && openChat.truncated && (
                        <p className="text-xs text-muted-foreground">{t("viewShortened")}</p>
                      )}
                      {openChat &&
                        openChat.messages.map((m, i) => (
                          <div key={i}>
                            <p className="text-xs font-medium text-muted-foreground">
                              {m.role === "user" ? t("roleYou") : t("roleAssistant")}
                            </p>
                            <p className="whitespace-pre-wrap break-words text-sm text-foreground">{m.content}</p>
                            {m.attachments && m.attachments.length > 0 && (
                              <p className="text-xs text-muted-foreground">
                                {t("attachmentsLine", { names: m.attachments.join(", ") })}
                              </p>
                            )}
                          </div>
                        ))}
                    </div>
                  )}
                </li>
              ))}
            </ul>
            {chats.length >= IMPORTED_CHAT_LIST_LIMIT && (
              <p className="text-xs text-muted-foreground" data-testid="imported-chats-capped">
                {t("yourChatsNewestNote", { count: IMPORTED_CHAT_LIST_LIMIT })}
              </p>
            )}
          </div>
        )}
        {listFailed && !chats && <p className="text-xs text-muted-foreground">{t("yourChatsLoadFailed")}</p>}
      </CardContent>
    </Card>
  );
}

"use client";

import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Copy, Upload } from "lucide-react";
import { api } from "../lib/api";
import Select from "../components/Select";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Textarea } from "../components/ui/textarea";
import { useTranslations } from "../lib/i18n";
import {
  IMPORT_SHARED_TARGET,
  IMPORT_SOURCES,
  buildImportPrompt,
  buildImportRequest,
  defaultSelection,
  groupSkipped,
  importReasonKey,
  importSourceLabel,
  isSelectable,
  overflow,
  roomFor,
  selectAll,
  selectNone,
  selectedFacts,
  toggleSelection,
  type ImportPreview,
  type ImportResult,
  type ImportSource,
} from "./memoryImport";
import type { MemoryAgent, MemoryFact } from "./memoryTypes";

type Stage = "paste" | "review" | "done";

/**
 * "Import from another assistant": copy a prompt into ChatGPT / Claude / Gemini, paste
 * its answer, review the lines, keep the ones you want. Backed by core's
 * /api/memory/import (preview parses and screens with no side effects; import saves
 * through the same limits as add-a-fact). The card renders nothing when the server
 * says the feature is off (404 on the limits probe, or on any later call).
 */
export default function ImportMemoryCard({
  agents,
  facts,
  onImported,
}: {
  agents: MemoryAgent[];
  /** The user's existing memories, to work out how much room one assistant has left. */
  facts: MemoryFact[];
  onImported: () => void;
}) {
  const t = useTranslations("memoryPage");
  const [limits, setLimits] = useState<{ maxFacts: number; maxChars: number } | null>(null);
  const [gone, setGone] = useState(false);
  const [open, setOpen] = useState(false);
  const [stage, setStage] = useState<Stage>("paste");
  const [source, setSource] = useState<ImportSource>("chatgpt");
  const [text, setText] = useState("");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [selection, setSelection] = useState<Set<number>>(new Set<number>());
  const [target, setTarget] = useState<string>(IMPORT_SHARED_TARGET);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    api
      .getMemoryImportLimits()
      .then((l: { maxFacts: number; maxChars: number }) => {
        if (alive) setLimits(l);
      })
      .catch(() => {
        if (alive) setGone(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  const prompt = useMemo(() => buildImportPrompt(source), [source]);
  const chosen = useMemo(() => selectedFacts(preview?.facts, selection), [preview, selection]);
  const room = useMemo(() => roomFor(target, preview?.capacity, facts), [target, preview, facts]);
  const over = overflow(chosen.length, room);

  if (gone || !limits) return null;

  const maxChars = limits.maxChars;
  const tooLong = text.length > maxChars;

  const copyPrompt = async () => {
    setCopyFailed(false);
    try {
      await navigator.clipboard.writeText(prompt);
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    } catch {
      setCopied(false);
      setCopyFailed(true);
    }
  };

  const reset = () => {
    setText("");
    setPreview(null);
    setSelection(selectNone());
    setResult(null);
    setStage("paste");
  };

  const close = () => {
    reset();
    setOpen(false);
  };

  const review = async () => {
    if (busy || !text.trim() || tooLong) return;
    setBusy(true);
    try {
      const p: ImportPreview = await api.previewMemoryImport(text, source);
      setPreview(p);
      setSelection(defaultSelection(p.facts));
      setStage("review");
    } catch (err: any) {
      // 404: switched off since the page loaded. Anything else was already shown by toJson.
      if (err?.status === 404) setGone(true);
    } finally {
      setBusy(false);
    }
  };

  const runImport = async () => {
    if (busy || !preview) return;
    const request = buildImportRequest(preview.facts, selection, target, source);
    if (request.facts.length === 0) return;
    setBusy(true);
    try {
      const r: ImportResult = await api.importMemory(request.facts, request.source);
      setResult(r);
      setStage("done");
      onImported();
    } catch (err: any) {
      if (err?.status === 404) setGone(true);
    } finally {
      setBusy(false);
    }
  };

  const skippedGroups = groupSkipped(result?.skipped);
  const total = result ? result.saved + (result.skipped?.length ?? 0) : 0;

  return (
    <Card elevation="raised" data-testid="memory-import">
      <CardHeader>
        <CardTitle>{t("importHeading")}</CardTitle>
        <CardDescription>{t("importIntro")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {!open && (
          <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
            <Upload size={14} aria-hidden="true" /> {t("importStart")}
          </Button>
        )}

        {open && stage === "paste" && (
          <>
            <div className="space-y-2">
              <p className="text-sm font-medium text-foreground">{t("importStep1Title")}</p>
              <p className="text-xs text-muted-foreground">{t("importStep1Body")}</p>
              <div className="flex flex-wrap gap-2" role="group" aria-label={t("importSourceLabel")}>
                {IMPORT_SOURCES.map((s) => (
                  <Button
                    key={s.id}
                    type="button"
                    size="sm"
                    variant={source === s.id ? "default" : "outline"}
                    aria-pressed={source === s.id}
                    onClick={() => {
                      setSource(s.id);
                      setCopied(false);
                      setCopyFailed(false);
                    }}
                  >
                    {s.label}
                  </Button>
                ))}
              </div>
              <Textarea
                readOnly
                rows={8}
                value={prompt}
                aria-label={t("importPromptLabel", { source: importSourceLabel(source) })}
                className="font-mono text-xs"
              />
              <div className="flex flex-wrap items-center gap-2">
                <Button type="button" size="sm" variant="outline" onClick={() => void copyPrompt()}>
                  {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}{" "}
                  {copied ? t("importCopied") : t("importCopyPrompt")}
                </Button>
                {copyFailed && <span className="text-xs text-destructive">{t("importCopyFailed")}</span>}
              </div>
            </div>

            <div className="space-y-2 border-t border-border pt-4">
              <p className="text-sm font-medium text-foreground">{t("importStep2Title")}</p>
              <p className="text-xs text-muted-foreground">{t("importStep2Body")}</p>
              <Alert variant="warning">
                <AlertTriangle aria-hidden="true" />
                <AlertDescription>{t("importSensitiveWarning")}</AlertDescription>
              </Alert>
              <Textarea
                rows={8}
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder={t("importPastePlaceholder")}
                aria-label={t("importStep2Title")}
              />
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className={`text-xs tabular-nums ${tooLong ? "text-destructive" : "text-muted-foreground"}`}>
                  {t("charCount", { count: text.length, max: maxChars })}
                </span>
                <div className="flex items-center gap-2">
                  <Button type="button" size="sm" variant="ghost" onClick={close}>
                    {t("importClose")}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    disabled={busy || !text.trim() || tooLong}
                    onClick={() => void review()}
                  >
                    {busy ? t("importReviewing") : t("importReviewButton")}
                  </Button>
                </div>
              </div>
            </div>
          </>
        )}

        {open && stage === "review" && preview && (
          <div className="space-y-3">
            <p className="text-sm font-medium text-foreground">{t("importStep3Title")}</p>
            <p className="text-xs text-muted-foreground">
              {t("importStats", {
                kept: preview.stats.kept,
                duplicates: preview.stats.duplicates,
                rejected: preview.stats.rejected,
                lines: preview.stats.lines,
              })}
            </p>

            {preview.facts.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("importNothingFound")}</p>
            ) : (
              <>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs text-muted-foreground">{t("addFactWho")}</span>
                  <Select
                    aria-label={t("addFactWho")}
                    value={target}
                    onChange={(e) => setTarget(e.target.value)}
                    searchable={agents.length > 8}
                    options={[
                      { value: IMPORT_SHARED_TARGET, label: t("addFactShared") },
                      ...agents.map((a) => ({ value: a.id, label: t("addFactAgent", { name: a.name }) })),
                    ]}
                  />
                </div>
                {target === IMPORT_SHARED_TARGET && (
                  <p className="text-xs text-muted-foreground">{t("addFactSharedHint")}</p>
                )}

                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={() => setSelection(selectAll(preview.facts))}
                  >
                    {t("importSelectAll")}
                  </Button>
                  <Button type="button" size="sm" variant="outline" onClick={() => setSelection(selectNone())}>
                    {t("importSelectNone")}
                  </Button>
                  <span className="text-xs text-muted-foreground">
                    {t("importSelectedCount", { count: chosen.length })}
                  </span>
                </div>

                <ul
                  className="max-h-96 space-y-1 overflow-y-auto rounded-lg border border-border p-2"
                  data-testid="memory-import-list"
                >
                  {preview.facts.map((f) => {
                    const selectable = isSelectable(f);
                    return (
                      <li key={f.id} className="flex items-start gap-2 rounded px-1.5 py-1">
                        <input
                          id={`memory-import-fact-${f.id}`}
                          type="checkbox"
                          className="mt-1"
                          checked={selectable && selection.has(f.id)}
                          disabled={!selectable}
                          onChange={() => setSelection((s) => toggleSelection(s, f.id))}
                        />
                        <label
                          htmlFor={`memory-import-fact-${f.id}`}
                          className={`min-w-0 flex-1 break-words text-sm ${selectable ? "text-foreground" : "text-muted-foreground"}`}
                        >
                          {f.text}
                          {f.duplicateOf && (
                            <Badge variant="secondary" className="ms-2">
                              {t("importDuplicate")}
                            </Badge>
                          )}
                          {f.rejectedReason && (
                            <Badge variant="outline" className="ms-2">
                              {t(importReasonKey(f.rejectedReason))}
                            </Badge>
                          )}
                          {f.duplicateOf && (
                            <span className="mt-0.5 block text-xs text-muted-foreground">
                              {t("importDuplicateOf", { text: f.duplicateOf })}
                            </span>
                          )}
                        </label>
                      </li>
                    );
                  })}
                </ul>

                {over > 0 && (
                  <Alert variant="warning">
                    <AlertTriangle aria-hidden="true" />
                    <AlertDescription>
                      {room === 0 ? t("importRoomFull") : t("importRoomWarning", { room: room ?? 0, over })}
                    </AlertDescription>
                  </Alert>
                )}
              </>
            )}

            <div className="flex flex-wrap items-center justify-end gap-2">
              <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={reset}>
                {t("importBack")}
              </Button>
              <Button type="button" size="sm" disabled={busy || chosen.length === 0} onClick={() => void runImport()}>
                <Upload size={14} aria-hidden="true" />{" "}
                {busy ? t("importing") : t("importButton", { count: chosen.length })}
              </Button>
            </div>
          </div>
        )}

        {open && stage === "done" && result && (
          <div className="space-y-3" data-testid="memory-import-result">
            <Alert variant={result.saved > 0 ? "success" : "warning"}>
              {result.saved === 0 && <AlertTriangle aria-hidden="true" />}
              <AlertDescription>
                <p className="font-medium">{t("importDoneTitle")}</p>
                <p>{t("importDoneBody", { saved: result.saved, total, skipped: result.skipped?.length ?? 0 })}</p>
              </AlertDescription>
            </Alert>
            {skippedGroups.length > 0 && (
              <ul className="list-disc space-y-0.5 ps-5 text-xs text-muted-foreground">
                {skippedGroups.map((g) => (
                  <li key={g.reason}>
                    {t("importSkippedGroup", { count: g.count, reason: t(importReasonKey(g.reason)) })}
                  </li>
                ))}
              </ul>
            )}
            <p className="text-xs text-muted-foreground">{t("importDoneDelete")}</p>
            <div className="flex flex-wrap items-center justify-end gap-2">
              <Button type="button" size="sm" variant="ghost" onClick={close}>
                {t("importClose")}
              </Button>
              <Button type="button" size="sm" variant="outline" onClick={reset}>
                {t("importAnother")}
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

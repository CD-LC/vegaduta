"use client";

import { useCallback, useRef, useState, type ChangeEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AlertCircle, ArrowLeft, Bot, KeyRound, Loader2, Sparkles, Upload, Wrench, Info } from "lucide-react";
import { api } from "../../lib/api";
import type { DetectedImportSource, ImportPreviewResponse, ImportSource } from "../../lib/api/agentImport";
import { useTranslations } from "../../lib/i18n";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState } from "../../components/ax/page-kit";
import Select from "../../components/Select";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import ImportReportView from "./ImportReportView";
import {
  MAX_IMPORT_BYTES,
  MAX_NAME_LENGTH,
  buildEnvelope,
  detectSourceFromText,
  exceedsImportLimit,
  parseExport,
} from "./importDetect";
import { classifyImportError, normalizeReport, truncateText, type ImportErrorKind } from "./importReport";

/**
 * Import agent - migration kit. Paste or upload an export from OpenAI Assistants,
 * OpenAI Agent Builder (Agents SDK style JSON) or Flowise, preview exactly what
 * converts, then create an UNPUBLISHED draft agent.
 *
 * Privacy: the export is held only in this component's state and sent in the
 * request body. It is never logged, put in a URL, or written to browser storage.
 * Credentials are never imported by the server and nothing here asks for any.
 *
 * Failure handling is deliberately quiet and inline (the api client routes
 * 400/403/404/413 through toJsonQuietOn): a 404 means import is switched off or
 * this core predates it, which is a calm state, not an error.
 */

const INSTRUCTIONS_PREVIEW_CHARS = 1200;

type PageError = { kind: ImportErrorKind | "local"; message: string };

// What a preview was made from. The create button is only live while the
// textarea and source still match it, so the draft always matches the report.
type PreviewState = {
  source: ImportSource;
  text: string;
  payload: Record<string, unknown>;
  data: ImportPreviewResponse;
};

const SOURCE_LABEL_KEY: Record<ImportSource, string> = {
  auto: "sourceAuto",
  "openai-assistant": "sourceOpenaiAssistant",
  "openai-agents": "sourceOpenaiAgents",
  flowise: "sourceFlowise",
};

export default function AgentImportPage() {
  const t = useTranslations("agentImport");
  const router = useRouter();
  const fileRef = useRef<HTMLInputElement>(null);
  const [source, setSource] = useState<ImportSource>("auto");
  const [text, setText] = useState("");
  const [detected, setDetected] = useState<DetectedImportSource | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [busy, setBusy] = useState<null | "preview" | "create">(null);
  const [error, setError] = useState<PageError | null>(null);
  const [unavailable, setUnavailable] = useState(false);

  const sourceOptions = (Object.keys(SOURCE_LABEL_KEY) as ImportSource[]).map((value) => ({
    value,
    label: t(SOURCE_LABEL_KEY[value]),
  }));

  const applyText = useCallback((next: string) => {
    setText(next);
    setError(null);
    // The guess is only a hint: the selector stays on what the person chose
    // (Auto-detect by default) so the server's own detection is never overridden.
    setDetected(exceedsImportLimit(next) ? null : detectSourceFromText(next));
  }, []);

  const onSourceChange = (e: any) => {
    const value = (e?.target?.value ?? e) as ImportSource;
    setSource(value);
    setError(null);
  };

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Reset so choosing the same file again still fires onChange.
    e.target.value = "";
    if (!file) return;
    if (file.size > MAX_IMPORT_BYTES) {
      setError({ kind: "local", message: t("errTooLarge") });
      return;
    }
    if (!/\.json$/i.test(file.name) && !/json/i.test(file.type)) {
      setError({ kind: "local", message: t("errNotJsonFile") });
      return;
    }
    try {
      applyText(await file.text());
      setFileName(file.name);
    } catch {
      setError({ kind: "local", message: t("errFileRead") });
    }
  };

  const failWith = (err: any) => {
    const info = classifyImportError(err);
    if (info.kind === "unavailable") {
      setUnavailable(true);
      return;
    }
    if (info.kind === "too-large") {
      setError({ kind: info.kind, message: t("errServerTooLarge") });
      return;
    }
    const fallback = info.kind === "invalid" ? t("errInvalidFallback") : t("errGeneric");
    setError({ kind: info.kind, message: info.message || fallback });
  };

  const runPreview = async () => {
    if (busy) return;
    setError(null);
    if (exceedsImportLimit(text)) {
      setError({ kind: "local", message: t("errTooLarge") });
      return;
    }
    const parsed = parseExport(text);
    if (!parsed.payload) {
      const key =
        parsed.reason === "empty" ? "errEmpty" : parsed.reason === "not-object" ? "errNotObject" : "errInvalidJson";
      setError({ kind: "local", message: t(key) });
      return;
    }
    setBusy("preview");
    try {
      const data = await api.previewAgentImport(buildEnvelope(source, parsed.payload));
      const safe: ImportPreviewResponse = { ...data, report: normalizeReport(data?.report) };
      setPreview({ source, text, payload: parsed.payload, data: safe });
      setName(String(data?.agent?.name || "").slice(0, MAX_NAME_LENGTH));
    } catch (err) {
      failWith(err);
    } finally {
      setBusy(null);
    }
  };

  const runCreate = async () => {
    if (busy || !preview) return;
    setError(null);
    setBusy("create");
    try {
      const res = await api.importAgent(buildEnvelope(preview.source, preview.payload, name));
      if (res?.agentId) {
        // Stay "busy" while the route changes so a second click cannot create a duplicate.
        router.push(`/agents?agentId=${encodeURIComponent(res.agentId)}`);
        return;
      }
      setError({ kind: "other", message: t("errGeneric") });
    } catch (err) {
      failWith(err);
    }
    setBusy(null);
  };

  const header = (
    <PageHeader
      title={t("title")}
      subtitle={t("subtitle")}
      actions={
        <Button asChild variant="outline" size="sm">
          <Link href="/agents">
            <ArrowLeft size={14} aria-hidden="true" /> {t("backToAgents")}
          </Link>
        </Button>
      }
    />
  );

  if (unavailable) {
    return (
      <AppShell eyebrow={t("eyebrow")}>
        {header}
        <EmptyState
          icon={Bot}
          title={t("unavailableTitle")}
          description={t("unavailableBody")}
          action={
            <Button asChild size="sm">
              <Link href="/agents">{t("createManually")}</Link>
            </Button>
          }
        />
      </AppShell>
    );
  }

  const isCurrent = !!preview && preview.text === text && preview.source === source;
  const agent = preview?.data.agent;
  const providerModel = agent ? [agent.modelProvider, agent.model].filter(Boolean).join(" / ") : "";
  const instructions = truncateText(agent?.instructions || "", INSTRUCTIONS_PREVIEW_CHARS);
  const detectedLabel = detected ? t(SOURCE_LABEL_KEY[detected]) : "";
  const previewSourceLabel = preview ? t(SOURCE_LABEL_KEY[preview.data.detectedSource] || "sourceAuto") : "";

  return (
    <AppShell eyebrow={t("eyebrow")}>
      {header}
      <div className="mx-auto w-full max-w-3xl space-y-6">
        <Card className="gap-3 py-5">
          <CardHeader className="px-4 sm:px-6">
            <CardTitle className="text-sm">{t("notesTitle")}</CardTitle>
          </CardHeader>
          <CardContent className="px-4 sm:px-6">
            <ul className="space-y-2 text-sm text-muted-foreground">
              <li className="flex gap-2">
                <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>{t("noteDraft")}</span>
              </li>
              <li className="flex gap-2">
                <KeyRound className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>{t("noteCredentials")}</span>
              </li>
              <li className="flex gap-2">
                <Wrench className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>{t("noteFunctionTools")}</span>
              </li>
            </ul>
          </CardContent>
        </Card>

        <Card className="gap-4 py-5">
          <CardContent className="space-y-4 px-4 sm:px-6">
            <div className="space-y-2">
              <Label htmlFor="agent-import-source">{t("sourceLabel")}</Label>
              <Select
                id="agent-import-source"
                aria-label={t("sourceLabel")}
                className="w-full sm:max-w-xs"
                value={source}
                onChange={onSourceChange}
                options={sourceOptions}
                disabled={busy !== null}
              />
              {detected && source === "auto" && (
                <p className="text-xs text-muted-foreground" data-testid="import-detected-hint">
                  {t("detectedHint", { source: detectedLabel })}
                </p>
              )}
              {source === "openai-agents" && <p className="text-xs text-muted-foreground">{t("agentBuilderHint")}</p>}
            </div>

            <div className="space-y-2">
              <Label htmlFor="agent-import-payload">{t("pasteLabel")}</Label>
              <Textarea
                id="agent-import-payload"
                value={text}
                onChange={(e) => {
                  setFileName(null);
                  applyText(e.target.value);
                }}
                placeholder={t("pastePlaceholder")}
                spellCheck={false}
                autoComplete="off"
                autoCapitalize="off"
                className="min-h-48 max-h-96 overflow-auto font-mono text-xs"
                disabled={busy !== null}
              />
              <div className="flex flex-wrap items-center gap-3">
                <input
                  ref={fileRef}
                  type="file"
                  accept=".json,application/json"
                  className="sr-only"
                  tabIndex={-1}
                  aria-label={t("uploadButton")}
                  data-testid="import-file-input"
                  onChange={onFile}
                />
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy !== null}
                  onClick={() => fileRef.current?.click()}
                >
                  <Upload size={14} aria-hidden="true" /> {t("uploadButton")}
                </Button>
                {fileName && (
                  <span className="min-w-0 break-all text-xs text-muted-foreground">
                    {t("fileLoaded", { name: fileName })}
                  </span>
                )}
              </div>
              <p className="text-xs text-muted-foreground">{t("sizeLimitHint")}</p>
            </div>

            <Button type="button" onClick={runPreview} disabled={busy !== null || !text.trim()}>
              {busy === "preview" ? (
                <Loader2 className="animate-spin" aria-hidden="true" />
              ) : (
                <Sparkles aria-hidden="true" />
              )}
              {busy === "preview" ? t("previewing") : t("previewButton")}
            </Button>
          </CardContent>
        </Card>

        {error && error.kind === "upgrade" && (
          <Alert variant="warning" data-testid="import-upgrade">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              <p className="font-medium">{t("upgradeTitle")}</p>
              <p>{error.message || t("upgradeBody")}</p>
              <Button asChild size="sm" variant="outline" className="mt-2">
                <Link href="/admin/billing">
                  <Sparkles size={14} aria-hidden="true" /> {t("upgradeCta")}
                </Link>
              </Button>
            </AlertDescription>
          </Alert>
        )}
        {error && error.kind !== "upgrade" && (
          <Alert variant="error" data-testid="import-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              <p className="break-words">{error.message}</p>
            </AlertDescription>
          </Alert>
        )}

        {preview && agent && (
          <Card className="gap-4 py-5" data-testid="import-preview">
            <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 px-4 sm:px-6">
              <CardTitle className="text-base">{t("previewHeading")}</CardTitle>
              <Badge variant="secondary">{t("detectedAs", { source: previewSourceLabel })}</Badge>
            </CardHeader>
            <CardContent className="space-y-5 px-4 sm:px-6">
              {!isCurrent && (
                <Alert variant="info" data-testid="import-stale">
                  <Info aria-hidden="true" />
                  <AlertDescription>{t("previewStale")}</AlertDescription>
                </Alert>
              )}

              <div className="space-y-2">
                <Label htmlFor="agent-import-name">{t("nameLabel")}</Label>
                <Input
                  id="agent-import-name"
                  value={name}
                  maxLength={MAX_NAME_LENGTH}
                  onChange={(e) => setName(e.target.value)}
                  disabled={busy !== null}
                />
              </div>

              <dl className="grid gap-3 text-sm sm:grid-cols-3">
                <div className="min-w-0">
                  <dt className="text-xs text-muted-foreground">{t("modelLabel")}</dt>
                  <dd className="break-words text-foreground">{providerModel || t("modelNotSet")}</dd>
                </div>
                <div className="min-w-0">
                  <dt className="text-xs text-muted-foreground">{t("temperatureLabel")}</dt>
                  <dd className="text-foreground">{agent.temperature ?? t("notSet")}</dd>
                </div>
                <div className="min-w-0">
                  <dt className="text-xs text-muted-foreground">{t("topPLabel")}</dt>
                  <dd className="text-foreground">{agent.topP ?? t("notSet")}</dd>
                </div>
              </dl>

              <div className="space-y-2">
                <p className="text-sm font-medium text-foreground">{t("instructionsLabel")}</p>
                {instructions.text ? (
                  <>
                    <pre
                      // A scrollable text region must be keyboard-focusable (WCAG 2.1.1).
                      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
                      tabIndex={0}
                      aria-label={t("instructionsLabel")}
                      className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/40 p-3 text-xs text-foreground"
                    >
                      {instructions.text}
                    </pre>
                    {instructions.truncated && (
                      <p className="text-xs text-muted-foreground">
                        {t("instructionsTruncated", { count: INSTRUCTIONS_PREVIEW_CHARS })}
                      </p>
                    )}
                  </>
                ) : (
                  <p className="text-sm text-muted-foreground">{t("instructionsEmpty")}</p>
                )}
              </div>

              {(agent.suggestedTools.length > 0 || agent.suggestedCapabilities.length > 0) && (
                <div className="space-y-2" data-testid="import-suggestions">
                  <p className="text-sm font-medium text-foreground">{t("suggestionsTitle")}</p>
                  <p className="text-xs text-muted-foreground">{t("suggestionsBody")}</p>
                  {agent.suggestedTools.length > 0 && (
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">{t("suggestedTools")}</span>
                      {agent.suggestedTools.map((tool) => (
                        <Badge key={tool} variant="outline">
                          {tool}
                        </Badge>
                      ))}
                    </div>
                  )}
                  {agent.suggestedCapabilities.length > 0 && (
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">{t("suggestedCapabilities")}</span>
                      {agent.suggestedCapabilities.map((cap) => (
                        <Badge key={cap} variant="outline">
                          {cap}
                        </Badge>
                      ))}
                    </div>
                  )}
                </div>
              )}

              <ImportReportView report={preview.data.report} />

              <div className="flex flex-col gap-2 border-t border-border pt-4 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-xs text-muted-foreground">{t("createNote")}</p>
                <Button type="button" onClick={runCreate} disabled={busy !== null || !isCurrent}>
                  {busy === "create" ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
                  {busy === "create" ? t("creating") : t("createButton")}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </AppShell>
  );
}

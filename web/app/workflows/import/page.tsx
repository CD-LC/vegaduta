"use client";

import { useCallback, useEffect, useRef, useState, type ChangeEvent, type DragEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AlertCircle, ArrowLeft, FileUp, Info, KeyRound, Loader2, Sparkles, Upload, Workflow } from "lucide-react";
import { api } from "../../lib/api";
import type {
  DetectedPortabilitySource,
  PortabilityPlaceholder,
  PortabilitySource,
  WorkflowImportPreview,
} from "../../lib/api/workflowPortability";
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
import { classifyImportError, normalizeReport, type ImportErrorKind } from "../../agents/import/importReport";
import WorkflowImportReportView from "./WorkflowImportReportView";
import {
  MAX_WORKFLOW_IMPORT_BYTES,
  MAX_WORKFLOW_NAME_LENGTH,
  buildPortabilityEnvelope,
  cleanMappings,
  detectWorkflowFormat,
  exceedsWorkflowImportLimit,
  fidelityBadgeVariant,
  fidelityPercent,
  groupPlaceholders,
  looksLikeWorkflowFile,
  nameFromFileName,
  pickerSourceFor,
  sameMappings,
  unmappedCount,
} from "./workflowImportHelpers";

/**
 * Import workflow - migration funnel. Paste or drop a workflow file (a VegaDuta export, an n8n export or a Dify app
 * DSL), preview exactly what converts - with a fidelity summary, validation issues and the agents / knowledge bases /
 * credentials it refers to - then create an UNPUBLISHED draft. Mirrors /agents/import.
 *
 * Privacy: the file is held only in this component's state and sent in the request body. It is never logged, put in a
 * URL, or written to browser storage. Credentials are never imported by the server and nothing here asks for any.
 *
 * Failure handling is deliberately quiet and inline (the api client routes 400/403/404/413 through toJsonQuietOn): a
 * 404 means import is switched off or this core predates it, which is a calm state, not an error.
 */

type PageError = { kind: ImportErrorKind | "local"; message: string };

// What a preview was made from. The create button is only live while the textarea, source and mapping still match
// it, so the draft always matches the report.
type PreviewState = {
  source: PortabilitySource;
  text: string;
  mappings: Record<string, string>;
  data: WorkflowImportPreview;
};

type Choice = { value: string; label: string };
type Choices = { agents: Choice[]; knowledge: Choice[]; workflows: Choice[] };

const SOURCE_LABEL_KEY: Record<PortabilitySource, string> = {
  auto: "sourceAuto",
  vegaduta: "sourceVegaduta",
  n8n: "sourceN8n",
  dify: "sourceDify",
};

const FIDELITY_LABEL_KEY: Record<string, string> = {
  high: "fidelityHigh",
  partial: "fidelityPartial",
  low: "fidelityLow",
};

const KIND_LABEL_KEY: Record<string, string> = {
  agent: "kindAgent",
  kb: "kindKb",
  workflow: "kindWorkflow",
  group: "kindGroup",
  device: "kindDevice",
  destination: "kindDestination",
  credential: "kindCredential",
  secret: "kindSecret",
};

const KIND_HINT_KEY: Record<string, string> = {
  destination: "hintDestination",
  credential: "hintCredential",
  secret: "hintSecret",
  group: "hintGroup",
  device: "hintDevice",
};

function toChoices(list: unknown): Choice[] {
  if (!Array.isArray(list)) return [];
  return list
    .filter((item: any) => item && typeof item.id === "string")
    .map((item: any) => ({ value: item.id, label: String(item.name || item.title || item.id) }));
}

export default function WorkflowImportPage() {
  const t = useTranslations("workflowPortability");
  const router = useRouter();
  const fileRef = useRef<HTMLInputElement>(null);
  const [source, setSource] = useState<PortabilitySource>("auto");
  const [text, setText] = useState("");
  const [detected, setDetected] = useState<DetectedPortabilitySource | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [name, setName] = useState("");
  const [mappings, setMappings] = useState<Record<string, string>>({});
  const [choices, setChoices] = useState<Choices>({ agents: [], knowledge: [], workflows: [] });
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [busy, setBusy] = useState<null | "preview" | "create">(null);
  const [error, setError] = useState<PageError | null>(null);
  const [unavailable, setUnavailable] = useState(false);

  const sourceOptions = (Object.keys(SOURCE_LABEL_KEY) as PortabilitySource[]).map((value) => ({
    value,
    label: t(SOURCE_LABEL_KEY[value]),
  }));

  const applyText = useCallback((next: string) => {
    setText(next);
    setError(null);
    // The guess is only a hint: the selector stays on what the person chose (Auto-detect by default) so the
    // server's own detection is never overridden.
    setDetected(exceedsWorkflowImportLimit(next) ? null : detectWorkflowFormat(next));
  }, []);

  const onSourceChange = (e: any) => {
    const value = (e?.target?.value ?? e) as PortabilitySource;
    setSource(value);
    setError(null);
  };

  const loadFile = async (file: File) => {
    if (file.size > MAX_WORKFLOW_IMPORT_BYTES) {
      setError({ kind: "local", message: t("errTooLarge") });
      return;
    }
    if (!looksLikeWorkflowFile(file.name, file.type)) {
      setError({ kind: "local", message: t("errNotWorkflowFile") });
      return;
    }
    try {
      applyText(await file.text());
      setFileName(file.name);
    } catch {
      setError({ kind: "local", message: t("errFileRead") });
    }
  };

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Reset so choosing the same file again still fires onChange.
    e.target.value = "";
    if (file) await loadFile(file);
  };

  const onDrop = async (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragging(false);
    if (busy) return;
    const file = e.dataTransfer?.files?.[0];
    if (file) await loadFile(file);
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

  // The existing resources a placeholder can be pointed at. Loaded once, and only when a preview needs them; a failed
  // load leaves a list empty (the person can still pick later on the node), it never blocks the import.
  const needed: Array<ReturnType<typeof pickerSourceFor>> = preview ? preview.data.placeholders.filter((p) => p.mappable).map((p) => pickerSourceFor(p.kind)) : [];
  const needsAgents = needed.includes("agents");
  const needsKnowledge = needed.includes("knowledge");
  const needsWorkflows = needed.includes("workflows");
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [agents, knowledge, workflows] = await Promise.all([
          needsAgents ? api.listAgents() : Promise.resolve([]),
          needsKnowledge ? api.listCollections() : Promise.resolve([]),
          needsWorkflows ? api.listWorkflows() : Promise.resolve([]),
        ]);
        if (!cancelled) {
          setChoices({ agents: toChoices(agents), knowledge: toChoices(knowledge), workflows: toChoices(workflows) });
        }
      } catch {
        // Leave the lists as they are: mapping is optional.
      }
    };
    if (needsAgents || needsKnowledge || needsWorkflows) void load();
    return () => {
      cancelled = true;
    };
  }, [needsAgents, needsKnowledge, needsWorkflows]);

  const runPreview = async () => {
    if (busy) return;
    setError(null);
    if (!text.trim()) {
      setError({ kind: "local", message: t("errEmpty") });
      return;
    }
    if (exceedsWorkflowImportLimit(text)) {
      setError({ kind: "local", message: t("errTooLarge") });
      return;
    }
    setBusy("preview");
    try {
      const picks = cleanMappings(mappings);
      const data = await api.previewWorkflowImport(buildPortabilityEnvelope(source, text, "", picks));
      const safe: WorkflowImportPreview = {
        ...data,
        placeholders: Array.isArray(data?.placeholders) ? data.placeholders : [],
        validationIssues: Array.isArray(data?.validationIssues) ? data.validationIssues : [],
        report: normalizeReport(data?.report),
      };
      setPreview({ source, text, mappings: picks, data: safe });
      // Keep a name the person already typed; otherwise start from the one the file carries.
      setName((current) => current || String(data?.workflow?.name || nameFromFileName(fileName || "")).slice(0, MAX_WORKFLOW_NAME_LENGTH));
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
      const res = await api.importWorkflowFile(buildPortabilityEnvelope(preview.source, preview.text, name, preview.mappings));
      if (res?.workflowId) {
        // Stay "busy" while the route changes so a second click cannot create a duplicate.
        router.push(`/workflows/${encodeURIComponent(res.workflowId)}`);
        return;
      }
      setError({ kind: "other", message: t("errGeneric") });
    } catch (err) {
      failWith(err);
    }
    setBusy(null);
  };

  const pick = (key: string, value: string) => setMappings((current) => ({ ...current, [key]: value }));

  const header = (
    <PageHeader
      title={t("title")}
      subtitle={t("subtitle")}
      actions={
        <Button asChild variant="outline" size="sm">
          <Link href="/workflows">
            <ArrowLeft size={14} /> {t("backToWorkflows")}
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
          icon={Workflow}
          title={t("unavailableTitle")}
          description={t("unavailableBody")}
          action={
            <Button asChild size="sm">
              <Link href="/workflows">{t("createManually")}</Link>
            </Button>
          }
        />
      </AppShell>
    );
  }

  const data = preview?.data;
  const isCurrent = !!preview && preview.text === text && preview.source === source && sameMappings(preview.mappings, mappings);
  const detectedLabel = detected ? t(SOURCE_LABEL_KEY[detected]) : "";
  const previewSourceLabel = data ? t(SOURCE_LABEL_KEY[data.detectedSource] || "sourceAuto") : "";
  const groups = data ? groupPlaceholders(data.placeholders) : [];
  const pending = data ? unmappedCount(data.placeholders, mappings) : 0;
  const choicesFor = (kind: string): Choice[] => {
    const which = pickerSourceFor(kind);
    return which ? choices[which] : [];
  };

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
                <Workflow className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>{t("noteBestEffort")}</span>
              </li>
            </ul>
          </CardContent>
        </Card>

        <Card className="gap-4 py-5">
          <CardContent className="space-y-4 px-4 sm:px-6">
            <div className="space-y-2">
              <Label htmlFor="workflow-import-source">{t("sourceLabel")}</Label>
              <Select
                id="workflow-import-source"
                aria-label={t("sourceLabel")}
                className="w-full sm:max-w-xs"
                value={source}
                onChange={onSourceChange}
                options={sourceOptions}
                disabled={busy !== null}
              />
              {detected && source === "auto" && (
                <p className="text-xs text-muted-foreground" data-testid="workflow-import-detected-hint">
                  {t("detectedHint", { source: detectedLabel })}
                </p>
              )}
            </div>

            <div
              className={`space-y-2 rounded-lg border border-dashed p-3 transition-colors ${
                dragging ? "border-primary bg-primary/5" : "border-border"
              }`}
              data-testid="workflow-import-dropzone"
              onDragOver={(e) => {
                e.preventDefault();
                if (!busy) setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
            >
              <Label htmlFor="workflow-import-payload">{t("pasteLabel")}</Label>
              <Textarea
                id="workflow-import-payload"
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
                  accept=".json,.yml,.yaml,application/json,text/yaml"
                  className="sr-only"
                  tabIndex={-1}
                  aria-label={t("uploadButton")}
                  data-testid="workflow-import-file-input"
                  onChange={onFile}
                />
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy !== null}
                  onClick={() => fileRef.current?.click()}
                >
                  <Upload size={14} /> {t("uploadButton")}
                </Button>
                <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <FileUp size={13} aria-hidden="true" /> {t("dropHint")}
                </span>
                {fileName && <span className="min-w-0 break-all text-xs text-muted-foreground">{t("fileLoaded", { name: fileName })}</span>}
              </div>
              <p className="text-xs text-muted-foreground">{t("sizeLimitHint")}</p>
            </div>

            <Button type="button" onClick={runPreview} disabled={busy !== null || !text.trim()}>
              {busy === "preview" ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
              {busy === "preview" ? t("previewing") : t("previewButton")}
            </Button>
          </CardContent>
        </Card>

        {error && error.kind === "upgrade" && (
          <Alert variant="warning" data-testid="workflow-import-upgrade">
            <AlertCircle />
            <AlertDescription>
              <p className="font-medium">{t("upgradeTitle")}</p>
              <p>{error.message || t("upgradeBody")}</p>
              <Button asChild size="sm" variant="outline" className="mt-2">
                <Link href="/admin/billing">
                  <Sparkles size={14} /> {t("upgradeCta")}
                </Link>
              </Button>
            </AlertDescription>
          </Alert>
        )}
        {error && error.kind !== "upgrade" && (
          <Alert variant="error" data-testid="workflow-import-error">
            <AlertCircle />
            <AlertDescription>
              <p className="break-words">{error.message}</p>
            </AlertDescription>
          </Alert>
        )}

        {preview && data && (
          <Card className="gap-4 py-5" data-testid="workflow-import-preview">
            <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 px-4 sm:px-6">
              <CardTitle className="text-base">{t("previewHeading")}</CardTitle>
              <Badge variant="secondary">{t("detectedAs", { source: previewSourceLabel })}</Badge>
            </CardHeader>
            <CardContent className="space-y-5 px-4 sm:px-6">
              {!isCurrent && (
                <Alert variant="info" data-testid="workflow-import-stale">
                  <Info />
                  <AlertDescription className="flex flex-wrap items-center gap-3">
                    <span>{t("previewStale")}</span>
                    <Button type="button" size="sm" variant="outline" onClick={runPreview} disabled={busy !== null}>
                      {t("updatePreview")}
                    </Button>
                  </AlertDescription>
                </Alert>
              )}

              <div className="space-y-2">
                <Label htmlFor="workflow-import-name">{t("nameLabel")}</Label>
                <Input
                  id="workflow-import-name"
                  value={name}
                  maxLength={MAX_WORKFLOW_NAME_LENGTH}
                  onChange={(e) => setName(e.target.value)}
                  disabled={busy !== null}
                />
              </div>

              <div className="space-y-2" data-testid="workflow-import-fidelity">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="text-sm font-medium text-foreground">{t("fidelityTitle")}</p>
                  <Badge variant={fidelityBadgeVariant(data.fidelity.level)}>{t(FIDELITY_LABEL_KEY[data.fidelity.level])}</Badge>
                </div>
                <p className="text-sm text-muted-foreground">
                  {t("fidelitySummary", { percent: fidelityPercent(data.fidelity), count: data.fidelity.sourceNodes - data.fidelity.skipped })}
                </p>
                <p className="text-xs text-muted-foreground">
                  {t("fidelityStats", {
                    exact: data.fidelity.exact,
                    review: data.fidelity.needsReview,
                    placeholders: data.fidelity.placeholders,
                    skipped: data.fidelity.skipped,
                  })}{" "}
                  {t("connectionsSummary", { kept: data.fidelity.connectionsKept, total: data.fidelity.sourceConnections })}
                </p>
              </div>

              <div className="space-y-2" data-testid="workflow-import-validation">
                <p className="text-sm font-medium text-foreground">{t("issuesTitle")}</p>
                {data.validationIssues.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t("issuesNone")}</p>
                ) : (
                  <>
                    <p className="text-xs text-muted-foreground">{t("issuesIntro")}</p>
                    <ul className="list-disc space-y-1 pl-5 text-sm text-foreground">
                      {data.validationIssues.map((issue, i) => (
                        <li key={i} className="break-words">
                          {issue}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </div>

              {groups.length > 0 && (
                <div className="space-y-3" data-testid="workflow-import-placeholders">
                  <div>
                    <p className="text-sm font-medium text-foreground">{t("placeholdersTitle")}</p>
                    <p className="text-xs text-muted-foreground">{t("placeholdersIntro")}</p>
                    {pending > 0 && <p className="text-xs text-muted-foreground">{t("pendingMappings", { count: pending })}</p>}
                  </div>
                  {groups.map((group) => (
                    <section key={group.kind} aria-label={t(KIND_LABEL_KEY[group.kind] || "kindUnknown")} className="rounded-lg border border-border bg-card/40">
                      <h3 className="px-4 py-2 text-sm font-semibold text-foreground">{t(KIND_LABEL_KEY[group.kind] || "kindUnknown")}</h3>
                      {KIND_HINT_KEY[group.kind] && (
                        <p className="px-4 pb-2 text-xs text-muted-foreground">{t(KIND_HINT_KEY[group.kind])}</p>
                      )}
                      <ul className="divide-y divide-border border-t border-border">
                        {group.items.map((item: PortabilityPlaceholder) => (
                          <li key={item.key} className="space-y-2 px-4 py-3 text-sm">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <span className="min-w-0 break-words font-medium text-foreground">{item.label || item.key}</span>
                              <span className="text-xs text-muted-foreground">{t("usedIn", { count: item.usedBy.length })}</span>
                            </div>
                            {item.mappable && (
                              <Select
                                aria-label={t("mapAria", { label: item.label || item.key })}
                                className="w-full sm:max-w-sm"
                                value={mappings[item.key] || ""}
                                onChange={(e: any) => pick(item.key, String(e?.target?.value ?? e ?? ""))}
                                options={[{ value: "", label: t("mapNone") }, ...choicesFor(item.kind)]}
                                placeholder={t("mapNone")}
                                searchable
                                disabled={busy !== null}
                              />
                            )}
                          </li>
                        ))}
                      </ul>
                    </section>
                  ))}
                </div>
              )}

              <WorkflowImportReportView report={data.report} />

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

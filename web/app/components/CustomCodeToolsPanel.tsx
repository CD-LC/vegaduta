"use client";

import { useCallback, useEffect, useState } from "react";
import {
  AlertTriangle,
  Code2,
  PlugZap,
  RefreshCw,
  ShieldAlert,
  Wand2,
  X,
} from "lucide-react";
import { api, type VaultSecret } from "../lib/api";
import { toast } from "../lib/toastBus";
import { useTranslations } from "../lib/i18n";
import ToolCard from "./customCodeTools/ToolCard";
import ToolTestPanel from "./customCodeTools/ToolTestPanel";
import ToolFormFields from "./customCodeTools/ToolFormFields";
import { useToolGeneration } from "./customCodeTools/useToolGeneration";
import {
  DEFAULT_MEMORY_MB,
  DEFAULT_TIMEOUT_MS,
  EMPTY_FORM,
  FEATURE_DISABLED_MARKER,
  TEMPLATES,
  TOOL_NAME_PATTERN,
  type CustomCodeTool,
  type CustomCodeToolLanguage,
  type FormState,
  type TestResult,
} from "./customCodeTools/model";
import { useAuth } from "./AuthProvider";
import { cn } from "../lib/utils";
import { Section, EmptyState } from "./ax/page-kit";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { Alert, AlertDescription, AlertTitle } from "./ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "./ui/alert-dialog";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";

// Custom Code Tools - a tenant-admin writes a whole Java or Python program
// that becomes an agent tool, compiled/validated on save and executed
// server-side in an isolated sandbox. Built as the deliberate sibling of
// UserToolScriptsPanel (same Section + Card grid + inline form + "TRY IT"
// rhythm) so the two surfaces read as two rungs of one ladder rather than
// two unrelated features: SpEL expression -> whole program.
//
// Three things here are NOT in the SpEL sibling and are worth knowing about
// before editing:
//   1. Saving can SUCCEED and still leave you with a tool that won't run.
//      The backend persists a rejected program (so you don't lose your work
//      to a typo) and reports it back as status: "REJECTED" with the
//      compiler output in validationError. That is a 2xx, not an error - so
//      it can't be surfaced through the usual catch/toast path, and gets a
//      dedicated red panel above the editor instead.
//   2. Test runs hit /{id}/test, i.e. they execute the version STORED ON
//      THE SERVER, not whatever is currently in the textarea. So there is
//      no test-before-save; the panel says so rather than silently testing
//      stale code.
//   3. Two whole-page states (admin-only, and sandbox-not-enabled-on-this-
//      deployment) are normal situations rather than errors - see the
//      listCustomCodeTools comment in lib/api.ts for why they come back
//      quiet, with no global toast.

export type {
  CodeToolGenerationResponse,
  CustomCodeTool,
  CustomCodeToolLanguage,
  CustomCodeToolStatus,
  GenerationValidationError,
} from "./customCodeTools/model";

export default function CustomCodeToolsPanel({ onLoaded }: { onLoaded?: () => void }) {
  const t = useTranslations("customCodeTools");
  const [vaultSecrets, setVaultSecrets] = useState<VaultSecret[]>([]);
  // Granting a tool a WORKSPACE credential is admin-only server-side
  // (CustomCodeToolController#updateSecrets). readOnly above is a different
  // thing - it means the tool LIST itself 403d - so it cannot stand in for
  // this, or a plain member would get enabled toggles that 403 on click.
  const { hasPermission } = useAuth();
  const canManageTools = hasPermission("MANAGE_TOOLS");

  const [tools, setTools] = useState<CustomCodeTool[]>([]);
  const [loading, setLoading] = useState(true);
  // Whole-page states, not errors - see this file's header comment.
  const [featureDisabled, setFeatureDisabled] = useState(false);
  const [readOnly, setReadOnly] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CustomCodeTool | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [busy, setBusy] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  // Populated from a SUCCESSFUL save that came back REJECTED (see header).
  const [validationError, setValidationError] = useState<string | null>(null);

  const [sampleInput, setSampleInput] = useState('{"name": "world"}');
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [testing, setTesting] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<CustomCodeTool | null>(null);
  const [deleting, setDeleting] = useState(false);

  const gen = useToolGeneration({
    language: form.language,
    toolName: form.toolName,
    onSourceCode: (sourceCode) => setForm((prev) => ({ ...prev, sourceCode })),
  });

  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(tools);

  const load = useCallback(async () => {
    try {
      const data = await api.listCustomCodeTools();
      // Best-effort: a member without vault access still gets the tool list,
      // they just see nothing grantable. A vault failure must not blank this
      // panel, which is why this is not awaited into the same try.
      api
        .listSecrets()
        .then(setVaultSecrets)
        .catch(() => setVaultSecrets([]));
      setTools(Array.isArray(data) ? data : []);
      setFeatureDisabled(false);
      setReadOnly(false);
      setLoadError(null);
    } catch (e: any) {
      if (e?.status === 400 && String(e?.message || "").includes(FEATURE_DISABLED_MARKER)) {
        setFeatureDisabled(true);
      } else if (e?.status === 403) {
        setReadOnly(true);
      } else {
        setLoadError(e?.message || t("loadError"));
      }
      setTools([]);
    } finally {
      setLoading(false);
      onLoaded?.();
    }
    // `t` is memoised by useTranslations and onLoaded is expected to be
    // stable; both are deliberately left out to keep this a mount-once load
    // rather than a re-fetch on every parent render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function resetFormPanels() {
    setFormError(null);
    setValidationError(null);
    setTestResult(null);
    gen.reset();
  }

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    resetFormPanels();
    setShowForm(true);
  }

  function openEdit(tool: CustomCodeTool) {
    setEditing(tool);
    setForm({
      toolName: tool.toolName,
      displayName: tool.displayName,
      description: tool.description || "",
      language: tool.language,
      sourceCode: tool.sourceCode || "",
      inputSchemaJson: tool.inputSchemaJson || "",
      outputDescription: tool.outputDescription || "",
      timeoutMs: tool.timeoutMs ?? DEFAULT_TIMEOUT_MS,
      memoryMb: tool.memoryMb ?? DEFAULT_MEMORY_MB,
    });
    resetFormPanels();
    // A tool that's already been rejected should say so the moment you open
    // it, not only after the next save.
    setValidationError(tool.status === "REJECTED" ? tool.validationError : null);
    setShowForm(true);
  }

  function closeForm() {
    setShowForm(false);
    setEditing(null);
    resetFormPanels();
  }

  function set<K extends keyof FormState>(field: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  // Swap in the starter template when the editor is empty, and also when it
  // still holds the OTHER language's untouched template - switching Java ->
  // Python having written nothing yet should not leave Java boilerplate
  // sitting in a Python file.
  function chooseLanguage(language: CustomCodeToolLanguage) {
    setForm((prev) => {
      const current = prev.sourceCode.trim();
      const isPristine = current === "" || Object.values(TEMPLATES).some((tpl) => tpl.trim() === current);
      return { ...prev, language, sourceCode: isPristine ? TEMPLATES[language] : prev.sourceCode };
    });
  }

  function validateForm(): string | null {
    if (!form.toolName.trim() || !form.displayName.trim() || !form.description.trim() || !form.sourceCode.trim()) {
      return t("requiredFieldsError");
    }
    if (!TOOL_NAME_PATTERN.test(form.toolName.trim())) {
      return t("toolNameInvalid");
    }
    if (form.inputSchemaJson.trim()) {
      try {
        JSON.parse(form.inputSchemaJson);
      } catch {
        return t("invalidSchemaError");
      }
    }
    return null;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setValidationError(null);
    const problem = validateForm();
    if (problem) {
      setFormError(problem);
      return;
    }
    // toolName is immutable on update, but the contract still wants it in
    // the body - so it's sent either way and the input is disabled instead.
    const payload = {
      toolName: form.toolName.trim(),
      displayName: form.displayName.trim(),
      description: form.description.trim(),
      language: form.language,
      sourceCode: form.sourceCode,
      inputSchemaJson: form.inputSchemaJson.trim() ? form.inputSchemaJson.trim() : null,
      outputDescription: form.outputDescription.trim() ? form.outputDescription.trim() : null,
      timeoutMs: Number(form.timeoutMs),
      memoryMb: Number(form.memoryMb),
    };
    setBusy(true);
    try {
      const saved: CustomCodeTool = editing
        ? await api.updateCustomCodeTool(editing.id, payload)
        : await api.createCustomCodeTool(payload);
      await load();
      // A REJECTED save is still a save. Stay on the form with the compiler
      // output in front of the person instead of closing and quietly
      // dropping them back on a list where one card says "Rejected".
      if (saved?.status === "REJECTED") {
        setEditing(saved);
        setValidationError(saved.validationError || t("rejectedNoDetail"));
        return;
      }
      toast.success(
        editing
          ? t("toolUpdatedSuccess", { name: payload.displayName })
          : t("toolCreatedSuccess", { name: payload.displayName })
      );
      closeForm();
    } catch (err: any) {
      if (err?.status === 403) setReadOnly(true);
      setFormError(err?.message || t("saveError"));
    } finally {
      setBusy(false);
    }
  }

  async function handleToggle(tool: CustomCodeTool) {
    setBusyId(tool.id);
    try {
      await api.setCustomCodeToolEnabled(tool.id, !tool.enabled);
      await load();
    } catch (e: any) {
      if (e?.status === 403) setReadOnly(true);
      toast.error(t("toggleError", { name: tool.displayName, error: e?.message || e }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleRevalidate(tool: CustomCodeTool) {
    setBusyId(tool.id);
    try {
      const updated: CustomCodeTool = await api.revalidateCustomCodeTool(tool.id);
      await load();
      if (updated?.status === "REJECTED") {
        setValidationError(updated.validationError || t("rejectedNoDetail"));
      } else {
        setValidationError(null);
        toast.success(t("revalidatedSuccess", { name: tool.displayName }));
      }
      if (editing?.id === tool.id) setEditing(updated);
    } catch (e: any) {
      if (e?.status === 403) setReadOnly(true);
      toast.error(t("revalidateError", { name: tool.displayName, error: e?.message || e }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.deleteCustomCodeTool(deleteTarget.id);
      toast.success(t("toolDeletedSuccess", { name: deleteTarget.displayName }));
      if (editing?.id === deleteTarget.id) closeForm();
      setDeleteTarget(null);
      await load();
    } catch (e: any) {
      if (e?.status === 403) setReadOnly(true);
      toast.error(t("deleteError", { name: deleteTarget.displayName, error: e?.message || e }));
    } finally {
      setDeleting(false);
    }
  }

  async function handleTest() {
    if (!editing) return;
    setTesting(true);
    setTestResult(null);
    try {
      const parsed = sampleInput.trim() ? JSON.parse(sampleInput) : {};
      const res: TestResult = await api.testCustomCodeTool(editing.id, parsed);
      setTestResult(res);
    } catch (e: any) {
      // Covers both a malformed sample-input JSON (thrown by JSON.parse
      // before any request goes out) and a transport-level failure. Shaped
      // like a real failed run so there's one rendering path below.
      setTestResult({
        ok: false,
        result: null,
        stdout: "",
        error: e?.message || t("testFailedFallback"),
        errorType: e instanceof SyntaxError ? t("invalidSampleInput") : "",
        durationMs: 0,
      });
    } finally {
      setTesting(false);
    }
  }

  // ---- whole-page states ------------------------------------------------

  if (featureDisabled) {
    return (
      <EmptyState
        icon={PlugZap}
        title={t("disabledTitle")}
        description={
          <>
            <span className="block">{t("disabledBody")}</span>
            <code className="mt-3 inline-block rounded bg-secondary px-2 py-1 font-mono text-xs text-foreground">
              TOOL_SANDBOX_ENABLED=true
            </code>
          </>
        }
      />
    );
  }

  return (
    <Section title={t("sectionLabel", { count: tools.length })} className="space-y-4">
      {readOnly && (
        <Alert variant="info">
          <ShieldAlert aria-hidden="true" />
          <AlertTitle>{t("readOnlyTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("readOnlyBody")}</p>
          </AlertDescription>
        </Alert>
      )}

      {loadError && (
        <Alert variant="error">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("loadError")}</AlertTitle>
          <AlertDescription>
            <Button variant="outline" size="sm" className="mt-2" onClick={load}>
              {t("retryButton")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {!loading && (tools.length > 0 || !readOnly) && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {pageItems.map((tool: CustomCodeTool) => (
            <ToolCard
              key={tool.id}
              tool={tool}
              readOnly={readOnly}
              busy={busyId === tool.id}
              canManageTools={canManageTools}
              vaultSecrets={vaultSecrets}
              onToggle={handleToggle}
              onEdit={openEdit}
              onRevalidate={handleRevalidate}
              onDelete={setDeleteTarget}
              onGrantsChanged={(toolId, names) =>
                setTools((prev: CustomCodeTool[]) =>
                  prev.map((x) => (x.id === toolId ? { ...x, allowedSecretNames: names } : x))
                )
              }
            />
          ))}

          {!readOnly && (
            <button
              type="button"
              onClick={openCreate}
              className="flex min-h-[168px] flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border bg-card/40 p-5 text-center transition-colors hover:border-primary/40 hover:bg-accent"
            >
              <span className="flex size-10 items-center justify-center rounded-full bg-primary/10 text-primary">
                <Wand2 aria-hidden="true" size={18} />
              </span>
              <span className="text-sm font-medium text-foreground">{t("newToolLabel")}</span>
              <span className="text-xs text-muted-foreground">{t("newToolSub")}</span>
            </button>
          )}
        </div>
      )}

      {!loading && tools.length > 0 && (
        <PaginationControls
          page={page}
          totalPages={totalPages}
          totalItems={totalItems}
          pageSize={pageSize}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
          itemLabel={t("recordsLabel")}
        />
      )}

      {!loading && tools.length === 0 && readOnly && (
        <EmptyState icon={Code2} title={t("emptyTitle")} description={t("emptyReadOnlyBody")} />
      )}

      {/* ---- create / edit form ---- */}
      {showForm && !readOnly && (
        <Card className="gap-4 p-6">
          <div className="flex items-start justify-between gap-3">
            <div className="text-sm font-semibold text-foreground">
              {editing ? t("editTitle", { name: editing.displayName }) : t("newToolTitle")}
            </div>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-8"
              onClick={closeForm}
              aria-label={t("closeAriaLabel")}
              title={t("closeAriaLabel")}
            >
              <X aria-hidden="true" size={15} />
            </Button>
          </div>

          {formError && (
            <div
              role="alert"
              className="rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]"
            >
              {formError}
            </div>
          )}

          {/* A rejected save DID persist - this panel exists to make sure
              nobody reads "saved" as "working". */}
          {validationError && (
            <div className="space-y-2 rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] p-4">
              <div className="flex items-center gap-2 text-sm font-semibold text-[var(--danger-text)]">
                <AlertTriangle aria-hidden="true" size={15} /> {t("rejectedTitle")}
              </div>
              <p className="text-sm text-[var(--danger-text)]">{t("rejectedBody")}</p>
              <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-md bg-background/70 p-3 font-mono text-xs text-[var(--danger-text)]">
                {validationError}
              </pre>
              {editing && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busyId === editing.id}
                  onClick={() => handleRevalidate(editing)}
                >
                  <RefreshCw aria-hidden="true" size={13} className={cn(busyId === editing.id && "animate-spin")} />
                  {busyId === editing.id ? t("revalidatingEllipsis") : t("rejectedRevalidateButton")}
                </Button>
              )}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <ToolFormFields
              form={form}
              editing={Boolean(editing)}
              set={set}
              chooseLanguage={chooseLanguage}
              gen={gen}
            />

            <ToolTestPanel
              saved={Boolean(editing)}
              sampleInput={sampleInput}
              onSampleInputChange={setSampleInput}
              testing={testing}
              testResult={testResult}
              onRun={handleTest}
            />

            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={busy}>
                {busy ? t("savingEllipsis") : editing ? t("saveChangesButton") : t("createToolButton")}
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={closeForm} disabled={busy}>
                {t("cancelButton")}
              </Button>
            </div>
          </form>
        </Card>
      )}

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open: boolean) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteDialogTitle", { name: deleteTarget?.displayName || "" })}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("deleteDialogBody", { toolName: deleteTarget?.toolName || "" })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t("deleteDialogCancel")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deleting}
              onClick={(e: any) => {
                e.preventDefault();
                void handleDelete();
              }}
            >
              {deleting ? t("deletingEllipsis") : t("deleteDialogConfirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  );
}

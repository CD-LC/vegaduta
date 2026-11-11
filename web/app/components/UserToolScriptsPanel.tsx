"use client";

import { useCallback, useEffect, useId, useState } from "react";
import { Wand2, Play, Pencil, Trash2, X, Terminal } from "lucide-react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import { Section, EmptyState } from "./ax/page-kit";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Textarea } from "./ui/textarea";
import { Card } from "./ui/card";
import { Label } from "./ui/label";
import { Switch } from "./ui/switch";
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
import { errorMessage } from "../lib/errors";

// #424 - "user-created tools (generated scripts)", deliberately scoped down
// to sandboxed Spring Expression Language (SpEL) expressions rather than
// arbitrary-language script execution - see UserToolScript's javadoc
// (core/agents) for the full reasoning: no sandboxed script-execution
// engine exists anywhere in this codebase, and SimpleEvaluationContext is
// sandboxed BY CONSTRUCTION (no method/constructor/bean/type resolution,
// and SpEL's grammar has no loop construct at all - it cannot run forever).
// Rendered directly below McpPanel on the same Tools & MCP screen. Built on
// the shared page-kit Section + shadcn Card/Textarea/Button primitives (the
// premium console standard) rather than the legacy .ax-tl-* chrome.
const EMPTY_FORM = { toolName: "", displayName: "", description: "", expression: "", inputSchemaJson: "" };

interface UserToolScript {
  id: string;
  toolName: string;
  displayName: string;
  description?: string | null;
  expression: string;
  inputSchemaJson?: string | null;
  enabled: boolean;
}

export default function UserToolScriptsPanel() {
  const tHC = useTranslations("hcComponentsUserToolScriptsPanel");
  const t = useTranslations("userToolScriptsPanel");
  const [scripts, setScripts] = useState<UserToolScript[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<UserToolScript | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [busy, setBusy] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [sampleInput, setSampleInput] = useState('{"example": 42}');
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<UserToolScript | null>(null);
  const [deleting, setDeleting] = useState(false);
  const toolNameId = useId();
  const displayNameId = useId();
  const descriptionId = useId();
  const expressionId = useId();
  const inputSchemaId = useId();
  // Client-side paging (shared hook, default 10/page) so a workspace with
  // many saved tool scripts stays legible instead of an unbounded card wall.
  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(scripts);

  const load = useCallback(async () => {
    try {
      const data = await api.listUserToolScripts();
      setScripts(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(errorMessage(e, t("loadScriptsError")));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setTestResult(null);
    setError(null);
    setShowForm(true);
  }

  function openEdit(script: UserToolScript) {
    setEditing(script);
    setForm({
      toolName: script.toolName,
      displayName: script.displayName,
      description: script.description || "",
      expression: script.expression,
      inputSchemaJson: script.inputSchemaJson || "",
    });
    setTestResult(null);
    setError(null);
    setShowForm(true);
  }

  function set(field: keyof typeof EMPTY_FORM, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  async function handleTest() {
    setTesting(true);
    setTestResult(null);
    try {
      const res = await api.testUserToolScript(form.expression, sampleInput);
      setTestResult(res.resultJson);
    } catch (e) {
      setTestResult(`{"error": ${JSON.stringify(errorMessage(e, t("testFailedFallback")))}}`);
    } finally {
      setTesting(false);
    }
  }

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!form.displayName.trim() || !form.expression.trim() || (!editing && !form.toolName.trim())) {
      setError(t("nameRequiredError"));
      return;
    }
    setBusy(true);
    try {
      if (editing) {
        await api.updateUserToolScript(editing.id, form);
        setSuccess(t("scriptUpdatedSuccess", { name: form.displayName }));
      } else {
        await api.createUserToolScript(form);
        setSuccess(t("scriptCreatedSuccess", { name: form.displayName }));
      }
      setShowForm(false);
      setEditing(null);
      void load();
    } catch (e) {
      setError(errorMessage(e, t("saveScriptError")));
    } finally {
      setBusy(false);
    }
  }

  async function handleToggle(script: UserToolScript) {
    setBusyId(script.id);
    try {
      await api.setUserToolScriptEnabled(script.id, !script.enabled);
      void load();
    } catch (e) {
      toast.error(t("toggleScriptError", { name: script.displayName, error: errorMessage(e) }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(script: UserToolScript) {
    setBusyId(script.id);
    setDeleting(true);
    try {
      await api.deleteUserToolScript(script.id);
      toast.success(t("scriptDeletedSuccess", { name: script.displayName }));
      setDeleteTarget(null);
      void load();
    } catch (e) {
      toast.error(t("deleteScriptError", { name: script.displayName, error: errorMessage(e) }));
    } finally {
      setBusyId(null);
      setDeleting(false);
    }
  }

  return (
    <Section title={t("sectionLabel", { count: scripts.length })} className="space-y-4">
      {!loading && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {pageItems.map((script: UserToolScript) => (
            <Card key={script.id} className={cn("gap-0 p-5 transition-colors hover:border-primary/40")}>
              <div className="flex items-start gap-3">
                <span
                  className={cn(
                    "flex size-9 shrink-0 items-center justify-center rounded-lg",
                    script.enabled ? "bg-primary/10 text-primary" : "bg-secondary text-muted-foreground opacity-60"
                  )}
                >
                  <Terminal aria-hidden="true" size={16} />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-foreground">{script.displayName}</div>
                  <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                    <span className="truncate font-mono">{script.toolName}</span>
                    <span>·</span>
                    <span>{script.enabled ? t("statusEnabled") : t("statusDisabled")}</span>
                  </div>
                </div>
                <Switch
                  checked={script.enabled}
                  onCheckedChange={() => void handleToggle(script)}
                  disabled={busyId === script.id}
                  aria-label={t("toggleScriptAriaLabel", { name: script.displayName })}
                />
              </div>
              <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">
                {script.description || t("noDescription")}
              </p>
              <div className="mt-4 flex gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="size-8"
                  disabled={busyId === script.id}
                  onClick={() => openEdit(script)}
                  aria-label={t("editScriptAriaLabel", { name: script.displayName })}
                >
                  <Pencil aria-hidden="true" size={14} />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="size-8 text-muted-foreground hover:text-destructive"
                  disabled={busyId === script.id}
                  onClick={() => setDeleteTarget(script)}
                  aria-label={t("deleteScriptAriaLabel", { name: script.displayName })}
                >
                  <Trash2 aria-hidden="true" size={14} />
                </Button>
              </div>
            </Card>
          ))}
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
        </div>
      )}

      {!loading && scripts.length > 0 && (
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

      {!loading && scripts.length === 0 && (
        <EmptyState icon={Terminal} title={t("newToolLabel")} description={t("emptyStateText")} />
      )}

      {showForm && (
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
              onClick={() => {
                setShowForm(false);
                setEditing(null);
              }}
              aria-label={t("closeAriaLabel")}
            >
              <X aria-hidden="true" size={15} />
            </Button>
          </div>

          {error && (
            <div
              role="alert"
              className="rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]"
            >
              {error}
            </div>
          )}

          <p className="text-sm text-muted-foreground">
            {t("sandboxNotePrefix")}
            <code className="rounded bg-secondary px-1 py-0.5 font-mono text-xs">[&apos;field&apos;]</code>
            {t("sandboxNoteSuffix")}
          </p>

          <form onSubmit={handleSubmit} className="space-y-3">
            <div className="flex flex-wrap gap-3">
              <div className="flex-1 basis-60 space-y-1">
                <Label htmlFor={toolNameId}>{t("toolNameLabel")}</Label>
                <Input
                  id={toolNameId}
                  type="text"
                  placeholder={t("toolNamePlaceholder")}
                  value={form.toolName}
                  onChange={(e) => set("toolName", e.target.value)}
                  disabled={Boolean(editing)}
                  className="w-full"
                  aria-describedby="uts-tool-name-help"
                />
              </div>
              <div className="flex-1 basis-60 space-y-1">
                <Label htmlFor={displayNameId}>{t("displayNameLabel")}</Label>
                <Input
                  id={displayNameId}
                  type="text"
                  placeholder={t("displayNamePlaceholder")}
                  value={form.displayName}
                  onChange={(e) => set("displayName", e.target.value)}
                  className="w-full"
                />
              </div>
            </div>
            <p id="uts-tool-name-help" className="text-xs text-muted-foreground">
              {tHC("s1")}
            </p>
            <div className="space-y-1">
              <Label htmlFor={descriptionId}>{t("descriptionLabel")}</Label>
              <Input
                id={descriptionId}
                type="text"
                placeholder={t("descriptionPlaceholder")}
                value={form.description}
                onChange={(e) => set("description", e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor={expressionId}>{t("expressionLabel")}</Label>
              <Textarea
                id={expressionId}
                placeholder={t("expressionPlaceholder")}
                value={form.expression}
                onChange={(e) => set("expression", e.target.value)}
                rows={3}
                className="font-mono"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor={inputSchemaId}>{t("inputSchemaLabel")}</Label>
              <Textarea
                id={inputSchemaId}
                placeholder={t("inputSchemaPlaceholder")}
                value={form.inputSchemaJson}
                onChange={(e) => set("inputSchemaJson", e.target.value)}
                rows={2}
                className="font-mono"
              />
            </div>

            <div className="rounded-lg border border-border bg-secondary/30 p-4">
              <div className="mb-2 font-mono text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("tryItLabel")}
              </div>
              <Textarea
                placeholder={t("sampleInputPlaceholder")}
                value={sampleInput}
                onChange={(e) => setSampleInput(e.target.value)}
                rows={2}
                className="bg-background font-mono"
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-2"
                onClick={handleTest}
                disabled={testing || !form.expression.trim()}
              >
                <Play aria-hidden="true" size={13} /> {testing ? t("runningEllipsis") : t("runTestButton")}
              </Button>
              {testResult && (
                <pre className="mt-2 whitespace-pre-wrap break-words rounded-md bg-background p-3 font-mono text-xs text-muted-foreground">
                  {testResult}
                </pre>
              )}
            </div>

            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={busy}>
                {busy ? t("savingEllipsis") : editing ? t("saveChangesButton") : t("createToolButton")}
              </Button>
            </div>
          </form>
        </Card>
      )}

      {success && (
        <div
          role="status"
          className="rounded-lg border border-[var(--success-border)] bg-[var(--success-bg)] px-3 py-2 text-sm text-[var(--success-text)]"
        >
          {success}
        </div>
      )}

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteDialogTitle", { name: deleteTarget?.displayName || "" })}</AlertDialogTitle>
            <AlertDialogDescription>{t("deleteDialogBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t("deleteDialogCancel")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deleting}
              onClick={(e) => {
                e.preventDefault();
                if (deleteTarget) void handleDelete(deleteTarget);
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

"use client";

import { forwardRef, useCallback, useEffect, useImperativeHandle, useState } from "react";
import { FileJson2 } from "lucide-react";
import OpenApiSourceList from "./openapi/OpenApiSourceList";
import OpenApiEditPanel from "./openapi/OpenApiEditPanel";
import OpenApiImportWizard from "./openapi/OpenApiImportWizard";
import {
  EMPTY_SOURCE_FORM,
  EMPTY_SETTINGS,
  type OpenApiToolSource,
  type OpenApiTool,
  type Preview,
  type AuthType,
} from "./openapi/shared";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { useTranslations } from "../lib/i18n";
import { useAuth } from "./AuthProvider";
import { usePagination } from "../lib/usePagination";
import { Section, EmptyState } from "./ax/page-kit";
import { Button } from "./ui/button";
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

// Import from OpenAPI - the no-code sibling of UserToolScriptsPanel (SpEL)
// and CustomCodeToolsPanel (sandboxed programs): a tenant points the
// platform at an OpenAPI 3 / Swagger 2 document (URL or pasted text) and
// every operation in it becomes an agent-callable tool, exposed through the
// exact same in-process ToolCallback path the other two use
// (core/agents com.agenticai.core.tools.openapi). Reads are open to every
// signed-in member; preview/import/refresh/settings/delete are tenant-admin
// on the backend, so the write affordances are hidden for everyone else
// rather than rendered as buttons that 403 on click.
//
// Flow: [source] name + URL-or-paste -> Preview -> [review] operations with
// checkboxes, base URL (pre-filled from the document's servers[0]), auth
// (none / API-key header / bearer) with the secret entered ONCE and never
// echoed back (`hasSecret` + `secretPreview` is all the list is told).

const OpenApiToolSourcesPanel = forwardRef(function OpenApiToolSourcesPanel(_props: object, ref) {
  const t = useTranslations("openApiTools");
  const { hasPermission } = useAuth();
  const canManageTools = hasPermission("MANAGE_TOOLS");

  const [sources, setSources] = useState<OpenApiToolSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  // Import wizard.
  const [showImport, setShowImport] = useState(false);
  const [step, setStep] = useState<"source" | "review">("source");
  const [sourceForm, setSourceForm] = useState(EMPTY_SOURCE_FORM);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());
  const [settings, setSettings] = useState(EMPTY_SETTINGS);
  const [previewing, setPreviewing] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);

  // Settings editor for an existing source.
  const [editing, setEditing] = useState<OpenApiToolSource | null>(null);
  const [editForm, setEditForm] = useState({ name: "", ...EMPTY_SETTINGS });
  const [editBusy, setEditBusy] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // Expanded source -> its generated tools (fetched on first expand).
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [toolsBySource, setToolsBySource] = useState<Record<string, OpenApiTool[]>>({});
  const [toolsLoading, setToolsLoading] = useState<string | null>(null);
  const [busyToolId, setBusyToolId] = useState<string | null>(null);

  // "Try it" on one tool.
  const [testTool, setTestTool] = useState<OpenApiTool | null>(null);
  const [testInput, setTestInput] = useState("{}");
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<OpenApiToolSource | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await api.listOpenApiToolSources();
      setSources(Array.isArray(data) ? data : []);
      setLoadError(null);
    } catch (e: any) {
      setLoadError(e?.message || t("loadError"));
    } finally {
      setLoading(false);
    }
    // `t` is memoised by useTranslations; mount-once load, like the sibling panels.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function openImport() {
    if (!canManageTools) {
      toast.error(t("adminOnlyToast"));
      return;
    }
    setEditing(null);
    setSourceForm(EMPTY_SOURCE_FORM);
    setPreview(null);
    setSelectedKeys(new Set());
    setSettings(EMPTY_SETTINGS);
    setImportError(null);
    setStep("source");
    setShowImport(true);
  }

  useImperativeHandle(ref, () => ({ openImport }));

  function closeImport() {
    setShowImport(false);
    setPreview(null);
    setImportError(null);
  }

  function setSource(field: keyof typeof EMPTY_SOURCE_FORM, value: string) {
    setSourceForm((prev) => ({ ...prev, [field]: value }));
  }

  function setSetting(field: keyof typeof EMPTY_SETTINGS, value: string) {
    setSettings((prev) => ({ ...prev, [field]: value }));
  }

  // Explicit return type is load-bearing, not decoration: without it TS has no
  // contextual type for the bare `undefined` in each ternary branch and reports
  // "property 'specText' implicitly has an 'any' type", which fails `next build`
  // (its type check is stricter than `tsc --noEmit` against this tsconfig - the
  // error does NOT reproduce under the latter, which is how it reached main).
  function specPayload(): { specUrl: string | undefined; specText: string | undefined } {
    return sourceForm.mode === "url"
      ? { specUrl: sourceForm.specUrl.trim(), specText: undefined }
      : { specUrl: undefined, specText: sourceForm.specText };
  }

  async function handlePreview(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setImportError(null);
    if (!sourceForm.name.trim()) {
      setImportError(t("nameRequired"));
      return;
    }
    const spec = specPayload();
    if (sourceForm.mode === "url" ? !spec.specUrl : !spec.specText?.trim()) {
      setImportError(sourceForm.mode === "url" ? t("specUrlRequired") : t("specTextRequired"));
      return;
    }
    setPreviewing(true);
    try {
      const res: Preview = await api.previewOpenApiToolSource({
        name: sourceForm.name.trim(),
        toolPrefix: sourceForm.toolPrefix.trim() || undefined,
        ...spec,
      });
      setPreview(res);
      // Everything the document declares starts selected except what it
      // itself marks deprecated - the LLM shouldn't be handed those by default.
      setSelectedKeys(new Set(res.operations.filter((op) => !op.deprecated).map((op) => op.operationKey)));
      setSettings({ ...EMPTY_SETTINGS, baseUrl: res.suggestedBaseUrl || "" });
      setStep("review");
    } catch (err: any) {
      setImportError(err?.message || t("previewFailed"));
    } finally {
      setPreviewing(false);
    }
  }

  function toggleKey(key: string) {
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function selectAll(select: boolean) {
    if (!preview) return;
    setSelectedKeys(select ? new Set(preview.operations.map((op) => op.operationKey)) : new Set());
  }

  async function handleImport(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!preview) return;
    setImportError(null);
    if (!settings.baseUrl.trim()) {
      setImportError(t("baseUrlRequired"));
      return;
    }
    if (settings.authType !== "NONE" && !settings.secret.trim()) {
      setImportError(t("secretRequired"));
      return;
    }
    if (
      settings.authType === "OAUTH2_CLIENT_CREDENTIALS" &&
      (!settings.oauth2TokenUrl.trim() || !settings.oauth2ClientId.trim())
    ) {
      setImportError(t("oauth2Required"));
      return;
    }
    if (selectedKeys.size === 0) {
      setImportError(t("selectAtLeastOne"));
      return;
    }
    setImporting(true);
    try {
      const created: OpenApiToolSource = await api.createOpenApiToolSource({
        name: sourceForm.name.trim(),
        toolPrefix: preview.toolPrefix,
        ...specPayload(),
        baseUrl: settings.baseUrl.trim(),
        authType: settings.authType,
        authHeaderName: settings.authType === "API_KEY_HEADER" ? settings.authHeaderName.trim() : undefined,
        secret: settings.authType === "NONE" ? undefined : settings.secret,
        oauth2TokenUrl: settings.authType === "OAUTH2_CLIENT_CREDENTIALS" ? settings.oauth2TokenUrl.trim() : undefined,
        oauth2ClientId: settings.authType === "OAUTH2_CLIENT_CREDENTIALS" ? settings.oauth2ClientId.trim() : undefined,
        oauth2Scope: settings.authType === "OAUTH2_CLIENT_CREDENTIALS" ? settings.oauth2Scope.trim() : undefined,
        oauth2TokenStyle: settings.authType === "OAUTH2_CLIENT_CREDENTIALS" ? settings.oauth2TokenStyle : undefined,
        enabledOperationKeys: Array.from(selectedKeys),
      });
      toast.success(t("importedToast", { name: created.name, count: created.enabledToolCount }));
      closeImport();
      setExpandedId(created.id);
      setToolsBySource((prev) => {
        const next = { ...prev };
        delete next[created.id];
        return next;
      });
      void load();
    } catch (err: any) {
      setImportError(err?.message || t("importFailed"));
    } finally {
      setImporting(false);
    }
  }

  function openEdit(source: OpenApiToolSource) {
    setShowImport(false);
    setEditing(source);
    setEditForm({
      name: source.name,
      baseUrl: source.baseUrl,
      authType: source.authType || "NONE",
      authHeaderName: source.authHeaderName || "X-API-Key",
      secret: "",
      oauth2TokenUrl: source.oauth2TokenUrl || "",
      oauth2ClientId: source.oauth2ClientId || "",
      oauth2Scope: source.oauth2Scope || "",
      oauth2TokenStyle: source.oauth2TokenStyle || "FORM",
    });
    setEditError(null);
  }

  async function handleSaveSettings(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!editing) return;
    setEditError(null);
    if (!editForm.name.trim() || !editForm.baseUrl.trim()) {
      setEditError(t("nameAndBaseUrlRequired"));
      return;
    }
    const keepsStored = editing.hasSecret && editing.authType === editForm.authType;
    if (editForm.authType !== "NONE" && !editForm.secret.trim() && !keepsStored) {
      setEditError(t("secretRequired"));
      return;
    }
    if (
      editForm.authType === "OAUTH2_CLIENT_CREDENTIALS" &&
      (!editForm.oauth2TokenUrl.trim() || !editForm.oauth2ClientId.trim())
    ) {
      setEditError(t("oauth2Required"));
      return;
    }
    setEditBusy(true);
    try {
      await api.updateOpenApiToolSource(editing.id, {
        name: editForm.name.trim(),
        baseUrl: editForm.baseUrl.trim(),
        authType: editForm.authType,
        authHeaderName: editForm.authType === "API_KEY_HEADER" ? editForm.authHeaderName.trim() : undefined,
        secret: editForm.secret.trim() || undefined,
        oauth2TokenUrl: editForm.authType === "OAUTH2_CLIENT_CREDENTIALS" ? editForm.oauth2TokenUrl.trim() : undefined,
        oauth2ClientId: editForm.authType === "OAUTH2_CLIENT_CREDENTIALS" ? editForm.oauth2ClientId.trim() : undefined,
        oauth2Scope: editForm.authType === "OAUTH2_CLIENT_CREDENTIALS" ? editForm.oauth2Scope.trim() : undefined,
        oauth2TokenStyle: editForm.authType === "OAUTH2_CLIENT_CREDENTIALS" ? editForm.oauth2TokenStyle : undefined,
      });
      toast.success(t("settingsSavedToast", { name: editForm.name.trim() }));
      setEditing(null);
      void load();
    } catch (err: any) {
      setEditError(err?.message || t("saveFailed"));
    } finally {
      setEditBusy(false);
    }
  }

  async function handleRefresh(source: OpenApiToolSource) {
    setBusyId(source.id);
    try {
      const updated: OpenApiToolSource = await api.refreshOpenApiToolSource(source.id);
      if (updated.lastError) {
        toast.error(t("refreshFailedToast", { name: source.name, error: updated.lastError }));
      } else {
        toast.success(t("refreshedToast", { name: source.name, count: updated.toolCount }));
      }
      setToolsBySource((prev) => {
        const next = { ...prev };
        delete next[source.id];
        return next;
      });
      if (expandedId === source.id) void loadTools(source.id);
      void load();
    } catch (err: any) {
      toast.error(t("refreshFailedToast", { name: source.name, error: err?.message || err }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleToggleSource(source: OpenApiToolSource) {
    setBusyId(source.id);
    try {
      await api.setOpenApiToolSourceEnabled(source.id, !source.enabled);
      void load();
    } catch (err: any) {
      toast.error(t("toggleFailedToast", { name: source.name, error: err?.message || err }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await api.deleteOpenApiToolSource(deleteTarget.id);
      toast.success(t("deletedToast", { name: deleteTarget.name }));
      if (expandedId === deleteTarget.id) setExpandedId(null);
      setDeleteTarget(null);
      void load();
    } catch (err: any) {
      toast.error(t("deleteFailedToast", { name: deleteTarget.name, error: err?.message || err }));
    } finally {
      setDeleting(false);
    }
  }

  async function loadTools(sourceId: string) {
    setToolsLoading(sourceId);
    try {
      const data = await api.listOpenApiTools(sourceId);
      setToolsBySource((prev) => ({ ...prev, [sourceId]: Array.isArray(data) ? data : [] }));
    } catch (err: any) {
      toast.error(err?.message || t("loadToolsError"));
    } finally {
      setToolsLoading(null);
    }
  }

  function toggleExpanded(source: OpenApiToolSource) {
    if (expandedId === source.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(source.id);
    setTestTool(null);
    if (!toolsBySource[source.id]) void loadTools(source.id);
  }

  async function handleToggleTool(tool: OpenApiTool) {
    setBusyToolId(tool.id);
    try {
      const updated: OpenApiTool = await api.setOpenApiToolEnabled(tool.sourceId, tool.id, !tool.enabled);
      setToolsBySource((prev) => ({
        ...prev,
        [tool.sourceId]: (prev[tool.sourceId] || []).map((x) => (x.id === tool.id ? updated : x)),
      }));
      void load();
    } catch (err: any) {
      toast.error(t("toggleFailedToast", { name: tool.toolName, error: err?.message || err }));
    } finally {
      setBusyToolId(null);
    }
  }

  function openTest(tool: OpenApiTool) {
    if (testTool?.id === tool.id) {
      setTestTool(null);
      return;
    }
    setTestTool(tool);
    setTestResult(null);
    // Seed the input with every property of the tool's schema so the person
    // sees what the agent would have to supply, rather than an empty {}.
    let seed = "{}";
    try {
      const schema = tool.inputSchemaJson ? JSON.parse(tool.inputSchemaJson) : null;
      const props =
        schema && schema.properties && typeof schema.properties === "object" ? Object.keys(schema.properties) : [];
      if (props.length > 0) {
        const obj: Record<string, string> = {};
        for (const p of props) obj[p] = "";
        seed = JSON.stringify(obj, null, 2);
      }
    } catch {
      /* unparseable schema - keep {} */
    }
    setTestInput(seed);
  }

  async function handleTest() {
    if (!testTool) return;
    setTesting(true);
    setTestResult(null);
    try {
      const res = await api.testOpenApiTool(testTool.sourceId, testTool.id, testInput);
      let pretty = res?.resultJson ?? "";
      try {
        pretty = JSON.stringify(JSON.parse(pretty), null, 2);
      } catch {
        /* not JSON - show raw */
      }
      setTestResult(pretty);
    } catch (err: any) {
      setTestResult(`{"error": ${JSON.stringify(err?.message || t("testFailed"))}}`);
    } finally {
      setTesting(false);
    }
  }

  function authLabel(type: AuthType) {
    if (type === "BEARER") return t("authBearer");
    if (type === "API_KEY_HEADER") return t("authApiKeyHeader");
    if (type === "OAUTH2_CLIENT_CREDENTIALS") return t("authOauth2");
    return t("authNone");
  }

  // Shared client-side pagination for the three unbounded record lists in
  // this panel - the sources list, one expanded source's tools (only one
  // source is expanded at a time, so a single hook keyed off expandedId
  // covers it), and the import preview's operations checklist.
  const sourcesPagination = usePagination(sources);
  const toolsPagination = usePagination(expandedId ? toolsBySource[expandedId] || [] : []);
  const { setPage: setToolsPage } = toolsPagination;
  useEffect(() => {
    setToolsPage(1);
  }, [expandedId, setToolsPage]);
  const previewPagination = usePagination(preview?.operations || []);

  return (
    <Section
      title={t("sectionLabel", { count: sources.length })}
      className="space-y-4"
      actions={
        canManageTools ? (
          <Button type="button" variant="outline" size="sm" onClick={openImport}>
            <FileJson2 aria-hidden="true" size={14} /> {t("importButton")}
          </Button>
        ) : null
      }
    >
      {loadError && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]">
          <span>{loadError}</span>
          <Button type="button" variant="outline" size="sm" onClick={load}>
            {t("retryButton")}
          </Button>
        </div>
      )}

      {!canManageTools && !loading && <p className="text-xs text-muted-foreground">{t("readOnlyNote")}</p>}

      {!loading && sources.length === 0 && !loadError && !showImport && (
        <EmptyState
          icon={FileJson2}
          title={t("emptyTitle")}
          description={t("emptyBody")}
          action={
            canManageTools ? (
              <Button type="button" size="sm" onClick={openImport}>
                <FileJson2 aria-hidden="true" size={14} /> {t("importButton")}
              </Button>
            ) : undefined
          }
        />
      )}

      {!loading && sources.length > 0 && (
        <OpenApiSourceList
          authLabel={authLabel}
          busyId={busyId}
          busyToolId={busyToolId}
          canManageTools={canManageTools}
          expandedId={expandedId}
          handleRefresh={handleRefresh}
          handleTest={handleTest}
          handleToggleSource={handleToggleSource}
          handleToggleTool={handleToggleTool}
          openEdit={openEdit}
          openTest={openTest}
          setDeleteTarget={setDeleteTarget}
          setTestInput={setTestInput}
          setTestTool={setTestTool}
          sourcesPagination={sourcesPagination}
          testInput={testInput}
          testResult={testResult}
          testTool={testTool}
          testing={testing}
          toggleExpanded={toggleExpanded}
          toolsBySource={toolsBySource}
          toolsLoading={toolsLoading}
          toolsPagination={toolsPagination}
        />
      )}

      {editing && (
        <OpenApiEditPanel
          editBusy={editBusy}
          editError={editError}
          editForm={editForm}
          editing={editing}
          handleSaveSettings={handleSaveSettings}
          setEditForm={setEditForm}
          setEditing={setEditing}
        />
      )}

      {showImport && (
        <OpenApiImportWizard
          closeImport={closeImport}
          handleImport={handleImport}
          handlePreview={handlePreview}
          importError={importError}
          importing={importing}
          preview={preview}
          previewPagination={previewPagination}
          previewing={previewing}
          selectAll={selectAll}
          selectedKeys={selectedKeys}
          setSetting={setSetting}
          setSource={setSource}
          setStep={setStep}
          settings={settings}
          sourceForm={sourceForm}
          step={step}
          toggleKey={toggleKey}
        />
      )}

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open: boolean) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteDialogTitle", { name: deleteTarget?.name || "" })}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("deleteDialogBody", { count: deleteTarget?.toolCount ?? 0 })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t("cancelButton")}</AlertDialogCancel>
            <AlertDialogAction disabled={deleting} onClick={handleDelete}>
              {deleting ? t("deletingEllipsis") : t("deleteConfirmButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  );
});

export default OpenApiToolSourcesPanel;

"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { KeyRound, Plus, ChevronDown, ChevronUp } from "lucide-react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import EmptyState from "../../components/EmptyState";
import McpAccessHint, { LoadErrorRetry } from "./McpAccessHint";
import CheckboxPicker from "./CheckboxPicker";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { toggleInSet } from "./mcpGrants";
import type { GrantIdsPayload, McpServerGrant, McpServerGrantCreated, NamedItem } from "./mcpGrants";
import { KeyRevealDialog } from "./KeyRevealDialog";
import { ConfirmDialog } from "./ConfirmDialog";
import { GrantRow } from "./GrantRow";

// Tenant-admin UI for core/mcpserver's McpServerAccessController
// (/api/mcpserver/grants) - the single piece that was missing to make the
// inbound-MCP feature genuinely usable: without this page, a tenant admin
// could only mint/rotate/revoke a grant by calling the REST API directly,
// which no real customer will ever do. Endpoints/DTOs read directly off
// that controller and its dto/*.java records before writing this, not
// guessed:
//   GET    /api/mcpserver/grants           -> McpServerAccessGrantResponse[]
//   POST   /api/mcpserver/grants           -> McpServerAccessGrantCreatedResponse
//   PUT    /api/mcpserver/grants/{id}      -> McpServerAccessGrantResponse
//   PATCH  /api/mcpserver/grants/{id}      -> McpServerAccessGrantResponse (enabled toggle)
//   POST   /api/mcpserver/grants/{id}/rotate -> McpServerAccessGrantCreatedResponse
//   DELETE /api/mcpserver/grants/{id}      -> 204
//
// exposedWorkflowIds/exposedKnowledgeCollectionIds are persisted, but only
// exposedAgentIds is enforced by any MCP tool today (McpServerAccessGrant's
// javadoc); the "advanced" section says so rather than implying otherwise.

export default function AdminMcpServersPage() {
  const t = useTranslations("adminMcpServersPage");
  // MANAGE_INTEGRATIONS scope (granular RBAC, 2026-09-23) - MCP grant/rotate/
  // revoke is the integrations permission server-side.
  const allowed = useRequireScope("MANAGE_INTEGRATIONS");

  const [grants, setGrants] = useState<McpServerGrant[]>([]);
  const [agents, setAgents] = useState<NamedItem[]>([]);
  const [workflows, setWorkflows] = useState<NamedItem[]>([]);
  const [collections, setCollections] = useState<NamedItem[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const [createName, setCreateName] = useState("");
  const [createAgentIds, setCreateAgentIds] = useState(() => new Set<string>());
  const [createWorkflowIds, setCreateWorkflowIds] = useState(() => new Set<string>());
  const [createKnowledgeIds, setCreateKnowledgeIds] = useState(() => new Set<string>());
  const [createAdvancedOpen, setCreateAdvancedOpen] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editBusy, setEditBusy] = useState(false);

  const [toggleBusyIds, setToggleBusyIds] = useState(() => new Set<string>());
  const [rotateBusyIds, setRotateBusyIds] = useState(() => new Set<string>());
  const [revokeBusyIds, setRevokeBusyIds] = useState(() => new Set<string>());

  // The one-time key-reveal dialog, shared by create and rotate (both return
  // a McpServerAccessGrantCreatedResponse).
  const [reveal, setReveal] = useState<{ title: string; rawApiKey: string } | null>(null);

  // Replaces window.confirm() for both destructive-ish actions below (see
  // ConfirmDialog).
  const [confirmAction, setConfirmAction] = useState<{ type: "rotate" | "revoke"; grant: McpServerGrant } | null>(null);

  const refresh = useCallback(async () => {
    try {
      setGrants(await api.listMcpServerGrants());
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err, t("loadErrorFallback")));
    }
    api
      .listAgents()
      .then(setAgents)
      .catch(() => setAgents([]));
    api
      .listWorkflows()
      .then(setWorkflows)
      .catch(() => setWorkflows([]));
    api
      .listCollections()
      .then(setCollections)
      .catch(() => setCollections([]));
    setLoaded(true);
  }, [t]);

  useEffect(() => {
    if (allowed) void refresh();
  }, [allowed, refresh]);
  // Non-scoped callers redirect to "/" (return null below) → ready immediately.
  usePageReady(loaded || !allowed);

  function replaceGrant(id: string, updated: McpServerGrant) {
    setGrants((prev) => prev.map((g) => (g.id === id ? updated : g)));
  }

  async function handleCreate() {
    if (!createName.trim()) {
      toast.error(t("createErrorFallback"));
      return;
    }
    setCreateBusy(true);
    try {
      const result: McpServerGrantCreated = await api.createMcpServerGrant({
        name: createName.trim(),
        exposedAgentIds: Array.from(createAgentIds),
        exposedWorkflowIds: Array.from(createWorkflowIds),
        exposedKnowledgeCollectionIds: Array.from(createKnowledgeIds),
      });
      setGrants((prev) => [result.grant, ...prev]);
      setCreateName("");
      setCreateAgentIds(new Set());
      setCreateWorkflowIds(new Set());
      setCreateKnowledgeIds(new Set());
      setCreateAdvancedOpen(false);
      toast.success(t("createSuccessToast", { name: result.grant.name }));
      setReveal({ title: t("revealTitleCreate"), rawApiKey: result.rawApiKey });
    } catch (err) {
      toast.error(errorMessage(err, t("createErrorFallback")));
    } finally {
      setCreateBusy(false);
    }
  }

  async function handleToggleEnabled(grant: McpServerGrant) {
    setToggleBusyIds((prev) => new Set(prev).add(grant.id));
    try {
      const updated = await api.setMcpServerGrantEnabled(grant.id, !grant.enabled);
      replaceGrant(grant.id, updated);
      toast.success(t(updated.enabled ? "enabledSuccessToast" : "disabledSuccessToast", { name: grant.name }));
    } catch (err) {
      toast.error(errorMessage(err, t("toggleEnabledError")));
    } finally {
      setToggleBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(grant.id);
        return next;
      });
    }
  }

  async function performRotate(grant: McpServerGrant) {
    setRotateBusyIds((prev) => new Set(prev).add(grant.id));
    try {
      const result: McpServerGrantCreated = await api.rotateMcpServerGrant(grant.id);
      replaceGrant(grant.id, result.grant);
      toast.success(t("rotateSuccessToast", { name: grant.name }));
      setReveal({ title: t("revealTitleRotate"), rawApiKey: result.rawApiKey });
    } catch (err) {
      toast.error(errorMessage(err, t("rotateError")));
    } finally {
      setRotateBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(grant.id);
        return next;
      });
    }
  }

  async function performRevoke(grant: McpServerGrant) {
    setRevokeBusyIds((prev) => new Set(prev).add(grant.id));
    try {
      await api.revokeMcpServerGrant(grant.id);
      setGrants((prev) => prev.filter((g) => g.id !== grant.id));
      if (editingId === grant.id) setEditingId(null);
      toast.success(t("revokeSuccessToast", { name: grant.name }));
    } catch (err) {
      toast.error(errorMessage(err, t("revokeError")));
    } finally {
      setRevokeBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(grant.id);
        return next;
      });
    }
  }

  function handleConfirmedAction() {
    if (!confirmAction) return;
    const { type, grant } = confirmAction;
    setConfirmAction(null);
    if (type === "rotate") void performRotate(grant);
    else if (type === "revoke") void performRevoke(grant);
  }

  async function handleSaveEdit(grant: McpServerGrant, payload: GrantIdsPayload) {
    setEditBusy(true);
    try {
      const updated = await api.updateMcpServerGrant(grant.id, payload);
      replaceGrant(grant.id, updated);
      setEditingId(null);
      toast.success(t("updateSuccessToast", { name: grant.name }));
    } catch (err) {
      toast.error(errorMessage(err, t("updateErrorFallback")));
    } finally {
      setEditBusy(false);
    }
  }

  const grantsCountLabel = useMemo(
    () => t("grantsCount", { count: grants.length, plural: grants.length === 1 ? "" : "s" }),
    [grants.length, t]
  );

  // Client-side paging (shared hook, default 10/page) — a tenant can mint many
  // API-key grants over time, so the list must not grow unbounded.
  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(grants);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} subtitle={t("introHint")} />
      <McpAccessHint />

      {loadError && <LoadErrorRetry message={loadError} onRetry={() => void refresh()} />}

      <div className="panel" style={{ padding: "1.1rem 1.25rem", marginBottom: "1.1rem" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
          <Plus size={15} aria-hidden="true" />
          <span style={{ fontWeight: 600, fontSize: "0.95rem" }}>{t("createSectionTitle")}</span>
        </div>

        <div className="field">
          <Label htmlFor="mcp-grant-name" className="mb-1.5">
            {t("nameLabel")}
          </Label>
          <Input
            id="mcp-grant-name"
            type="text"
            placeholder={t("namePlaceholder")}
            value={createName}
            onChange={(e) => setCreateName(e.target.value)}
            maxLength={128}
          />
        </div>

        <div className="hint" style={{ marginBottom: 4 }}>
          {t("exposeAgentsLabel")}
        </div>
        <p className="hint" style={{ fontSize: "0.75rem", marginBottom: 8 }}>
          {t("exposeAgentsHint")}
        </p>
        <CheckboxPicker
          items={agents}
          selected={createAgentIds}
          onToggle={(id) => setCreateAgentIds((prev) => toggleInSet(prev, id))}
          emptyLabel={t("noAgentsYet")}
        />
        {agents.length > 0 && createAgentIds.size === 0 && (
          <p className="hint" style={{ fontSize: "0.75rem", marginTop: 8 }}>
            {t("noAgentsSelectedHint")}
          </p>
        )}

        <Button
          type="button"
          variant="outline"
          size="sm"
          className="rounded-full"
          style={{ marginTop: 12, marginBottom: 0 }}
          onClick={() => setCreateAdvancedOpen((v) => !v)}
        >
          {createAdvancedOpen ? (
            <ChevronUp size={13} aria-hidden="true" />
          ) : (
            <ChevronDown size={13} aria-hidden="true" />
          )}
          {createAdvancedOpen ? t("advancedToggleHide") : t("advancedToggleShow")}
        </Button>

        {createAdvancedOpen && (
          <div style={{ marginTop: 10 }}>
            <p className="hint" style={{ fontSize: "0.75rem", marginBottom: 10 }}>
              {t("advancedNotWiredNote")}
            </p>
            <div className="hint" style={{ marginBottom: 4 }}>
              {t("exposeWorkflowsLabel")}
            </div>
            <CheckboxPicker
              items={workflows}
              selected={createWorkflowIds}
              onToggle={(id) => setCreateWorkflowIds((prev) => toggleInSet(prev, id))}
              emptyLabel={t("noWorkflowsYet")}
            />
            <div className="hint" style={{ margin: "10px 0 4px" }}>
              {t("exposeKnowledgeLabel")}
            </div>
            <CheckboxPicker
              items={collections}
              selected={createKnowledgeIds}
              onToggle={(id) => setCreateKnowledgeIds((prev) => toggleInSet(prev, id))}
              emptyLabel={t("noKnowledgeYet")}
            />
          </div>
        )}

        <Button
          type="button"
          size="sm"
          style={{ marginTop: 14, marginBottom: 0 }}
          disabled={createBusy}
          onClick={handleCreate}
        >
          {createBusy ? t("creatingButton") : t("createButton")}
        </Button>
      </div>

      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 10 }}>
        <span style={{ fontWeight: 600, fontSize: "0.95rem" }}>{t("grantsSectionTitle")}</span>
        <span className="hint" style={{ fontSize: "0.78rem", marginBottom: 0 }}>
          {grantsCountLabel}
        </span>
      </div>

      {grants.length === 0 ? (
        <EmptyState icon={KeyRound} title={t("noGrantsTitle")} hint={t("noGrantsHint")} />
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: "0.8rem" }}>
          {/* Explicit param type: usePagination is untyped (items: any), so
              pageItems comes back `any` and the callback gets no contextual
              type - the annotation restores the real element type. */}
          {pageItems.map((grant: McpServerGrant) => (
            <GrantRow
              key={grant.id}
              grant={grant}
              agents={agents}
              workflows={workflows}
              collections={collections}
              editing={editingId === grant.id}
              editBusy={editBusy}
              onStartEdit={(g) => setEditingId(g.id)}
              onCancelEdit={() => setEditingId(null)}
              onSaveEdit={handleSaveEdit}
              onToggleEnabled={handleToggleEnabled}
              toggleBusy={toggleBusyIds.has(grant.id)}
              onRotate={(g) => setConfirmAction({ type: "rotate", grant: g })}
              rotateBusy={rotateBusyIds.has(grant.id)}
              onRevoke={(g) => setConfirmAction({ type: "revoke", grant: g })}
              revokeBusy={revokeBusyIds.has(grant.id)}
              t={t}
            />
          ))}
          <PaginationControls
            page={page}
            totalPages={totalPages}
            totalItems={totalItems}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            itemLabel={t("recordsLabel")}
          />
        </div>
      )}

      {reveal && (
        <KeyRevealDialog title={reveal.title} rawApiKey={reveal.rawApiKey} onClose={() => setReveal(null)} t={t} />
      )}

      {confirmAction && confirmAction.type === "rotate" && (
        <ConfirmDialog
          title={t("confirmRotateTitle")}
          message={t("rotateConfirm", { name: confirmAction.grant.name })}
          confirmLabel={t("rotateKeyButton")}
          cancelLabel={t("cancelButton")}
          danger={false}
          busy={rotateBusyIds.has(confirmAction.grant.id)}
          onConfirm={handleConfirmedAction}
          onCancel={() => setConfirmAction(null)}
        />
      )}

      {confirmAction && confirmAction.type === "revoke" && (
        <ConfirmDialog
          title={t("confirmRevokeTitle")}
          message={t("revokeConfirm", { name: confirmAction.grant.name })}
          confirmLabel={t("revokeButton")}
          cancelLabel={t("cancelButton")}
          danger
          busy={revokeBusyIds.has(confirmAction.grant.id)}
          onConfirm={handleConfirmedAction}
          onCancel={() => setConfirmAction(null)}
        />
      )}
    </AppShell>
  );
}

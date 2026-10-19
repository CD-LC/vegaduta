"use client";

// One MCP server access grant row and its inline edit panel (page.tsx).

import { useState } from "react";
import { RotateCw, Trash2, Pencil, ChevronDown, ChevronUp } from "lucide-react";
import CheckboxPicker from "./CheckboxPicker";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { idSet, toggleInSet } from "./mcpGrants";
import type { GrantIdsPayload, McpServerGrant, NamedItem, Translator } from "./mcpGrants";

// Inline "edit exposed agents/workflows/knowledge" panel for one existing
// grant - opened from that grant's "Edit agents" row action. Always sends
// all three id sets back on save (McpServerAccessGrantUpdateRequest fully
// REPLACES the stored sets - McpServerAccessGrantService#updateExposedIds -
// so omitting workflow/knowledge ids here would silently wipe out whatever
// was set at creation time, not just leave them untouched).
function EditGrantPanel({
  grant,
  agents,
  workflows,
  collections,
  onSave,
  onCancel,
  busy,
  t,
}: {
  grant: McpServerGrant;
  agents: NamedItem[];
  workflows: NamedItem[];
  collections: NamedItem[];
  onSave: (payload: GrantIdsPayload) => void;
  onCancel: () => void;
  busy: boolean;
  t: Translator;
}) {
  const [agentIds, setAgentIds] = useState(() => idSet(grant.exposedAgentIds));
  const [workflowIds, setWorkflowIds] = useState(() => idSet(grant.exposedWorkflowIds));
  const [knowledgeIds, setKnowledgeIds] = useState(() => idSet(grant.exposedKnowledgeCollectionIds));
  const [advancedOpen, setAdvancedOpen] = useState(false);

  return (
    <div className="panel" style={{ padding: "1rem 1.15rem", marginTop: "0.6rem" }}>
      <div
        className="hint"
        style={{ fontFamily: "var(--font-mono), monospace", letterSpacing: "0.06em", marginBottom: 6 }}
      >
        {t("exposeAgentsLabel")}
      </div>
      <CheckboxPicker
        items={agents}
        selected={agentIds}
        onToggle={(id) => setAgentIds((prev) => toggleInSet(prev, id))}
        emptyLabel={t("noAgentsYet")}
      />

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="rounded-full"
        style={{ marginTop: 10, marginBottom: 0 }}
        onClick={() => setAdvancedOpen((v) => !v)}
      >
        {advancedOpen ? <ChevronUp size={13} aria-hidden="true" /> : <ChevronDown size={13} aria-hidden="true" />}
        {advancedOpen ? t("advancedToggleHide") : t("advancedToggleShow")}
      </Button>

      {advancedOpen && (
        <div style={{ marginTop: 10 }}>
          <p className="hint" style={{ fontSize: "0.75rem", marginBottom: 10 }}>
            {t("advancedNotWiredNote")}
          </p>
          <div
            className="hint"
            style={{ fontFamily: "var(--font-mono), monospace", letterSpacing: "0.06em", marginBottom: 6 }}
          >
            {t("exposeWorkflowsLabel")}
          </div>
          <CheckboxPicker
            items={workflows}
            selected={workflowIds}
            onToggle={(id) => setWorkflowIds((prev) => toggleInSet(prev, id))}
            emptyLabel={t("noWorkflowsYet")}
          />
          <div
            className="hint"
            style={{ fontFamily: "var(--font-mono), monospace", letterSpacing: "0.06em", margin: "10px 0 6px" }}
          >
            {t("exposeKnowledgeLabel")}
          </div>
          <CheckboxPicker
            items={collections}
            selected={knowledgeIds}
            onToggle={(id) => setKnowledgeIds((prev) => toggleInSet(prev, id))}
            emptyLabel={t("noKnowledgeYet")}
          />
        </div>
      )}

      <div className="row" style={{ marginTop: "1rem" }}>
        <Button
          type="button"
          size="sm"
          style={{ marginBottom: 0 }}
          disabled={busy}
          onClick={() =>
            onSave({
              exposedAgentIds: Array.from(agentIds),
              exposedWorkflowIds: Array.from(workflowIds),
              exposedKnowledgeCollectionIds: Array.from(knowledgeIds),
            })
          }
        >
          {t("saveAgentsButton")}
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onCancel}>
          {t("cancelButton")}
        </Button>
      </div>
    </div>
  );
}

export function GrantRow({
  grant,
  agents,
  workflows,
  collections,
  editing,
  onStartEdit,
  onCancelEdit,
  onSaveEdit,
  editBusy,
  onToggleEnabled,
  toggleBusy,
  onRotate,
  rotateBusy,
  onRevoke,
  revokeBusy,
  t,
}: {
  grant: McpServerGrant;
  agents: NamedItem[];
  workflows: NamedItem[];
  collections: NamedItem[];
  editing: boolean;
  onStartEdit: (grant: McpServerGrant) => void;
  onCancelEdit: () => void;
  onSaveEdit: (grant: McpServerGrant, payload: GrantIdsPayload) => void;
  editBusy: boolean;
  onToggleEnabled: (grant: McpServerGrant) => void;
  toggleBusy: boolean;
  onRotate: (grant: McpServerGrant) => void;
  rotateBusy: boolean;
  onRevoke: (grant: McpServerGrant) => void;
  revokeBusy: boolean;
  t: Translator;
}) {
  const agentCount = grant.exposedAgentIds?.length || 0;
  const createdText = t("createdLabel", { value: new Date(grant.createdAt).toLocaleDateString() });
  const lastUsedText = grant.lastUsedAt
    ? t("lastUsedLabel", { value: new Date(grant.lastUsedAt).toLocaleString() })
    : t("neverUsedLabel");

  return (
    <div className="panel" style={{ padding: "1rem 1.15rem" }}>
      <div
        style={{
          display: "flex",
          alignItems: "flex-start",
          justifyContent: "space-between",
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
            <strong style={{ fontSize: "0.95rem" }}>{grant.name}</strong>
            <Badge
              variant="outline"
              className={
                grant.enabled
                  ? "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
                  : "border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]"
              }
            >
              {grant.enabled ? t("statusEnabled") : t("statusDisabled")}
            </Badge>
          </div>
          <p
            className="hint"
            style={{ fontSize: "0.78rem", margin: "6px 0 0", fontFamily: "var(--font-mono), monospace" }}
          >
            {t("keyPrefixLabel")}: {grant.apiKeyDisplayPrefix}…
          </p>
          <p className="hint" style={{ fontSize: "0.78rem", margin: "4px 0 0" }}>
            {t("agentsExposedCount", { count: agentCount, plural: agentCount === 1 ? "" : "s" })} · {createdText} ·{" "}
            {lastUsedText}
          </p>
        </div>

        <div className="row" style={{ gap: 6 }}>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={toggleBusy}
            onClick={() => onToggleEnabled(grant)}
          >
            {grant.enabled ? t("disableButton") : t("enableButton")}
          </Button>
          <Button type="button" variant="outline" size="sm" disabled={editing} onClick={() => onStartEdit(grant)}>
            <Pencil className="size-3" aria-hidden="true" />
            {t("editAgentsButton")}
          </Button>
          <Button type="button" variant="outline" size="sm" disabled={rotateBusy} onClick={() => onRotate(grant)}>
            <RotateCw className="size-3" aria-hidden="true" />
            {rotateBusy ? t("rotatingButton") : t("rotateKeyButton")}
          </Button>
          <Button type="button" variant="destructive" size="sm" disabled={revokeBusy} onClick={() => onRevoke(grant)}>
            <Trash2 className="size-3" aria-hidden="true" />
            {revokeBusy ? t("revokingButton") : t("revokeButton")}
          </Button>
        </div>
      </div>

      {editing && (
        <EditGrantPanel
          grant={grant}
          agents={agents}
          workflows={workflows}
          collections={collections}
          busy={editBusy}
          onCancel={onCancelEdit}
          onSave={(payload) => onSaveEdit(grant, payload)}
          t={t}
        />
      )}
    </div>
  );
}

"use client";

// Agent lifecycle hooks (core /api/agents/{id}/hooks): declarative rules that run at set points in a run - block or
// limit a tool, require approval, redact, add a note, call a webhook, log. Built-in actions only; hooks can only make
// an agent stricter. This card edits the agent's own list and shows the workspace defaults that also apply.
//
// Self-hiding like AgentRunBudgetField: an older core has no such route (404), and a failed read hides the card
// rather than showing a broken editor. `locked` comes from the parent because the free-tier edit lock is a
// per-control obligation (see AgentGuardrailsCard).

import { useEffect, useMemo, useState } from "react";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { useAuth } from "../AuthProvider";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Switch } from "../ui/switch";
import GuardrailActivity from "./GuardrailActivity";
import HookInterceptExplainer from "./HookInterceptExplainer";
import HookEditor from "./hooks/HookEditor";
import {
  MAX_HOOKS,
  describeDraft,
  draftFromWire,
  emptyDraft,
  hasErrors,
  hooksPayload,
  nextFreeId,
  normalizeAgentHooks,
  validateAll,
  validateDraft,
  type AgentHooksView,
  type HookDraft,
  type Translate,
} from "./agentHooks";
import BrandText from "../BrandText";

type Editing = { index: number; draft: HookDraft } | null;

export default function AgentHooksCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("agentHooks") as Translate;
  const { isAdmin, hasPermission } = useAuth();
  // Keyed by the agent it was fetched for, so switching agents never shows (or saves against) the previous agent's hooks.
  const [loaded, setLoaded] = useState<{ agentId: string; view: AgentHooksView } | null>(null);
  const [drafts, setDrafts] = useState<HookDraft[]>([]);
  const [savedJson, setSavedJson] = useState("[]");
  const [editing, setEditing] = useState<Editing>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "error" | "saved"; text: string } | null>(null);

  function adopt(forAgent: string, view: AgentHooksView) {
    const next = view.hooks.map(draftFromWire);
    setLoaded({ agentId: forAgent, view });
    setDrafts(next);
    setSavedJson(JSON.stringify(hooksPayload(next)));
  }

  useEffect(() => {
    setMessage(null);
    setEditing(null);
    setLoaded(null);
    let cancelled = false;
    api
      .getAgentHooks(agentId)
      .then((raw: unknown) => {
        if (cancelled) return;
        const view = normalizeAgentHooks(raw);
        if (view) adopt(agentId, view);
      })
      .catch(() => {
        // 404 (older core / agent not visible) or a transient failure: hide the card.
        if (!cancelled) setLoaded(null);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const view = loaded && loaded.agentId === agentId ? loaded.view : null;
  const dirty = useMemo(() => JSON.stringify(hooksPayload(drafts)) !== savedJson, [drafts, savedJson]);
  if (!view) return null;

  const mayManage = isAdmin || hasPermission("MANAGE_GUARDRAILS");
  const canEdit = view.enabled && !locked && mayManage;
  const disabledTitle = locked ? editLockedTooltip : !mayManage ? t("noPermission") : undefined;

  function startAdd() {
    if (!canEdit || drafts.length >= MAX_HOOKS) return;
    const draft = emptyDraft();
    draft.id = nextFreeId(draft.action, drafts.map((d) => d.id));
    setMessage(null);
    setEditing({ index: -1, draft });
  }

  function startEdit(index: number) {
    if (!canEdit) return;
    setMessage(null);
    setEditing({ index, draft: { ...drafts[index], patterns: [...drafts[index].patterns] } });
  }

  function commitEdit() {
    if (!editing) return;
    const others = drafts.filter((_, i) => i !== editing.index).map((d) => d.id.trim());
    if (hasErrors(validateDraft(editing.draft, others))) return;
    setDrafts((list) => {
      const next = [...list];
      if (editing.index < 0) next.push(editing.draft);
      else next[editing.index] = editing.draft;
      return next;
    });
    setEditing(null);
  }

  function removeAt(index: number) {
    if (!canEdit) return;
    setDrafts((list) => list.filter((_, i) => i !== index));
    setEditing(null);
  }

  function toggleAt(index: number, enabled: boolean) {
    if (!canEdit) return;
    setDrafts((list) => list.map((d, i) => (i === index ? { ...d, enabled } : d)));
  }

  function discard() {
    setDrafts(view ? view.hooks.map(draftFromWire) : []);
    setEditing(null);
    setMessage(null);
  }

  async function save() {
    if (!canEdit || busy) return;
    const { tooMany, errors } = validateAll(drafts);
    if (tooMany) {
      setMessage({ kind: "error", text: t("tooMany", { max: MAX_HOOKS }) });
      return;
    }
    if (errors.some(hasErrors)) {
      setMessage({ kind: "error", text: t("saveFailed") });
      return;
    }
    const requestedFor = agentId;
    setBusy(true);
    setMessage(null);
    try {
      const next = normalizeAgentHooks(await api.setAgentHooks(requestedFor, hooksPayload(drafts).hooks));
      if (next) adopt(requestedFor, next);
      setMessage({ kind: "saved", text: t("saved") });
    } catch (err: any) {
      setMessage({ kind: "error", text: err?.message || t("saveFailed") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-6 rounded-lg border border-border bg-card p-4" data-testid="agent-hooks-card">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground"><BrandText text={t("title")} /></h3>
          <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground"><BrandText text={t("subtitle")} /></p>
        </div>
        <span className="mt-0.5 shrink-0 text-xs text-muted-foreground" data-testid="agent-hooks-count">
          {t("count", { count: drafts.length, max: MAX_HOOKS })}
        </span>
      </div>

      {!view.enabled && (
        <p className="mt-3 rounded-md border border-border bg-secondary/40 px-3 py-2 text-xs text-muted-foreground" data-testid="agent-hooks-off">
          {t("off")}
        </p>
      )}

      <HookInterceptExplainer className="mt-3" />
      <GuardrailActivity kind="hooks" agentId={agentId} />

      {view.inherited.length > 0 && (
        <div className="mt-4" data-testid="agent-hooks-inherited">
          <h4 className="text-xs font-semibold text-foreground">{t("inheritedTitle")}</h4>
          <p className="mt-0.5 text-xs text-muted-foreground">{t("inheritedNote")}</p>
          <ul className="mt-2 space-y-1.5">
            {view.inherited.map((h) => (
              <li key={`inherited-${h.id}`} className="rounded-md border border-border bg-secondary/30 px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <code className="text-xs text-foreground">{h.id}</code>
                  <Badge variant="outline">{t("workspaceBadge")}</Badge>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{describeDraft(draftFromWire(h), t)}</p>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="mt-4">
        <h4 className="text-xs font-semibold text-foreground">{t("ownTitle")}</h4>
        {drafts.length === 0 && !editing ? (
          <p className="mt-1 text-xs text-muted-foreground" data-testid="agent-hooks-empty">
            {t("empty")}
          </p>
        ) : (
          <ul className="mt-2 space-y-1.5" data-testid="agent-hooks-list">
            {drafts.map((d, i) => (
              <li
                key={`own-${i}-${d.id}`}
                className={cn("rounded-md border border-border px-3 py-2", !d.enabled && "opacity-60")}
                data-testid={`agent-hook-${d.id}`}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Switch
                    checked={d.enabled}
                    disabled={!canEdit || busy}
                    title={disabledTitle}
                    aria-label={t("enabledLabel", { id: d.id })}
                    onCheckedChange={(on) => toggleAt(i, on)}
                  />
                  <code className="text-xs text-foreground">{d.id}</code>
                  <span className="ml-auto flex gap-1">
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={!canEdit || busy}
                      title={disabledTitle}
                      onClick={() => startEdit(i)}
                    >
                      <Pencil size={13} /> {t("edit")}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={!canEdit || busy}
                      title={disabledTitle}
                      onClick={() => removeAt(i)}
                    >
                      <Trash2 size={13} /> {t("remove")}
                    </Button>
                  </span>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{describeDraft(d, t)}</p>
              </li>
            ))}
          </ul>
        )}
      </div>

      {editing && (
        <HookEditor
          t={t}
          draft={editing.draft}
          otherIds={drafts.filter((_, i) => i !== editing.index).map((d) => d.id.trim())}
          webhookHosts={view.webhookHosts}
          onChange={(draft) => setEditing({ index: editing.index, draft })}
          onDone={commitEdit}
          onCancel={() => setEditing(null)}
        />
      )}

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          data-testid="agent-hooks-add"
          disabled={!canEdit || busy || drafts.length >= MAX_HOOKS || editing !== null}
          title={disabledTitle}
          onClick={startAdd}
        >
          <Plus size={14} /> {t("add")}
        </Button>
        <Button
          type="button"
          size="sm"
          data-testid="agent-hooks-save"
          disabled={!canEdit || busy || !dirty || editing !== null}
          title={disabledTitle}
          onClick={() => void save()}
        >
          {busy ? t("saving") : t("save")}
        </Button>
        {dirty && (
          <>
            <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={discard}>
              {t("discard")}
            </Button>
            <span className="text-xs text-muted-foreground">{t("unsaved")}</span>
          </>
        )}
      </div>
      {message && (
        <p
          className={message.kind === "error" ? "mt-2 text-xs text-destructive" : "mt-2 text-xs text-muted-foreground"}
          role={message.kind === "error" ? "alert" : "status"}
          data-testid={`agent-hooks-${message.kind}`}
        >
          {message.text}
        </p>
      )}
    </div>
  );
}

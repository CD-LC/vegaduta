"use client";

import { useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { Info, Search } from "lucide-react";
import {
  settingsApi,
  type SettingRisk,
  type SettingRole,
  type SettingScope,
  type SettingsClient,
} from "../../lib/api/settings";
import { AuthContext } from "../AuthProvider";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import { Skeleton } from "../ui/skeleton";
import RiskyChangeDialog, { type RiskyChange } from "./RiskyChangeDialog";
import SettingHistoryDrawer from "./SettingHistoryDrawer";
import SettingsGroup from "./SettingsGroup";
import { buildRiskyChange } from "./riskyChange";
import { isDirty, needsReason, parseDraft, resolveSettingsRole, type Draft } from "./settingsModel";
import { useEffectiveSettings, useSettingsCatalog, useSettingsWriter, type SettingChange } from "./useSettings";
import { useSettingsText } from "./useSettingsText";

export interface SettingsPanelProps {
  /** "tenant": workspace-wide defaults. "agent": this agent's overrides on top of the tenant values (needs agentId). */
  scope: SettingScope;
  agentId?: string;
  /** Defaults to the console's authenticated client; inject a fake in tests. */
  client?: SettingsClient;
  /** The viewer's role. Omit to leave gating to the server; below an entry's minRole it renders read-only. */
  role?: SettingRole;
  /** Force every field read-only (e.g. a plan lock). */
  readOnly?: boolean;
  /** Show only these catalog groups, e.g. ["agent", "chat"]. */
  groups?: string[];
  /** Show only these keys. */
  keys?: string[];
  /** Search + group + risk filters. Default true; embeds showing a handful of keys turn them off. */
  showFilters?: boolean;
  /** Heading text; defaults to the localized panel title. Pass null to render no heading. */
  heading?: string | null;
  onSaved?: (key: string) => void;
}

const ALL = "__all__";
const RISKS: SettingRisk[] = ["low", "medium", "high", "security"];

/**
 * The whole catalog-driven settings screen for one scope: loads the catalog and the effective values, groups
 * the settings, filters them, edits them as drafts with a save bar per group, confirms risky changes with a typed
 * reason, writes optimistically (rolling back and showing the server's reason on failure) and opens a change
 * history drawer per setting. Everything it shows - bounds, units, options, risk, who may change what - comes
 * from the catalog.
 */
export default function SettingsPanel(props: SettingsPanelProps) {
  // Remount when the target changes so drafts from one agent can never be saved onto another.
  return <SettingsPanelInner key={`${props.scope}:${props.agentId ?? ""}`} {...props} />;
}

function SettingsPanelInner({
  scope,
  agentId,
  client = settingsApi,
  role: roleProp,
  readOnly,
  groups,
  keys,
  showFilters = true,
  heading,
  onSaved,
}: SettingsPanelProps) {
  const text = useSettingsText();
  const { ui, label, description, groupLabel } = text;
  // The caller's role: what the page passed, else (agent scope) tenant admin or editor from the signed-in session,
  // so a security key is not offered for editing to someone who would get a 403 after saving.
  const auth = useContext(AuthContext) as { isAdmin?: boolean; isPlatformAdmin?: boolean } | null;
  const role = resolveSettingsRole(roleProp, scope, auth);
  const catalog = useSettingsCatalog(client);
  const effective = useEffectiveSettings(client, scope, agentId);

  const scoped = useMemo(
    () =>
      catalog.entries.filter(
        (e) => e.scopes.includes(scope) && (!groups || groups.includes(e.group)) && (!keys || keys.includes(e.key))
      ),
    [catalog.entries, scope, groups, keys]
  );
  const writer = useSettingsWriter({ client, scope, agentId, entries: catalog.entries, effective, onSaved });
  const { clearError } = writer;

  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [search, setSearch] = useState("");
  const [groupFilter, setGroupFilter] = useState(ALL);
  const [riskFilter, setRiskFilter] = useState(ALL);
  const [historyKey, setHistoryKey] = useState<string | null>(null);
  const [dialog, setDialog] = useState<{
    changes: SettingChange[];
    risky: RiskyChange[];
    /** Set when the dialog asks for the reason of a reset instead of a save. */
    reset?: { key: string; resolve: (ok: boolean) => void };
  } | null>(null);
  const [message, setMessage] = useState("");
  const draftsRef = useRef(drafts);
  draftsRef.current = drafts;

  // Closing the tab or navigating away with unsaved edits asks first (the browser shows its own generic prompt).
  const hasUnsaved = scoped.some((e) =>
    isDirty(e, drafts[e.key], effective.values[e.key] ? effective.values[e.key].value : e.default)
  );
  useEffect(() => {
    if (!hasUnsaved) return undefined;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [hasUnsaved]);

  const groupIds = useMemo(() => Array.from(new Set(scoped.map((e) => e.group))), [scoped]);
  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    return scoped.filter((e) => {
      if (groupFilter !== ALL && e.group !== groupFilter) return false;
      if (riskFilter !== ALL && e.risk !== riskFilter) return false;
      if (!q) return true;
      return [e.key, label(e), description(e)].some((s) => s.toLowerCase().includes(q));
    });
  }, [scoped, search, groupFilter, riskFilter, label, description]);
  const visibleGroups = groupIds.filter((g) => visible.some((e) => e.group === g));
  const filtered = search.trim() !== "" || groupFilter !== ALL || riskFilter !== ALL;

  const onDraftChange = useCallback(
    (key: string, draft: Draft | undefined) => {
      setDrafts((cur) => {
        const next = { ...cur };
        if (draft) next[key] = draft;
        else delete next[key];
        return next;
      });
      setMessage("");
      clearError(key);
    },
    [clearError]
  );

  const focusField = (key: string) => {
    // After the DOM updates, put focus on the control of the first field that needs attention.
    setTimeout(() => {
      document
        .querySelector<HTMLElement>(
          `[data-testid="setting-${key}"] input, [data-testid="setting-${key}"] textarea, [data-testid="setting-${key}"] [role="switch"], [data-testid="setting-${key}"] [role="combobox"]`
        )
        ?.focus();
    }, 0);
  };

  const runCommit = useCallback(
    async (changes: SettingChange[]) => {
      const saved = await writer.commit(changes);
      setDrafts((cur) => {
        const next = { ...cur };
        for (const k of saved) delete next[k];
        return next;
      });
      const failed = changes.filter((c) => !saved.includes(c.key));
      setMessage(failed.length ? ui("saveFailed", { count: failed.length }) : ui("saved", { count: saved.length }));
      if (failed.length) focusField(failed[0].key);
    },
    [writer, ui]
  );

  const onSave = useCallback(
    async (saveKeys: string[]) => {
      const changes: SettingChange[] = [];
      const risky: RiskyChange[] = [];
      for (const key of saveKeys) {
        const entry = scoped.find((e) => e.key === key);
        const draft = draftsRef.current[key];
        if (!entry || !draft) continue;
        const parsed = parseDraft(entry, draft);
        if (!parsed.ok) continue;
        changes.push({ key, value: parsed.value });
        if (needsReason(entry)) {
          risky.push(
            buildRiskyChange(
              text,
              entry,
              effective.values[key] ? effective.values[key].value : entry.default,
              parsed.value
            )
          );
        }
      }
      if (!changes.length) return;
      if (risky.length) {
        setDialog({ changes, risky });
        return;
      }
      await runCommit(changes);
    },
    [scoped, text, effective.values, runCommit]
  );

  const confirmRisky = async (reason: string) => {
    if (!dialog) return;
    if (dialog.reset) {
      const ok = await doReset(dialog.reset.key, reason);
      dialog.reset.resolve(ok);
      setDialog(null);
      return;
    }
    const riskyKeys = new Set(dialog.risky.map((r) => r.key));
    const changes = dialog.changes.map((c) => (riskyKeys.has(c.key) ? { ...c, reason } : c));
    await runCommit(changes);
    setDialog(null);
  };

  const onDiscard = useCallback(
    (discardKeys: string[]) => {
      setDrafts((cur) => {
        const next = { ...cur };
        for (const k of discardKeys) delete next[k];
        return next;
      });
      discardKeys.forEach(clearError);
      setMessage("");
    },
    [clearError]
  );

  const doReset = useCallback(
    async (key: string, reason?: string) => {
      setDrafts((cur) => {
        if (!(key in cur)) return cur;
        const next = { ...cur };
        delete next[key];
        return next;
      });
      const ok = await writer.reset(key, reason);
      const entry = scoped.find((e) => e.key === key);
      setMessage(ok ? ui("resetDone", { label: entry ? label(entry) : key }) : ui("resetFailed"));
      return ok;
    },
    [writer, ui, label, scoped]
  );

  // A reset of a high-risk or security setting asks for a typed reason, like a save does; it resolves with whether the
  // reset happened (false when the dialog is cancelled), so the field can hand focus back only after a real reset.
  const onReset = useCallback(
    (key: string): Promise<boolean> => {
      const entry = scoped.find((e) => e.key === key);
      if (!entry || !needsReason(entry)) return doReset(key);
      const now = effective.values[key] ? effective.values[key].value : entry.default;
      const back = scope === "agent" && effective.inherited[key] ? effective.inherited[key].value : entry.default;
      return new Promise<boolean>((resolve) =>
        setDialog({ changes: [], risky: [buildRiskyChange(text, entry, now, back, "reset")], reset: { key, resolve } })
      );
    },
    [scoped, doReset, effective.values, effective.inherited, scope, text]
  );

  const title = heading === undefined ? ui(scope === "agent" ? "title.agent" : "title.tenant") : heading;
  const loading = catalog.status === "loading" || effective.status === "loading";
  const failed = catalog.status === "error" || effective.status === "error";
  const retry = () => {
    catalog.reload();
    void effective.reload();
  };

  if (scope === "agent" && !agentId) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{ui("agentMissing")}</AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="space-y-5" data-testid="settings-panel">
      {title ? <h2 className="text-lg font-semibold">{title}</h2> : null}

      {scope === "agent" ? (
        <Alert variant="info">
          <Info aria-hidden="true" />
          <AlertDescription>{ui("scopeNote.agent")}</AlertDescription>
        </Alert>
      ) : null}

      {loading ? (
        <div
          role="status"
          aria-busy="true"
          aria-label={ui("loading")}
          className="space-y-3"
          data-testid="settings-loading"
        >
          <Skeleton className="h-10" />
          <Skeleton className="h-28" />
          <Skeleton className="h-28" />
        </div>
      ) : failed ? (
        <Alert variant="destructive" data-testid="settings-error">
          <AlertDescription>
            <span>{ui("loadError", { message: catalog.error ?? effective.error ?? "" })}</span>
            <Button type="button" size="sm" variant="outline" onClick={retry}>
              {ui("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : (
        <>
          {showFilters && scoped.length > 0 ? (
            <div
              className="flex flex-col gap-2 sm:flex-row sm:items-center"
              role="search"
              aria-label={ui("filters.label")}
            >
              <div className="relative sm:max-w-xs sm:flex-1">
                <Search
                  aria-hidden="true"
                  className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                />
                <Input
                  type="search"
                  className="ps-9"
                  aria-label={ui("filters.search")}
                  placeholder={ui("filters.searchPlaceholder")}
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>
              <div className="sm:w-48">
                <Select
                  aria-label={ui("filters.group")}
                  value={groupFilter}
                  onValueChange={setGroupFilter}
                  options={[
                    { value: ALL, label: ui("filters.allGroups") },
                    ...groupIds.map((g) => ({ value: g, label: groupLabel(g) })),
                  ]}
                />
              </div>
              <div className="sm:w-44">
                <Select
                  aria-label={ui("filters.risk")}
                  value={riskFilter}
                  onValueChange={setRiskFilter}
                  options={[
                    { value: ALL, label: ui("filters.allRisks") },
                    ...RISKS.map((r) => ({ value: r, label: ui(`risk.${r}`) })),
                  ]}
                />
              </div>
              {filtered ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setSearch("");
                    setGroupFilter(ALL);
                    setRiskFilter(ALL);
                  }}
                >
                  {ui("filters.clear")}
                </Button>
              ) : null}
            </div>
          ) : null}

          <p role="status" aria-live="polite" className="min-h-5 text-sm" data-testid="settings-message">
            {message}
          </p>

          {scoped.length === 0 ? (
            <p className="text-sm text-muted-foreground" data-testid="settings-empty">
              {ui("empty.none")}
            </p>
          ) : visible.length === 0 ? (
            <p className="text-sm text-muted-foreground" data-testid="settings-empty">
              {ui("empty.filtered")}
            </p>
          ) : (
            <div className="space-y-8">
              {visibleGroups.map((g) => (
                <SettingsGroup
                  key={g}
                  group={g}
                  entries={visible.filter((e) => e.group === g)}
                  scope={scope}
                  values={effective.values}
                  inherited={effective.inherited}
                  drafts={drafts}
                  errors={writer.errors}
                  pending={writer.pending}
                  role={role}
                  readOnly={readOnly}
                  onDraftChange={onDraftChange}
                  onSave={onSave}
                  onDiscard={onDiscard}
                  onReset={onReset}
                  onOpenHistory={setHistoryKey}
                />
              ))}
            </div>
          )}
        </>
      )}

      <RiskyChangeDialog
        open={dialog != null}
        changes={dialog?.risky ?? []}
        onConfirm={confirmRisky}
        onCancel={() => {
          dialog?.reset?.resolve(false);
          setDialog(null);
        }}
      />
      <SettingHistoryDrawer
        entry={historyKey ? (catalog.entries.find((e) => e.key === historyKey) ?? null) : null}
        scope={scope}
        agentId={agentId}
        client={client}
        onClose={() => setHistoryKey(null)}
      />
    </div>
  );
}

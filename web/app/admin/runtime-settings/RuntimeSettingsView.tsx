"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Download, Info, Search, ShieldAlert, Upload } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { settingsApi, type SettingRole, type SettingsHistoryClient } from "../../lib/api/settings";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Checkbox } from "../../components/ui/checkbox";
import { ConfirmDialog } from "../../components/ui/confirm-dialog";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Select } from "../../components/ui/select";
import { Skeleton } from "../../components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/tabs";
import RiskyChangeDialog from "../../components/settings/RiskyChangeDialog";
import SettingHistoryDrawer from "../../components/settings/SettingHistoryDrawer";
import SettingsGroup from "../../components/settings/SettingsGroup";
import { useEffectiveSettings, useSettingsCatalog } from "../../components/settings/useSettings";
import { useSettingsText } from "../../components/settings/useSettingsText";
import ChangeHistoryTab from "./ChangeHistoryTab";
import ImportOverridesDialog from "./ImportOverridesDialog";
import { buildExport, isSecurityGroup, orderGroups, type ImportPlan } from "./overridesModel";
import { useSettingsEditor } from "./useSettingsEditor";

const ALL = "__all__";
const HIGHLIGHT = ["ring-2", "ring-primary", "ring-offset-2", "ring-offset-background"];

function readHash(): string {
  if (typeof window === "undefined") return "";
  const raw = window.location.hash.replace(/^#/, "");
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

function downloadJson(filename: string, data: unknown): void {
  if (typeof URL === "undefined" || typeof URL.createObjectURL !== "function") return;
  const blob = new Blob([`${JSON.stringify(data, null, 2)}\n`], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * The workspace runtime settings screen. Everything it shows - which settings exist, their bounds, units, options,
 * risk and who may change them - comes from the settings catalog; this component adds the page-level tools around
 * the shared field components: search and a "changed here" filter, group jump links, a separated security area,
 * reset of a whole group, export / import of the workspace overrides, the change history of every setting, and
 * deep links to a single setting (#<key>) or group (#group-<id>).
 */
export default function RuntimeSettingsView({
  client = settingsApi,
  viewerRole,
}: {
  client?: SettingsHistoryClient;
  /** The viewer's role. Anything below TENANT_ADMIN sees every value but cannot change it. */
  viewerRole: SettingRole;
}) {
  const t = useTranslations("runtimeSettings");
  const text = useSettingsText();
  const { ui, label, description, groupLabel } = text;
  const catalog = useSettingsCatalog(client);
  const effective = useEffectiveSettings(client, "tenant");
  const canEdit = viewerRole === "TENANT_ADMIN";

  const scoped = useMemo(() => catalog.entries.filter((e) => e.scopes.includes("tenant")), [catalog.entries]);
  const editor = useSettingsEditor({ client, entries: scoped, effective, text });

  const [tab, setTab] = useState("settings");
  const [search, setSearch] = useState("");
  const [groupFilter, setGroupFilter] = useState(ALL);
  const [changedOnly, setChangedOnly] = useState(false);
  const [historyKey, setHistoryKey] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<{ group: string; keys: string[] } | null>(null);
  const [hash, setHash] = useState("");

  const ready = catalog.status === "ready" && effective.status === "ready";
  const isOverridden = useCallback((key: string) => effective.values[key]?.source === "tenant", [effective.values]);

  const groupIds = useMemo(() => orderGroups(Array.from(new Set(scoped.map((e) => e.group)))), [scoped]);
  const changedCount = scoped.filter((e) => isOverridden(e.key)).length;

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    return scoped.filter((e) => {
      if (groupFilter !== ALL && e.group !== groupFilter) return false;
      if (changedOnly && !isOverridden(e.key)) return false;
      if (!q) return true;
      return [e.key, label(e), description(e)].some((s) => s.toLowerCase().includes(q));
    });
  }, [scoped, search, groupFilter, changedOnly, isOverridden, label, description]);
  const visibleGroups = groupIds.filter((g) => visible.some((e) => e.group === g));
  const filtered = search.trim() !== "" || groupFilter !== ALL || changedOnly;
  const clearFilters = useCallback(() => {
    setSearch("");
    setGroupFilter(ALL);
    setChangedOnly(false);
  }, []);

  // ---- deep links: #<setting key> and #group-<group id> ----
  useEffect(() => {
    const read = () => setHash(readHash());
    read();
    window.addEventListener("hashchange", read);
    return () => window.removeEventListener("hashchange", read);
  }, []);

  const targetKey = hash && scoped.some((e) => e.key === hash) ? hash : null;
  const targetGroup = hash.startsWith("group-") && groupIds.includes(hash.slice(6)) ? hash.slice(6) : null;
  const unknownAnchor = ready && hash !== "" && !targetKey && !targetGroup;
  const handled = useRef("");
  const highlightTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    handled.current = "";
    if (hash) setTab("settings");
  }, [hash]);

  useEffect(() => {
    if (!ready || tab !== "settings" || handled.current === hash) return;
    if (targetKey) {
      if (!visible.some((e) => e.key === targetKey)) {
        // A filter is hiding the setting the link points at: clear it, and this effect runs again.
        clearFilters();
        return;
      }
      const el = Array.from(document.querySelectorAll<HTMLElement>('[data-testid^="setting-"]')).find(
        (n) => n.getAttribute("data-testid") === `setting-${targetKey}`
      );
      if (!el) return;
      handled.current = hash;
      el.id = targetKey;
      el.scrollIntoView?.({ block: "center" });
      // The control itself (not the badge or history buttons that come first in the markup).
      (
        el.querySelector<HTMLElement>("input:not([type='range']), textarea, [role='switch'], [role='combobox']") ??
        el.querySelector<HTMLElement>("button")
      )?.focus({
        preventScroll: true,
      });
      el.classList.add(...HIGHLIGHT);
      if (highlightTimer.current) clearTimeout(highlightTimer.current);
      highlightTimer.current = setTimeout(() => el.classList.remove(...HIGHLIGHT), 3000);
    } else if (targetGroup) {
      const el = document.getElementById(`group-${targetGroup}`);
      if (!el) {
        if (!visibleGroups.includes(targetGroup)) clearFilters();
        return;
      }
      handled.current = hash;
      el.scrollIntoView?.({ block: "start" });
      el.focus({ preventScroll: true });
    }
  }, [ready, tab, hash, targetKey, targetGroup, visible, visibleGroups, clearFilters]);

  useEffect(
    () => () => {
      if (highlightTimer.current) clearTimeout(highlightTimer.current);
    },
    []
  );

  // ---- export / import / bulk reset ----
  const onExport = () => {
    const file = buildExport(scoped, effective.values);
    const count = Object.keys(file.overrides).length;
    downloadJson(`vegaduta-settings-overrides-${file.exportedAt.slice(0, 10)}.json`, file);
    editor.setMessage(count ? t("export.done", { count }) : t("export.none"));
  };

  const onApplyImport = async (plan: ImportPlan) => {
    let saved = 0;
    if (plan.changes.length) {
      const keys = await editor.requestCommit(plan.changes.map((c) => ({ key: c.entry.key, value: c.to })));
      saved = keys.length;
      // Cancelled at the reason prompt: stop here, do not remove overrides behind the admin's back.
      if (saved === 0) {
        editor.setMessage(t("import.cancelled"));
        throw new Error("cancelled");
      }
    }
    let resetDone = 0;
    let resetFailed = 0;
    if (plan.resets.length) {
      const r = await editor.resetMany(plan.resets.map((x) => x.entry.key));
      resetDone = r.done;
      // A reset left undone at the reason prompt stays in the diff, like one that failed.
      resetFailed = r.failed + r.cancelled;
    }
    const failed = plan.changes.length - saved + resetFailed;
    editor.setMessage(
      failed
        ? t("import.donePartial", { applied: saved + resetDone, failed })
        : t("import.done", { applied: saved + resetDone })
    );
    if (failed) throw new Error("partial");
  };

  const onConfirmGroupReset = async () => {
    if (!resetTarget) return;
    const keys = resetTarget.keys;
    // Close this confirmation first: a high-risk or security key then opens the reason prompt, and two modal
    // dialogs must not be open on top of each other.
    setResetTarget(null);
    const r = await editor.resetMany(keys);
    editor.setMessage(
      r.failed
        ? t("resetGroup.partial", { done: r.done, failed: r.failed })
        : r.cancelled
          ? t("resetGroup.cancelled", { done: r.done, skipped: r.cancelled })
          : t("resetGroup.done", { count: r.done })
    );
  };

  const loading = catalog.status === "loading" || effective.status === "loading";
  const failedLoad = catalog.status === "error" || effective.status === "error";
  const retry = () => {
    catalog.reload();
    void effective.reload();
  };

  return (
    <div className="space-y-5" data-testid="runtime-settings">
      {!canEdit ? (
        <Alert variant="info" data-testid="runtime-readonly">
          <Info aria-hidden="true" />
          <AlertTitle>{t("readOnly.title")}</AlertTitle>
          <AlertDescription>{t("readOnly.body")}</AlertDescription>
        </Alert>
      ) : null}

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList aria-label={t("tabs.label")} className="sm:w-fit">
          <TabsTrigger value="settings">{t("tabs.settings")}</TabsTrigger>
          <TabsTrigger value="history">{t("tabs.history")}</TabsTrigger>
        </TabsList>

        <TabsContent value="settings" className="space-y-5">
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
          ) : failedLoad ? (
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
              <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
                <p className="text-sm text-muted-foreground" data-testid="runtime-summary">
                  {t("summary", { changed: changedCount, total: scoped.length })}
                </p>
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="outline" size="sm" onClick={onExport} data-testid="export-button">
                    <Download aria-hidden="true" />
                    {t("export.button")}
                  </Button>
                  {canEdit ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setImportOpen(true)}
                      data-testid="import-button"
                    >
                      <Upload aria-hidden="true" />
                      {t("import.button")}
                    </Button>
                  ) : null}
                </div>
              </div>

              {scoped.length > 0 ? (
                <div className="space-y-3">
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
                    <div className="sm:w-52">
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
                    <div className="flex items-center gap-2">
                      <Checkbox
                        id="runtime-changed-only"
                        checked={changedOnly}
                        onCheckedChange={setChangedOnly}
                        data-testid="changed-only"
                      />
                      <Label htmlFor="runtime-changed-only">{t("filters.changedOnly")}</Label>
                    </div>
                    {filtered ? (
                      <Button type="button" variant="ghost" size="sm" onClick={clearFilters}>
                        {ui("filters.clear")}
                      </Button>
                    ) : null}
                  </div>

                  {groupIds.length > 1 ? (
                    <nav aria-label={t("jump.label")} className="flex flex-wrap gap-1.5" data-testid="group-jump">
                      {groupIds.map((g) => (
                        <a
                          key={g}
                          href={`#group-${g}`}
                          className="rounded-full border border-border px-2.5 py-1 text-xs hover:bg-muted focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring"
                        >
                          {groupLabel(g)}
                        </a>
                      ))}
                    </nav>
                  ) : null}
                </div>
              ) : null}

              {unknownAnchor ? (
                <Alert variant="info" data-testid="anchor-unknown">
                  <AlertDescription>{t("anchor.unknown", { key: hash })}</AlertDescription>
                </Alert>
              ) : null}

              <p role="status" aria-live="polite" className="min-h-5 text-sm" data-testid="settings-message">
                {editor.message}
              </p>

              {scoped.length === 0 ? (
                <p className="text-sm text-muted-foreground" data-testid="settings-empty">
                  {ui("empty.none")}
                </p>
              ) : visible.length === 0 ? (
                <p className="text-sm text-muted-foreground" data-testid="settings-empty">
                  {changedOnly && search.trim() === "" && groupFilter === ALL
                    ? t("empty.noneChanged")
                    : ui("empty.filtered")}
                </p>
              ) : (
                <div className="space-y-8">
                  {visibleGroups.map((g) => {
                    const groupEntries = visible.filter((e) => e.group === g);
                    const secure = isSecurityGroup(scoped.filter((e) => e.group === g));
                    const overriddenKeys = groupEntries
                      .filter((e) => isOverridden(e.key) && !effective.values[e.key]?.locked)
                      .map((e) => e.key);
                    return (
                      <div
                        key={g}
                        id={`group-${g}`}
                        tabIndex={-1}
                        data-testid={`runtime-section-${g}`}
                        className={
                          secure
                            ? "scroll-mt-24 space-y-3 rounded-xl border border-destructive/40 bg-destructive/5 p-3 outline-none sm:p-4"
                            : "scroll-mt-24 space-y-3 outline-none"
                        }
                      >
                        {secure ? (
                          <Alert variant="info" data-testid={`security-note-${g}`}>
                            <ShieldAlert aria-hidden="true" />
                            <AlertTitle>{t("security.title")}</AlertTitle>
                            <AlertDescription>{t("security.body")}</AlertDescription>
                          </Alert>
                        ) : null}
                        {canEdit && overriddenKeys.length > 0 ? (
                          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                            <span>{t("resetGroup.count", { count: overriddenKeys.length })}</span>
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              onClick={() => setResetTarget({ group: g, keys: overriddenKeys })}
                              data-testid={`reset-group-${g}`}
                            >
                              {t("resetGroup.button", { group: groupLabel(g) })}
                            </Button>
                          </div>
                        ) : null}
                        <SettingsGroup
                          group={g}
                          entries={groupEntries}
                          scope="tenant"
                          values={effective.values}
                          inherited={effective.inherited}
                          drafts={editor.drafts}
                          errors={editor.writer.errors}
                          pending={editor.writer.pending}
                          role={viewerRole}
                          onDraftChange={editor.onDraftChange}
                          onSave={editor.onSave}
                          onDiscard={editor.onDiscard}
                          onReset={editor.onReset}
                          onOpenHistory={setHistoryKey}
                        />
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </TabsContent>

        <TabsContent value="history">
          <ChangeHistoryTab client={client} entries={catalog.entries} canView={canEdit} />
        </TabsContent>
      </Tabs>

      <RiskyChangeDialog
        open={editor.riskyDialog.open}
        changes={editor.riskyDialog.risky}
        onConfirm={editor.confirmRisky}
        onCancel={editor.cancelRisky}
      />
      <ImportOverridesDialog
        open={importOpen}
        onOpenChange={setImportOpen}
        entries={scoped}
        values={effective.values}
        onApply={onApplyImport}
      />
      <ConfirmDialog
        open={resetTarget != null}
        onOpenChange={(o) => {
          if (!o) setResetTarget(null);
        }}
        title={t("resetGroup.confirmTitle", { group: resetTarget ? groupLabel(resetTarget.group) : "" })}
        description={t("resetGroup.confirmBody", { count: resetTarget?.keys.length ?? 0 })}
        confirmLabel={t("resetGroup.confirm")}
        cancelLabel={t("resetGroup.cancel")}
        destructive
        onConfirm={onConfirmGroupReset}
      />
      <SettingHistoryDrawer
        entry={historyKey ? (scoped.find((e) => e.key === historyKey) ?? null) : null}
        scope="tenant"
        client={client}
        onClose={() => setHistoryKey(null)}
      />
    </div>
  );
}

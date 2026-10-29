"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import { FlaskConical, Trash2, Plus, ChevronDown } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Alert, AlertDescription } from "../ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../PaginationControls";
import { SwitchToggle } from "./common";
import { useConfirm } from "../admin/ConfirmDialog";
import { useTranslations } from "../../../lib/i18n";

// A/B Testing & Feature Flags - Master Roadmap 2026 item #13 (§B). Lifted out
// of the old Ecosystem Maintenance monolith into its own component so it can be
// composed into Tenants & Users (§3.9 "Plans, roster, feature flags").
// FeatureFlagService (core/tenancy) buckets each tenant deterministically by
// SHA-256(flagKey:tenantId) mod 100 against rolloutPercentage - "treatment" vs
// "control", sticky across restarts. This panel is pure admin config: create/
// edit/delete flags, drill into "who got which variant" per flag.

function FlagAssignmentsList({ assignments, loading }: { assignments: any[]; loading: boolean }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsFeatureflags");
  const [query, setQuery] = useState("");

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return assignments;
    return assignments.filter((a) => a.tenantId?.toLowerCase().includes(q));
  }, [assignments, query]);

  const { page, pageSize, pageItems, totalPages, totalItems, setPage, setPageSize } = usePagination(filtered);

  if (loading) return <p className="text-xs text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsFeatureflags.s14")}</p>;
  if (assignments.length === 0)
    return <p className="text-xs text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsFeatureflags.s13")}</p>;

  return (
    <div className="space-y-2">
      {assignments.length > 10 && (
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={tHC("hcPlatformadminComponentsSectionsFeatureflags.s12")}
          className="h-8 max-w-xs text-xs"
        />
      )}
      {pageItems.length === 0 ? (
        <p className="text-xs text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsFeatureflags.s11")}{query}&quot;.</p>
      ) : (
        pageItems.map((a) => (
          <div key={a.tenantId} className="flex flex-wrap items-center justify-between gap-2 font-mono text-xs">
            <span className="text-foreground">{a.tenantId}</span>
            <span className={a.variant === "treatment" ? "text-[var(--state-success)]" : "text-muted-foreground"}>
              {a.variant}
            </span>
            <span className="text-muted-foreground">{new Date(a.assignedAt).toLocaleString()}</span>
          </div>
        ))
      )}
      <PaginationControls
        page={page}
        totalPages={totalPages}
        totalItems={totalItems}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
        itemLabel="assignments"
      />
    </div>
  );
}

export function FeatureFlagsSection() {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsFeatureflags");
  const askConfirm = useConfirm();
  const [featureFlags, setFeatureFlags] = useState<any[]>([]);
  const [featureFlagsError, setFeatureFlagsError] = useState<any>(null);
  const [flagDrafts, setFlagDrafts] = useState<Record<string, any>>({});
  const [savingFlagKey, setSavingFlagKey] = useState<string | null>(null);
  const [deletingFlagKey, setDeletingFlagKey] = useState<string | null>(null);
  const [rolloutErrors, setRolloutErrors] = useState<Record<string, string>>({});
  const [newFlagDraft, setNewFlagDraft] = useState<{
    key: string;
    description: string;
    enabled: boolean;
    rolloutPercentage: number | string;
  }>({
    key: "",
    description: "",
    enabled: false,
    rolloutPercentage: 0,
  });
  const [creatingFlag, setCreatingFlag] = useState(false);
  const [expandedFlagKey, setExpandedFlagKey] = useState<string | null>(null);
  const [flagAssignments, setFlagAssignments] = useState<Record<string, any[]>>({});
  const [loadingAssignmentsKey, setLoadingAssignmentsKey] = useState<string | null>(null);
  const [flagsQuery, setFlagsQuery] = useState("");

  const refresh = useCallback(async () => {
    try {
      const flags = await api.getFeatureFlags();
      setFeatureFlags(flags);
      setFeatureFlagsError(null);
      setFlagDrafts((prev) => {
        const next = { ...prev };
        for (const row of flags) {
          next[row.key] = {
            description: row.description || "",
            enabled: row.enabled,
            rolloutPercentage: row.rolloutPercentage,
          };
        }
        return next;
      });
    } catch (err: any) {
      setFeatureFlags([]);
      setFeatureFlagsError(err.message);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Feature flag refresh failed", e));
  }, [refresh]);

  function updateFlagDraft(key: string, patch: any) {
    setFlagDrafts((prev) => ({ ...prev, [key]: { ...prev[key], ...patch } }));
  }

  async function handleSaveFeatureFlag(key: string) {
    const draft = flagDrafts[key] || {};
    const rollout = Number(draft.rolloutPercentage);
    if (Number.isNaN(rollout) || rollout < 0 || rollout > 100) {
      setRolloutErrors((prev) => ({ ...prev, [key]: "Must be between 0 and 100." }));
      document.getElementById(`flag-rollout-${key}`)?.focus();
      toast.error("Rollout % must be between 0 and 100.");
      return;
    }
    setRolloutErrors((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
    setSavingFlagKey(key);
    try {
      const updated = await api.upsertFeatureFlag(key, {
        description: draft.description || "",
        enabled: !!draft.enabled,
        rolloutPercentage: rollout,
      });
      setFeatureFlags((prev) => prev.map((row) => (row.key === key ? updated : row)));
      toast.success(`${key} updated.`);
    } catch (err: any) {
      toast.error(err.message || `Could not update ${key}.`);
    } finally {
      setSavingFlagKey(null);
    }
  }

  async function handleCreateFeatureFlag(e: any) {
    e.preventDefault();
    const key = newFlagDraft.key.trim();
    if (!key) {
      toast.error("Flag key can't be blank.");
      return;
    }
    if (featureFlags.some((row) => row.key === key)) {
      toast.error(`A flag named "${key}" already exists.`);
      return;
    }
    const rollout = Number(newFlagDraft.rolloutPercentage);
    if (Number.isNaN(rollout) || rollout < 0 || rollout > 100) {
      toast.error("Rollout % must be between 0 and 100.");
      return;
    }
    setCreatingFlag(true);
    try {
      const created = await api.upsertFeatureFlag(key, {
        description: newFlagDraft.description || "",
        enabled: !!newFlagDraft.enabled,
        rolloutPercentage: rollout,
      });
      setFeatureFlags((prev) => [...prev, created].sort((a, b) => a.key.localeCompare(b.key)));
      setFlagDrafts((prev) => ({
        ...prev,
        [key]: {
          description: created.description || "",
          enabled: created.enabled,
          rolloutPercentage: created.rolloutPercentage,
        },
      }));
      setNewFlagDraft({ key: "", description: "", enabled: false, rolloutPercentage: 0 });
      toast.success(`${key} created.`);
    } catch (err: any) {
      toast.error(err.message || "Could not create flag.");
    } finally {
      setCreatingFlag(false);
    }
  }

  async function handleDeleteFeatureFlag(key: string) {
    if (!(await askConfirm(`Delete feature flag "${key}"? This cannot be undone.`))) return;
    setDeletingFlagKey(key);
    try {
      await api.deleteFeatureFlag(key);
      setFeatureFlags((prev) => prev.filter((row) => row.key !== key));
      setFlagDrafts((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
      if (expandedFlagKey === key) setExpandedFlagKey(null);
      toast.success(`${key} deleted.`);
    } catch (err: any) {
      toast.error(err.message || `Could not delete ${key}.`);
    } finally {
      setDeletingFlagKey(null);
    }
  }

  async function handleToggleAssignments(key: string) {
    if (expandedFlagKey === key) {
      setExpandedFlagKey(null);
      return;
    }
    setExpandedFlagKey(key);
    if (flagAssignments[key]) return;
    setLoadingAssignmentsKey(key);
    try {
      const list = await api.getFeatureFlagAssignments(key);
      setFlagAssignments((prev) => ({ ...prev, [key]: list }));
    } catch (err: any) {
      toast.error(err.message || `Could not load assignments for ${key}.`);
    } finally {
      setLoadingAssignmentsKey(null);
    }
  }

  const filteredFlags = useMemo(() => {
    const q = flagsQuery.trim().toLowerCase();
    if (!q) return featureFlags;
    return featureFlags.filter(
      (row) => row.key?.toLowerCase().includes(q) || row.description?.toLowerCase().includes(q)
    );
  }, [featureFlags, flagsQuery]);
  const flagsPagination = usePagination(filteredFlags);

  return (
    <motion.div
      className="mb-5"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay: 0.06, ease: [0.16, 1, 0.3, 1] }}
    >
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            <FlaskConical size={16} className="text-primary" /> {tHC("hcPlatformadminComponentsSectionsFeatureflags.s10")}
          </CardTitle>
          <p className="text-xs text-muted-foreground">
            {tHC("hcPlatformadminComponentsSectionsFeatureflags.s9")}
          </p>
        </CardHeader>
        <CardContent className="space-y-2">
          {featureFlagsError && (
            <Alert variant="destructive">
              <AlertDescription>{featureFlagsError}</AlertDescription>
            </Alert>
          )}
          {featureFlags.length === 0 && !featureFlagsError && (
            <p className="text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsFeatureflags.s8")}</p>
          )}

          {featureFlags.length > 10 && (
            <Input
              value={flagsQuery}
              onChange={(e) => setFlagsQuery(e.target.value)}
              placeholder={tHC("hcPlatformadminComponentsSectionsFeatureflags.s7")}
              className="max-w-xs"
            />
          )}

          <AnimatePresence initial={false}>
            {flagsPagination.pageItems.map((row) => {
              const draft = flagDrafts[row.key] || {
                description: row.description || "",
                enabled: row.enabled,
                rolloutPercentage: row.rolloutPercentage,
              };
              const dirty =
                draft.description !== (row.description || "") ||
                draft.enabled !== row.enabled ||
                Number(draft.rolloutPercentage) !== row.rolloutPercentage;
              const isExpanded = expandedFlagKey === row.key;
              const assignments = flagAssignments[row.key] || [];
              return (
                <motion.div
                  key={row.key}
                  className="rounded-lg border border-border p-4"
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.25 }}
                >
                  <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
                    <div className="font-mono text-sm font-semibold text-foreground">{row.key}</div>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleDeleteFeatureFlag(row.key)}
                      disabled={deletingFlagKey === row.key}
                      title={tHC("hcPlatformadminComponentsSectionsFeatureflags.s6")}
                    >
                      <Trash2 size={13} />
                      {deletingFlagKey === row.key ? "Deleting…" : "Delete"}
                    </Button>
                  </div>

                  <div className="max-w-md space-y-1.5">
                    <Label htmlFor={`flag-desc-${row.key}`} className="text-xs text-muted-foreground">
                      Description
                    </Label>
                    <Input
                      id={`flag-desc-${row.key}`}
                      value={draft.description}
                      onChange={(e) => updateFlagDraft(row.key, { description: e.target.value })}
                    />
                  </div>

                  <div className="mt-3 flex flex-wrap items-end gap-3">
                    <div className="flex items-center gap-2">
                      <SwitchToggle
                        on={draft.enabled}
                        label={`Enable ${row.key}`}
                        onClick={() => updateFlagDraft(row.key, { enabled: !draft.enabled })}
                      />
                      <span className="text-xs text-muted-foreground">Enabled</span>
                    </div>
                    <div className="w-28 space-y-1.5">
                      <Label htmlFor={`flag-rollout-${row.key}`} className="text-xs text-muted-foreground">
                        {tHC("hcPlatformadminComponentsSectionsFeatureflags.s1")}
                      </Label>
                      <Input
                        id={`flag-rollout-${row.key}`}
                        type="number"
                        min={0}
                        max={100}
                        value={draft.rolloutPercentage}
                        onChange={(e) => updateFlagDraft(row.key, { rolloutPercentage: e.target.value })}
                        aria-invalid={!!rolloutErrors[row.key]}
                      />
                      {rolloutErrors[row.key] && <p className="text-xs text-destructive">{rolloutErrors[row.key]}</p>}
                    </div>
                    <Button
                      size="sm"
                      disabled={savingFlagKey === row.key || !dirty}
                      onClick={() => handleSaveFeatureFlag(row.key)}
                    >
                      {savingFlagKey === row.key ? "Saving…" : "Save"}
                    </Button>
                    <Button variant="outline" size="sm" onClick={() => handleToggleAssignments(row.key)}>
                      <ChevronDown
                        size={13}
                        className={isExpanded ? "rotate-180 transition-transform" : "transition-transform"}
                      />
                      Assignments
                    </Button>
                  </div>

                  {row.updatedAt && (
                    <div className="mt-2 font-mono text-xs text-muted-foreground">
                      {tHC("hcPlatformadminComponentsSectionsFeatureflags.s5")} {new Date(row.updatedAt).toLocaleString()}
                      {row.updatedBy ? ` by ${row.updatedBy}` : ""}
                    </div>
                  )}

                  <AnimatePresence initial={false}>
                    {isExpanded && (
                      <motion.div
                        initial={{ opacity: 0, height: 0 }}
                        animate={{ opacity: 1, height: "auto" }}
                        exit={{ opacity: 0, height: 0 }}
                        transition={{ duration: 0.2 }}
                        className="mt-3 border-t border-border pt-3"
                      >
                        <FlagAssignmentsList assignments={assignments} loading={loadingAssignmentsKey === row.key} />
                      </motion.div>
                    )}
                  </AnimatePresence>
                </motion.div>
              );
            })}
          </AnimatePresence>

          <PaginationControls
            page={flagsPagination.page}
            totalPages={flagsPagination.totalPages}
            totalItems={flagsPagination.totalItems}
            pageSize={flagsPagination.pageSize}
            onPageChange={flagsPagination.setPage}
            onPageSizeChange={flagsPagination.setPageSize}
            itemLabel="flags"
          />

          <form
            onSubmit={handleCreateFeatureFlag}
            className="flex flex-wrap items-end gap-3 rounded-lg border border-border p-4"
          >
            <div className="w-48 space-y-1.5">
              <Label htmlFor="flag-new-key" className="text-xs text-muted-foreground">
                {tHC("hcPlatformadminComponentsSectionsFeatureflags.s4")}
              </Label>
              <Input
                id="flag-new-key"
                placeholder={tHC("hcPlatformadminComponentsSectionsFeatureflags.s3")}
                value={newFlagDraft.key}
                onChange={(e) => setNewFlagDraft((prev) => ({ ...prev, key: e.target.value }))}
              />
            </div>
            <div className="w-64 space-y-1.5">
              <Label htmlFor="flag-new-description" className="text-xs text-muted-foreground">
                Description
              </Label>
              <Input
                id="flag-new-description"
                value={newFlagDraft.description}
                onChange={(e) => setNewFlagDraft((prev) => ({ ...prev, description: e.target.value }))}
              />
            </div>
            <div className="flex items-center gap-2">
              <SwitchToggle
                on={newFlagDraft.enabled}
                label={tHC("hcPlatformadminComponentsSectionsFeatureflags.s2")}
                onClick={() => setNewFlagDraft((prev) => ({ ...prev, enabled: !prev.enabled }))}
              />
              <span className="text-xs text-muted-foreground">Enabled</span>
            </div>
            <div className="w-24 space-y-1.5">
              <Label htmlFor="flag-new-rollout" className="text-xs text-muted-foreground">
                {tHC("hcPlatformadminComponentsSectionsFeatureflags.s1")}
              </Label>
              <Input
                id="flag-new-rollout"
                type="number"
                min={0}
                max={100}
                value={newFlagDraft.rolloutPercentage}
                onChange={(e) => setNewFlagDraft((prev) => ({ ...prev, rolloutPercentage: e.target.value }))}
              />
            </div>
            <Button type="submit" size="sm" disabled={creatingFlag}>
              <Plus size={13} />
              {creatingFlag ? "Creating…" : "Create flag"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </motion.div>
  );
}

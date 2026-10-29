"use client";

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  RefreshCw,
  Users,
  Search,
  LockOpen,
  Lock,
  Loader2,
  EyeOff,
  Eye,
  Trash2,
  Building2,
  type LucideIcon,
} from "lucide-react";
import AdminShell from "../components/AdminShell";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Badge } from "../components/ui/badge";
import { Card, CardContent } from "../components/ui/card";
import Select from "../components/Select";
import { usePagination } from "../lib/usePagination";
import PaginationControls from "../components/PaginationControls";
import { auth } from "../lib/auth";
import { useTranslations } from "../../lib/i18n";

// Academy Learners — the admin's learner controls:
//
//   * Unlock full course (per learner): opens the lesson spine for exactly
//     ONE learner, independent of billing/plan - sequential gating bypassed
//     for them only, their completion history untouched, and the action is
//     recorded (Academy emits an admin_course_unlocked telemetry event) so
//     it's always auditable as admin-granted rather than earned.
//   * Unlock tenant / Unlock all users (bulk): the same per-learner unlock
//     applied to every learner in the selected tenant, or every learner on
//     the instance. Unlinked learners (tenantId null) can never be swept
//     into a tenant unlock — the Academy enforces the NULL RULE server-side.
//   * Reset history: wipes one learner's learning history (progress, labs,
//     quiz/challenge/review state, telemetry) and re-seeds them to the
//     day-one shape. The learner and their login survive; it is a reset,
//     not a deletion. Destructive — hence the two-step confirm.
//   * Hide from board / Show on board: keeps a learner off the public
//     leaderboard only. Reversible, one click.
//
// Mirrors the Academy's own admin page (ui/src/pages/AdminAnalytics.tsx's
// LearnersSection) - same two-step inline confirm before unlocking, since
// this bypasses a learner's actual progress.
//
// All data flows through the same-origin server-side proxy at
// /api/academy/** (app/api/academy/**) so the Academy admin key never
// reaches the browser - see app/lib/academyProxy.ts.

const OK_BADGE = "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";

function formatDate(iso: string | null | undefined) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

// Same-origin proxy fetch - the caller's own Keycloak Bearer token is
// forwarded so the route handler can verify it server-side before touching
// the privileged Academy key (see app/lib/serverAuth.ts).
async function proxyJson(path: string, options: any = {}) {
  const token = await auth.getToken();
  const headers: Record<string, string> = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(path, { ...options, headers });
  let body: any = null;
  const text = await res.text();
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  if (!res.ok) {
    const err: any = new Error(body?.error || `Request failed (${res.status}).`);
    err.status = res.status;
    throw err;
  }
  return body;
}

const StatTile = ({
  label,
  value,
  icon: Icon = null,
}: {
  label: ReactNode;
  value: ReactNode;
  icon?: LucideIcon | null;
}) => (
  <Card className="gap-0 py-0">
    <CardContent className="p-4">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 flex items-center gap-2 font-mono text-2xl font-semibold text-foreground">
        {Icon && <Icon size={18} className="text-muted-foreground" />}
        {value}
      </div>
    </CardContent>
  </Card>
);

// GET /platform-admin/api/academy/learners row (see academyProxy.ts) - the
// fields below are the ones this screen reads; the index signature covers
// the rest of the Academy learner payload.
type AcademyLearner = {
  id: string;
  name?: string | null;
  tenantId?: string | null;
  fullyUnlocked?: boolean;
  hiddenFromLeaderboard?: boolean;
  [key: string]: any;
};

export default function AcademyLearnersPage() {
    const tHC = useTranslations("hcPlatformadminAcademylearnersPage");
  const [learners, setLearners] = useState<AcademyLearner[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);
  const [lastChecked, setLastChecked] = useState(null);

  const [search, setSearch] = useState("");
  // "all" | "unlinked" | a real tenant id (from learner.tenantId)
  const [tenantFilter, setTenantFilter] = useState("all");
  const [confirmingId, setConfirmingId] = useState(null);
  const [confirmingResetId, setConfirmingResetId] = useState(null);
  const [confirmingUnlockAll, setConfirmingUnlockAll] = useState(false);
  const [confirmingTenantUnlock, setConfirmingTenantUnlock] = useState(false);
  const [busyId, setBusyId] = useState(null);
  const [busyBulk, setBusyBulk] = useState(null);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      const data = await proxyJson("/platform-admin/api/academy/learners");
      setLearners(Array.isArray(data?.learners) ? data.learners : []);
      setError(null);
    } catch (err) {
      setLearners([]);
      setError((err as Error).message || "Could not load learners.");
      toast.error((err as Error).message || "Could not load learners.");
    }
    setLastChecked(new Date());
    setLoading(false);
    setRefreshing(false);
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Academy learners refresh failed", e));
  }, [refresh]);

  // Distinct tenants present on the loaded learner rows (tenantId +
  // orgName come from the Academy's learner-admin payload; orgName is a
  // denormalised display convenience, never authorised against).
  const tenants = useMemo(() => {
    const map = new Map();
    for (const l of learners) {
      if (!l.tenantId) continue;
      const e = map.get(l.tenantId) ?? { tenantId: l.tenantId, orgName: l.orgName ?? null, count: 0 };
      e.count += 1;
      if (!e.orgName && l.orgName) e.orgName = l.orgName;
      map.set(l.tenantId, e);
    }
    return [...map.values()].sort((a, b) => (a.orgName ?? a.tenantId).localeCompare(b.orgName ?? b.tenantId));
  }, [learners]);

  const tenantOptions = useMemo(
    () => [
      { value: "all", label: "All tenants" },
      ...tenants.map((t) => ({
        value: t.tenantId,
        label: `${t.orgName ?? t.tenantId} (${t.count})`,
      })),
      { value: "unlinked", label: "Unlinked (no tenant)" },
    ],
    [tenants]
  );

  // If the selected tenant disappears from a refreshed payload (e.g. all its
  // learners unlinked), snap the filter back rather than leaving the table
  // empty behind an invisible selection.
  useEffect(() => {
    if (tenantFilter !== "all" && tenantFilter !== "unlinked" && !tenants.some((t) => t.tenantId === tenantFilter)) {
      setTenantFilter("all");
      setConfirmingTenantUnlock(false);
    }
  }, [tenants, tenantFilter]);

  const filtered = useMemo(() => {
    let list = learners;
    if (tenantFilter === "unlinked") list = list.filter((l) => !l.tenantId);
    else if (tenantFilter !== "all") list = list.filter((l) => l.tenantId === tenantFilter);
    const q = search.trim().toLowerCase();
    if (q) list = list.filter((l) => l.name?.toLowerCase().includes(q));
    return list;
  }, [learners, search, tenantFilter]);

  const fullyUnlockedCount = useMemo(() => learners.filter((l) => l.fullyUnlocked).length, [learners]);

  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(filtered);

  async function handleUnlock(learner: AcademyLearner) {
    setBusyId(learner.id);
    try {
      const res = await proxyJson(`/platform-admin/api/academy/learners/${encodeURIComponent(learner.id)}/unlock`, {
        method: "POST",
      });
      toast.success(
        res?.alreadyOpen
          ? `${learner.name}'s course was already fully open.`
          : `${learner.name}: ${res?.opened ?? 0} lesson(s) opened.`
      );
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not unlock this learner's course.");
    } finally {
      setBusyId(null);
      setConfirmingId(null);
    }
  }

  // Opens the course for EVERY learner on the instance — deliberately
  // two-stepped with destructive wording, since one click grants the whole
  // curriculum to every account.
  async function handleUnlockAll() {
    setBusyBulk("all");
    try {
      const res = await proxyJson("/platform-admin/api/academy/learners/unlock-all", { method: "POST" });
      toast.success(
        `Course opened for ${res?.learnersMatched ?? 0} learner(s) — ${res?.totalOpened ?? 0} lesson(s) unlocked.`
      );
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not unlock the course for all learners.");
    } finally {
      setBusyBulk(null);
      setConfirmingUnlockAll(false);
    }
  }

  // Opens the course for every learner linked to the tenant selected in the
  // filter. Disabled for "all"/"unlinked" — the unlinked bucket is exactly
  // the set a tenant-scoped unlock must never reach (the Academy enforces
  // the same NULL RULE server-side; the UI simply doesn't offer it).
  async function handleUnlockTenant() {
    if (tenantFilter === "all" || tenantFilter === "unlinked") return;
    const label = tenants.find((t) => t.tenantId === tenantFilter)?.orgName ?? tenantFilter;
    setBusyBulk("tenant");
    try {
      const res = await proxyJson(`/platform-admin/api/academy/tenants/${encodeURIComponent(tenantFilter)}/unlock`, {
        method: "POST",
      });
      toast.success(
        `${label}: course opened for ${res?.learnersMatched ?? 0} learner(s) — ${res?.totalOpened ?? 0} lesson(s) unlocked.`
      );
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not unlock the course for this tenant.");
    } finally {
      setBusyBulk(null);
      setConfirmingTenantUnlock(false);
    }
  }

  // Destructive: wipes the learner's history and resets them to day one.
  // Their login still works — this is a reset, not a deletion.
  async function handleResetHistory(learner: AcademyLearner) {
    setBusyId(learner.id);
    try {
      const res = await proxyJson(`/platform-admin/api/academy/learners/${encodeURIComponent(learner.id)}/history`, {
        method: "DELETE",
      });
      const removed = res?.removed ?? {};
      const wiped =
        (removed.progress ?? 0) +
        (removed.quizAttempts ?? 0) +
        (removed.labProgress ?? 0) +
        (removed.challengeProgress ?? 0) +
        (removed.reviewItems ?? 0) +
        (removed.telemetryEvents ?? 0);
      toast.success(`${learner.name}: history wiped (${wiped} record(s) removed), course reset to day one.`);
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not reset this learner's history.");
    } finally {
      setBusyId(null);
      setConfirmingResetId(null);
    }
  }

  // Leaderboard visibility is a light-touch moderation toggle — reversible,
  // not destructive — so it stays a one-click action unlike unlock/reset.
  async function handleToggleBoard(learner: AcademyLearner) {
    const hidden = !learner.hiddenFromLeaderboard;
    setBusyId(learner.id);
    try {
      await proxyJson(`/platform-admin/api/academy/learners/${encodeURIComponent(learner.id)}/leaderboard-visibility`, {
        method: "POST",
        body: JSON.stringify({ hidden }),
      });
      toast.success(
        hidden ? `${learner.name} is hidden from the leaderboard.` : `${learner.name} is back on the leaderboard.`
      );
      await refresh();
    } catch (err) {
      toast.error((err as Error).message || "Could not change leaderboard visibility.");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <AdminShell
      title={tHC("hcPlatformadminAcademylearnersPage.s22")}
      actions={
        <Button variant="outline" size="sm" onClick={refresh} disabled={refreshing} title={tHC("hcPlatformadminAcademylearnersPage.s21")}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <motion.p
        className="mb-5 max-w-3xl text-sm leading-relaxed text-muted-foreground"
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
      >
        {tHC("hcPlatformadminAcademylearnersPage.s20")}
        {lastChecked && ` Last checked ${lastChecked.toLocaleTimeString()}.`}
      </motion.p>

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatTile label="Learners" value={learners.length} icon={Users} />
        <StatTile label={tHC("hcPlatformadminAcademylearnersPage.s6")} value={fullyUnlockedCount} icon={LockOpen} />
        <StatTile label="Tenants" value={tenants.length} icon={Building2} />
      </div>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative w-full max-w-xs">
            <Search
              size={14}
              className="pointer-events-none absolute start-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={tHC("hcPlatformadminAcademylearnersPage.s19")}
              className="ps-8"
            />
          </div>
          <div className="w-64">
            <Select
              value={tenantFilter}
              onChange={(e) => {
                setTenantFilter(e.target.value);
                setConfirmingTenantUnlock(false);
              }}
              options={tenantOptions}
              placeholder={tHC("hcPlatformadminAcademylearnersPage.s18")}
            />
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* Per-tenant unlock only makes sense with a real tenant selected —
              "unlinked" is exactly the set a tenant-scoped unlock must never
              reach, so the button hides rather than offering it. */}
          {tenantFilter !== "all" && tenantFilter !== "unlinked" ? (
            confirmingTenantUnlock ? (
              <>
                <Button size="sm" variant="destructive" disabled={busyBulk === "tenant"} onClick={handleUnlockTenant}>
                  {busyBulk === "tenant" ? <Loader2 size={14} className="animate-spin" /> : <LockOpen size={14} />}
                  {tHC("hcPlatformadminAcademylearnersPage.s17")}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busyBulk === "tenant"}
                  onClick={() => setConfirmingTenantUnlock(false)}
                >
                  Cancel
                </Button>
              </>
            ) : (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setConfirmingTenantUnlock(true)}
                title={tHC("hcPlatformadminAcademylearnersPage.s16")}
              >
                <Building2 size={14} /> {tHC("hcPlatformadminAcademylearnersPage.s15")}
              </Button>
            )
          ) : null}

          {confirmingUnlockAll ? (
            <>
              <Button size="sm" variant="destructive" disabled={busyBulk === "all"} onClick={handleUnlockAll}>
                {busyBulk === "all" ? <Loader2 size={14} className="animate-spin" /> : <LockOpen size={14} />}
                {tHC("hcPlatformadminAcademylearnersPage.s14")}
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={busyBulk === "all"}
                onClick={() => setConfirmingUnlockAll(false)}
              >
                Cancel
              </Button>
            </>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => setConfirmingUnlockAll(true)}
              title={tHC("hcPlatformadminAcademylearnersPage.s13")}
            >
              <Users size={14} /> {tHC("hcPlatformadminAcademylearnersPage.s12")}
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {error}
        </div>
      )}

      <Card className="gap-0 overflow-hidden py-0">
        {loading ? (
          <p className="p-5 text-sm text-muted-foreground">{tHC("hcPlatformadminAcademylearnersPage.s11")}</p>
        ) : learners.length === 0 ? (
          <p className="p-5 text-sm text-muted-foreground">{tHC("hcPlatformadminAcademylearnersPage.s10")}</p>
        ) : filtered.length === 0 ? (
          <p className="p-5 text-sm text-muted-foreground">
            {search.trim() ? <>{tHC("hcPlatformadminAcademylearnersPage.s9")}{search}&rdquo;.</> : "No learners in this tenant filter."}
          </p>
        ) : (
          <>
            <AnimatePresence initial={false}>
              {pageItems.map((learner) => (
                <motion.div
                  key={learner.id}
                  className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-4 last:border-0"
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0 }}
                >
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <strong className="text-sm text-foreground">{learner.name}</strong>
                      {learner.certificationTier && learner.certificationTier !== "none" && (
                        <Badge variant="outline" className="text-[10px]">
                          {learner.certificationTier}
                        </Badge>
                      )}
                      {learner.orgName ? (
                        <Badge variant="outline" className="gap-1 text-[10px]">
                          <Building2 size={10} /> {learner.orgName}
                        </Badge>
                      ) : null}
                      {learner.hiddenFromLeaderboard ? (
                        <Badge variant="outline" className="gap-1 text-[10px]">
                          <EyeOff size={10} /> {tHC("hcPlatformadminAcademylearnersPage.s8")}
                        </Badge>
                      ) : null}
                    </div>
                    <p className="mt-1 font-mono text-xs text-muted-foreground">
                      {learner.completedLessons}/{learner.totalLessons} {tHC("hcPlatformadminAcademylearnersPage.s7")} {learner.labsCompleted} labs ·{" "}
                      {learner.xpTotal} XP
                      {learner.lastActiveDate ? ` · active ${formatDate(learner.lastActiveDate)}` : ""}
                    </p>
                  </div>

                  <div className="flex flex-wrap items-center gap-2">
                    {learner.fullyUnlocked ? (
                      <Badge variant="outline" className={`${OK_BADGE} gap-1`}>
                        <LockOpen size={12} /> {tHC("hcPlatformadminAcademylearnersPage.s6")}
                      </Badge>
                    ) : confirmingId === learner.id ? (
                      <>
                        <Button size="sm" disabled={busyId === learner.id} onClick={() => handleUnlock(learner)}>
                          {busyId === learner.id ? (
                            <Loader2 size={14} className="animate-spin" />
                          ) : (
                            <LockOpen size={14} />
                          )}
                          {tHC("hcPlatformadminAcademylearnersPage.s5")}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busyId === learner.id}
                          onClick={() => setConfirmingId(null)}
                        >
                          Cancel
                        </Button>
                      </>
                    ) : (
                      <Button size="sm" variant="outline" onClick={() => setConfirmingId(learner.id)}>
                        <Lock size={14} /> {tHC("hcPlatformadminAcademylearnersPage.s4")}
                      </Button>
                    )}

                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busyId === learner.id}
                      onClick={() => handleToggleBoard(learner)}
                      title={
                        learner.hiddenFromLeaderboard
                          ? "Show this learner on the public leaderboard"
                          : "Keep this learner off the public leaderboard"
                      }
                    >
                      {learner.hiddenFromLeaderboard ? <Eye size={14} /> : <EyeOff size={14} />}
                      {learner.hiddenFromLeaderboard ? "Show on board" : "Hide from board"}
                    </Button>

                    {confirmingResetId === learner.id ? (
                      <>
                        <Button
                          size="sm"
                          variant="destructive"
                          disabled={busyId === learner.id}
                          onClick={() => handleResetHistory(learner)}
                        >
                          {busyId === learner.id ? (
                            <Loader2 size={14} className="animate-spin" />
                          ) : (
                            <Trash2 size={14} />
                          )}
                          {tHC("hcPlatformadminAcademylearnersPage.s3")}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busyId === learner.id}
                          onClick={() => setConfirmingResetId(null)}
                        >
                          Cancel
                        </Button>
                      </>
                    ) : (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busyId === learner.id}
                        onClick={() => setConfirmingResetId(learner.id)}
                        title={tHC("hcPlatformadminAcademylearnersPage.s2")}
                      >
                        <Trash2 size={14} /> {tHC("hcPlatformadminAcademylearnersPage.s1")}
                      </Button>
                    )}
                  </div>
                </motion.div>
              ))}
            </AnimatePresence>
            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel="learners"
            />
          </>
        )}
      </Card>
    </AdminShell>
  );
}

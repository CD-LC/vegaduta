// @ts-nocheck
"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import {
  Boxes,
  RefreshCw,
  Plus,
  Trash2,
  Loader2,
  Clock,
} from "lucide-react";

import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";

import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "../components/ui/card";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../components/ui/alert-dialog";
import { useTranslations } from "../lib/i18n";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { codeIntel } from "./lib";
import { CODE_CLS, HIT_CLS, INFO_CLS, HINT_CLS, Toggle, Field } from "./shared";
/* ------------------------------------------------------------------ */
/* Indexing progress tracking                                          */
//
// Why this exists: indexing a repo (clone/pull → chunk → embed → build the
// code graph) can take minutes with no natural progress signal, and the old UI
// held that "indexing" flag only in local component state. So a page reload or
// simply revisiting the tab re-enabled the button while the job was still
// running server-side — which is exactly how customers ended up firing several
// overlapping index requests (the server's own lock rejected the extras, but
// with a confusing error and zero feedback).
//
// The fix, entirely client-side (no service change needed — the endpoints were
// already there):
//   • kick the index off in BACKGROUND mode so the POST returns immediately;
//   • persist an in-flight marker in localStorage keyed by repo, so the
//     "Indexing…" state + progress bar survive reloads and show in every tab;
//   • poll the repo's /status and reconcile against last_index.finished_at to
//     detect real completion — even if the tab that started it is long gone;
//   • render an honest, trickling progress bar + elapsed timer so the customer
//     can SEE it working and never feels the urge to click again.
const INDEX_LS_KEY = "codeintel:indexing:v1";
const INDEX_POLL_MS = 2500;
const INDEX_STALE_MS = 30 * 60 * 1000; // stop tracking a marker after 30 min
const INDEX_TRICKLE_EST_MS = 45000; // shapes the indeterminate progress curve

export function loadIndexMarkers() {
  try {
    return JSON.parse(localStorage.getItem(INDEX_LS_KEY) || "{}") || {};
  } catch {
    return {};
  }
}
export function persistIndexMarkers(markers) {
  try {
    localStorage.setItem(INDEX_LS_KEY, JSON.stringify(markers));
  } catch {
    /* private mode / quota — the in-memory state still works this session */
  }
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function formatElapsed(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

// Honest indeterminate progress: eases toward — but never reaches — 100% while
// the index runs, so it never lies about being done; the graph phase parks at a
// high fixed value since indexing already finished. It only "completes" when
// the row disappears on real completion.
function tricklePct(elapsedMs, phase) {
  if (phase === "graph") return 95;
  return Math.min(90, 100 * (1 - Math.exp(-elapsedMs / INDEX_TRICKLE_EST_MS)));
}

export default function ReposTab({ repos, repoId, setRepoId, refresh, onError }) {
  const t = useTranslations("codeIntel");
  const [form, setForm] = useState({
    repo_id: "",
    source_type: "git",
    git_url: "",
    path: "",
    auth_env: "",
  });
  const [busy, setBusy] = useState(false);
  const [pendingDeleteRepo, setPendingDeleteRepo] = useState(null);
  const [deletingRepo, setDeletingRepo] = useState(false);

  // In-flight index jobs, keyed by repo_id → { startedAt, prevIndexFinishedAt,
  // phase }. Seeded from localStorage so a reload / new tab immediately shows
  // the running state instead of an idle, re-clickable button.
  const [markers, setMarkers] = useState(loadIndexMarkers);
  const driversRef = useRef({}); // repo_id → true while its poll loop is active
  const [now, setNow] = useState(() => Date.now());
  const reposPagination = usePagination(repos);

  // A single 500ms ticker, alive only while something is indexing, drives the
  // elapsed timers + progress-bar animation for every in-flight repo.
  const anyInflight = Object.keys(markers).length > 0;
  useEffect(() => {
    if (!anyInflight) return undefined;
    const id = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(id);
  }, [anyInflight]);

  const putMarker = useCallback((id, m) => {
    setMarkers((prev) => {
      const next = { ...prev, [id]: m };
      persistIndexMarkers(next);
      return next;
    });
  }, []);
  const patchMarker = useCallback((id, patch) => {
    setMarkers((prev) => {
      if (!prev[id]) return prev;
      const next = { ...prev, [id]: { ...prev[id], ...patch } };
      persistIndexMarkers(next);
      return next;
    });
  }, []);
  const dropMarker = useCallback((id) => {
    setMarkers((prev) => {
      if (!prev[id]) return prev;
      const next = { ...prev };
      delete next[id];
      persistIndexMarkers(next);
      return next;
    });
  }, []);

  // The driver: poll /status until the index genuinely completes (a NEW
  // last_index.finished_at), then rebuild the graph if it's now stale, then
  // drop the marker. Self-guarded so at most one loop runs per repo, and safe
  // to (re)invoke from both a fresh click and reload reconciliation.
  const drive = useCallback(
    async (id, marker) => {
      if (driversRef.current[id]) return;
      driversRef.current[id] = true;
      try {
        const deadline = marker.startedAt + INDEX_STALE_MS;
        let indexResult = null;
        while (Date.now() < deadline) {
          let status = null;
          try {
            status = await codeIntel.repoStatus(id);
          } catch (e) {
            if (e?.status === 404) throw e; // repo deleted — stop tracking
            // transient (service restart / network blip) — keep polling
          }
          const li = status?.last_index;
          if (li?.finished_at && (!marker.prevIndexFinishedAt || li.finished_at > marker.prevIndexFinishedAt)) {
            indexResult = li;
            break;
          }
          await sleep(INDEX_POLL_MS);
        }
        if (!indexResult) throw new Error("Indexing is taking unusually long — check that the code-intel service is running, then try again.");
        if (indexResult.status === "error") throw new Error(indexResult.error || "Indexing failed.");
        // Rebuild the graph only if it's older than the index we just finished.
        patchMarker(id, { phase: "graph" });
        let status2 = null;
        try {
          status2 = await codeIntel.repoStatus(id);
        } catch {
          /* ignore — fall through to a rebuild */
        }
        const lg = status2?.last_graph;
        if (!lg?.finished_at || lg.finished_at < indexResult.finished_at) {
          await codeIntel.graphBuild(id);
        }
      } catch (e) {
        onError(e.message);
      } finally {
        driversRef.current[id] = false;
        dropMarker(id);
        refresh();
      }
    },
    [patchMarker, dropMarker, onError, refresh]
  );

  // Fresh click: record the marker, start the background index, then hand off
  // to the poller. A second click is a no-op (the marker already exists), and
  // even a "repo is already being indexed" race just adopts the running job
  // instead of surfacing a scary error.
  const index = useCallback(
    async (id) => {
      if (markers[id]) return;
      const repo = repos.find((r) => r.repo_id === id);
      const marker = { startedAt: Date.now(), prevIndexFinishedAt: repo?.last_index?.finished_at ?? null, phase: "indexing" };
      putMarker(id, marker);
      try {
        await codeIntel.indexRepo(id, false, true); // background → returns immediately
      } catch (e) {
        const alreadyRunning = e?.status === 409 || /already being indexed|busy/i.test(e?.message || "");
        if (!alreadyRunning) {
          onError(e.message);
          dropMarker(id);
          return;
        }
      }
      void drive(id, marker);
    },
    [markers, repos, putMarker, dropMarker, onError, drive]
  );

  // Reconcile persisted markers whenever the repo list resolves: drop stale
  // ones (>30 min) or ones for repos this tenant no longer has, and resume the
  // poller for anything still legitimately in flight (e.g. started before a
  // reload). drive() self-guards, so this is safe to run repeatedly.
  useEffect(() => {
    const current = loadIndexMarkers();
    const ids = Object.keys(current);
    if (ids.length === 0) return;
    const known = new Set(repos.map((r) => r.repo_id));
    for (const id of ids) {
      const m = current[id];
      const stale = Date.now() - (m?.startedAt || 0) > INDEX_STALE_MS;
      const unknown = repos.length > 0 && !known.has(id); // only trust "unknown" once the list has loaded
      if (stale || unknown) {
        dropMarker(id);
        continue;
      }
      void drive(id, m);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repos]);

  const register = async () => {
    setBusy(true);
    try {
      const payload = { repo_id: form.repo_id, source_type: form.source_type };
      if (form.source_type === "git") {
        payload.git_url = form.git_url;
        if (form.auth_env) payload.auth_env = form.auth_env;
      } else {
        payload.path = form.path;
      }
      await codeIntel.registerRepo(payload);
      setForm({ repo_id: "", source_type: "git", git_url: "", path: "", auth_env: "" });
      refresh();
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy(false);
    }
  };

  async function confirmDeleteRepo() {
    if (!pendingDeleteRepo) return;
    setDeletingRepo(true);
    try {
      await codeIntel.deleteRepo(pendingDeleteRepo.repo_id);
      setPendingDeleteRepo(null);
      refresh();
    } catch (e) {
      onError(e.message);
    } finally {
      setDeletingRepo(false);
    }
  }

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            <Plus size={16} className="text-primary" /> {t("registerRepoHeading")}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <Field label="Repo id">
            <Input
              value={form.repo_id}
              placeholder="billing-api"
              onChange={(e) => setForm({ ...form, repo_id: e.target.value })}
            />
          </Field>
          <Field label={t("sourceLabel")}>
            <Select
              value={form.source_type}
              onChange={(e) => setForm({ ...form, source_type: e.target.value })}
              options={[
                { value: "git", label: t("sourceOptionGit") },
                { value: "local", label: t("sourceOptionLocal") },
              ]}
            />
          </Field>
          {form.source_type === "git" ? (
            <>
              <Field label="Git URL">
                <Input
                  value={form.git_url}
                  placeholder="https://github.com/acme/billing-api.git"
                  onChange={(e) => setForm({ ...form, git_url: e.target.value })}
                />
              </Field>
              <Field label="Auth env var (optional)">
                <Input
                  value={form.auth_env}
                  placeholder="GITHUB_TOKEN_ACME"
                  onChange={(e) => setForm({ ...form, auth_env: e.target.value })}
                />
              </Field>
            </>
          ) : (
            <Field label="Path">
              <Input
                value={form.path}
                placeholder="/data/mounts/billing-api"
                onChange={(e) => setForm({ ...form, path: e.target.value })}
              />
            </Field>
          )}
          <Button size="sm" disabled={busy || !form.repo_id} onClick={register}>
            {busy ? <Loader2 size={15} className="animate-spin" /> : <Plus size={15} />} {t("registerButton")}
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            <Boxes size={16} className="text-primary" /> {t("registeredHeading", { count: repos.length })}
            <Button variant="ghost" size="sm" className="ml-auto" onClick={refresh}>
              <RefreshCw size={13} /> {t("refreshButton")}
            </Button>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {repos.length === 0 && <p className="text-sm text-muted-foreground">{t("noReposText")}</p>}
          {reposPagination.pageItems.map((r) => {
            const active = repoId === r.repo_id;
            const marker = markers[r.repo_id];
            const indexing = !!marker;
            const elapsed = now - (marker?.startedAt || now);
            const phaseLabel = marker?.phase === "graph" ? "Building code graph…" : "Indexing repository…";
            const pct = marker ? tricklePct(elapsed, marker.phase) : 0;
            return (
              <div
                key={r.repo_id}
                className={`rounded-lg border p-3 transition-colors ${
                  active ? "border-primary bg-primary/5" : "border-border hover:bg-accent/50"
                }`}
              >
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    className="min-w-0 flex-1 text-left"
                    onClick={() => setRepoId(r.repo_id)}
                  >
                    <div className="truncate text-sm font-semibold text-foreground">{r.repo_id}</div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      {r.source_type}
                      {r.last_index?.stats ? ` · indexed: ${r.last_index.stats.files_changed ?? 0} changed` : " · not indexed"}
                      {r.last_graph?.stats ? ` · graph: ${r.last_graph.stats.symbols ?? 0} symbols` : ""}
                    </div>
                  </button>
                  <Button variant="outline" size="sm" disabled={indexing} onClick={() => index(r.repo_id)}>
                    {indexing ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />}
                    {indexing ? t("indexingButton") : t("indexGraphButton")}
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-8 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                    disabled={indexing}
                    onClick={() => setPendingDeleteRepo(r)}
                    aria-label="Delete repository"
                  >
                    <Trash2 size={13} />
                  </Button>
                </div>

                {/* Live progress — survives reloads, so the customer sees it's
                    working and doesn't re-click. Honest indeterminate bar +
                    elapsed timer + a plain "no need to click again" note. */}
                {indexing && (
                  <div className="mt-3 space-y-1.5">
                    <div className="flex items-center justify-between text-xs">
                      <span className="flex items-center gap-1.5 font-medium text-foreground">
                        <Loader2 size={12} className="animate-spin text-primary" /> {phaseLabel}
                      </span>
                      <span className="flex items-center gap-1 font-mono tabular-nums text-muted-foreground">
                        <Clock size={11} /> {formatElapsed(elapsed)}
                      </span>
                    </div>
                    <div
                      className="h-1.5 w-full overflow-hidden rounded-full bg-secondary"
                      role="progressbar"
                      aria-label={phaseLabel}
                    >
                      <div
                        className="h-full rounded-full bg-primary transition-[width] duration-700 ease-out"
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                    <p className="text-[11px] leading-relaxed text-muted-foreground">
                      Safe to leave this page — indexing continues on the server and this progress will still be here
                      when you return. No need to click again.
                    </p>
                  </div>
                )}
              </div>
            );
          })}
          <PaginationControls
            page={reposPagination.page}
            totalPages={reposPagination.totalPages}
            totalItems={reposPagination.totalItems}
            pageSize={reposPagination.pageSize}
            onPageChange={reposPagination.setPage}
            onPageSizeChange={reposPagination.setPageSize}
            itemLabel="repositories"
          />
        </CardContent>
      </Card>

      <AlertDialog open={!!pendingDeleteRepo} onOpenChange={(open) => !open && setPendingDeleteRepo(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete repository &quot;{pendingDeleteRepo?.repo_id}&quot;?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes the repository and its index from code-intel. Search, graph queries and autonomous-resolve
              will no longer find anything in it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletingRepo}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deletingRepo}
              onClick={(e) => {
                e.preventDefault();
                void confirmDeleteRepo();
              }}
            >
              {deletingRepo ? "Deleting..." : "Delete repository"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Search                                                             */

/* ------------------------------------------------------------------ */
/* Graph                                                              */

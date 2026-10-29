"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { RefreshCw, ShieldCheck, Recycle, AlertTriangle } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import MasterKeyPrompt from "../components/tenants/MasterKeyPrompt";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { usePagination } from "../lib/usePagination";
import { formatDay, formatExact, formatRelative, UNAVAILABLE } from "../lib/platformFormat";
import {
  RECLAIM_BATCH_LIMIT,
  RECLAIM_CONFIRM_WORD,
  normalizeInventory,
  reclaimBatch,
  reclaimSummary,
  type DemoSandboxInventory,
  type ReclaimResult,
} from "../lib/demoSandboxes";
import { Checkbox } from "../../components/ui/checkbox";

// Demo sandboxes - reclaim of the legacy per-visitor /try tenants
// (GET/POST /api/platform/demo-sandboxes). The shared demo tenant that serves
// the public /try page today is shown as protected and is never selectable;
// the server refuses it too. Reclaim is irreversible (each sandbox is a real
// tenant row + schema) and is gated like tenant delete: the in-memory master
// key (X-Master-Key, re-validated server-side every call) plus a typed
// confirmation word. One click = one server batch (max 50); the next batch is
// only ever sent on another click - there is no automatic loop.

type Pending = { mode: "selected" | "all"; ids: string[] };

export default function DemoSandboxesPage() {
  const [inventory, setInventory] = useState<DemoSandboxInventory | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const [masterKey, setMasterKey] = useState<string | null>(null);
  const [masterKeyPromptOpen, setMasterKeyPromptOpen] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [typed, setTyped] = useState("");
  const [reclaiming, setReclaiming] = useState(false);
  const [lastResult, setLastResult] = useState<ReclaimResult | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const inv = normalizeInventory(await api.getDemoSandboxes());
      setInventory(inv);
      // Drop selections that no longer exist (erased since).
      setSelected((prev) => new Set([...prev].filter((id) => inv.reclaimable.some((r) => r.id === id))));
      setError(null);
    } catch (err: any) {
      setInventory(null);
      setError(err?.message || "Could not load demo sandboxes.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => inventory?.reclaimable ?? [], [inventory]);
  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(rows);
  const sharedId = inventory?.shared?.id ?? null;
  const pageIds: string[] = pageItems.map((r: any) => r.id);
  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id));

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function togglePage() {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allOnPageSelected) pageIds.forEach((id) => next.delete(id));
      else pageIds.forEach((id) => next.add(id));
      return next;
    });
  }

  function begin(mode: Pending["mode"]) {
    const ids = mode === "selected" ? reclaimBatch([...selected], sharedId) : [];
    if (mode === "selected" && ids.length === 0) return;
    setPending({ mode, ids });
    setTyped("");
    if (!masterKey) setMasterKeyPromptOpen(true);
  }

  function cancel() {
    setPending(null);
    setTyped("");
  }

  async function confirmReclaim() {
    if (!pending || !masterKey || typed !== RECLAIM_CONFIRM_WORD) return;
    setReclaiming(true);
    try {
      const raw = await api.reclaimDemoSandboxes({
        tenantIds: pending.mode === "selected" ? pending.ids : undefined,
        masterKey,
        confirm: typed,
      });
      const result: ReclaimResult = {
        erased: Array.isArray(raw?.erased) ? raw.erased : [],
        failed: Array.isArray(raw?.failed) ? raw.failed : [],
        remaining: typeof raw?.remaining === "number" ? raw.remaining : 0,
      };
      setLastResult(result);
      setSelected((prev) => new Set([...prev].filter((id) => !result.erased.includes(id))));
      if (result.failed.length) toast.error(`Reclaim finished with failures: ${reclaimSummary(result)}`);
      else toast.success(`Reclaim done: ${reclaimSummary(result)}`);
      setPending(null);
      setTyped("");
      await load();
    } catch (err: any) {
      // A rejected master key is almost certainly wrong, not transient - clear
      // it so the next attempt re-prompts (same as tenant delete).
      if (err?.status === 401 || err?.status === 403) {
        setMasterKey(null);
        setMasterKeyPromptOpen(true);
      }
      // toJson already toasted the reason.
    } finally {
      setReclaiming(false);
    }
  }

  const total = inventory?.total ?? 0;
  const selectedCount = selected.size;
  const confirmCount = pending?.mode === "selected" ? pending.ids.length : Math.min(RECLAIM_BATCH_LIMIT, total);

  return (
    <AdminShell
      title="Demo sandboxes"
      actions={
        <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading} title="Refresh">
          <RefreshCw size={14} className={loading ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Before the shared demo tenant, every anonymous /try visitor got a sandbox tenant of their own: a real tenant row
        and Postgres schema that nobody registered for. This page lists those leftovers and erases them in batches of{" "}
        {RECLAIM_BATCH_LIMIT}. Demo limits and live session metrics are on{" "}
        <Link href="/platform-admin/demo-sandbox" className="underline underline-offset-2 hover:text-foreground">
          Demo Sandbox
        </Link>
        .
      </PageHint>

      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading && !inventory && <Skeleton className="mb-5 h-32 w-full" />}

      {inventory && (
        <>
          <SectionCard title="Shared demo tenant" icon={ShieldCheck} subtitle="Protected - cannot be reclaimed">
            {inventory.shared ? (
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 text-sm font-semibold">
                    {inventory.shared.name}
                    <Badge variant="accent">
                      <ShieldCheck /> Protected
                    </Badge>
                  </div>
                  <p className="mt-0.5 text-xs text-muted-foreground">
                    schema <code>{inventory.shared.schemaName}</code> · id <code>{inventory.shared.id}</code>
                  </p>
                </div>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">Core did not report a shared demo tenant.</p>
            )}
            <InfoNote>
              This one tenant powers the public /try demo for every visitor. It is never listed below, never selectable,
              and the server refuses to reclaim it even if asked.
            </InfoNote>
          </SectionCard>

          <SectionCard
            title="Legacy sandboxes"
            icon={Recycle}
            subtitle={`${total.toLocaleString()} reclaimable${total > rows.length ? ` (showing ${rows.length})` : ""}`}
            delay={0.03}
            actions={
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={selectedCount === 0 || reclaiming || !!pending}
                  onClick={() => begin("selected")}
                  title={`Erase the selected sandboxes (first ${RECLAIM_BATCH_LIMIT} per click)`}
                >
                  Reclaim selected ({Math.min(selectedCount, RECLAIM_BATCH_LIMIT)})
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={total === 0 || reclaiming || !!pending}
                  onClick={() => begin("all")}
                  title="Erase the next batch of legacy sandboxes - click again for the batch after"
                >
                  Reclaim all ({RECLAIM_BATCH_LIMIT} per batch)
                </Button>
              </div>
            }
          >
            {selectedCount > RECLAIM_BATCH_LIMIT && (
              <p className="text-xs text-[var(--warning-text)]">
                {selectedCount} selected - one click reclaims the first {RECLAIM_BATCH_LIMIT}; click again for the rest.
              </p>
            )}

            {pending && masterKey && (
              <div className="space-y-2 rounded-lg border border-destructive/40 bg-destructive/5 p-3">
                <p className="flex items-center gap-1.5 text-sm font-semibold text-destructive">
                  <AlertTriangle size={14} /> Permanently erase {confirmCount} sandbox tenant
                  {confirmCount === 1 ? "" : "s"}?
                </p>
                <p className="text-xs text-muted-foreground">
                  {pending.mode === "all" ? `The server picks the next batch of up to ${RECLAIM_BATCH_LIMIT}. ` : ""}
                  Each tenant row and its schema are deleted. This cannot be undone. Type{" "}
                  <code className="font-semibold">{RECLAIM_CONFIRM_WORD}</code> to confirm.
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    aria-label={`Type ${RECLAIM_CONFIRM_WORD} to confirm`}
                    className="h-8 w-40"
                    value={typed}
                    autoComplete="off"
                    disabled={reclaiming}
                    onChange={(e) => setTyped(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && void confirmReclaim()}
                  />
                  <Button
                    size="sm"
                    variant="destructive"
                    disabled={typed !== RECLAIM_CONFIRM_WORD || reclaiming}
                    onClick={() => void confirmReclaim()}
                  >
                    {reclaiming ? "Reclaiming..." : "Reclaim"}
                  </Button>
                  <Button size="sm" variant="outline" disabled={reclaiming} onClick={cancel}>
                    Cancel
                  </Button>
                </div>
              </div>
            )}

            {lastResult && (
              <div className="space-y-1 rounded-lg border border-border p-3 text-xs">
                <p className="font-semibold">Last reclaim: {reclaimSummary(lastResult)}</p>
                {lastResult.failed.length > 0 && (
                  <ul className="list-disc ps-5 text-destructive">
                    {lastResult.failed.map((f) => (
                      <li key={f.id}>
                        {f.name || f.id}: {f.error}
                      </li>
                    ))}
                  </ul>
                )}
                {lastResult.remaining > 0 && !pending && (
                  <Button size="sm" variant="outline" disabled={reclaiming} onClick={() => begin("all")}>
                    Reclaim next batch ({Math.min(RECLAIM_BATCH_LIMIT, lastResult.remaining)} of {lastResult.remaining})
                  </Button>
                )}
              </div>
            )}

            {rows.length === 0 ? (
              <p className="text-sm text-muted-foreground">No legacy sandboxes left to reclaim.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="border-b border-border text-muted-foreground">
                    <tr>
                      <th className="w-8 px-2 py-1.5 text-start">
                        <Checkbox
                          checked={allOnPageSelected}
                          aria-label="Select every sandbox on this page"
                          onCheckedChange={() => togglePage()}
                        />
                      </th>
                      <th className="px-2 py-1.5 text-start font-medium">Name</th>
                      <th className="px-2 py-1.5 text-start font-medium">Schema</th>
                      <th className="px-2 py-1.5 text-start font-medium">Status</th>
                      <th className="px-2 py-1.5 text-start font-medium">Created</th>
                      <th className="px-2 py-1.5 text-start font-medium">Last session</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {pageItems.map((r: any) => (
                      <tr key={r.id}>
                        <td className="px-2 py-1.5">
                          <Checkbox
                            checked={selected.has(r.id)}
                            aria-label={`Select ${r.name}`}
                            onCheckedChange={() => toggle(r.id)}
                          />
                        </td>
                        <td className="px-2 py-1.5">{r.name}</td>
                        <td className="px-2 py-1.5 font-mono">{r.schemaName}</td>
                        <td className="px-2 py-1.5">{r.status}</td>
                        <td className="px-2 py-1.5" title={formatExact(r.createdAt)}>
                          {formatDay(r.createdAt)}
                        </td>
                        <td className="px-2 py-1.5" title={formatExact(r.lastSessionAt)}>
                          {r.lastSessionAt ? formatRelative(r.lastSessionAt) : UNAVAILABLE}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel="sandboxes"
            />
          </SectionCard>
        </>
      )}

      <MasterKeyPrompt
        open={masterKeyPromptOpen}
        description="Unlocks sandbox reclaim for the rest of this browser session. It is re-checked on every reclaim call, not just this once."
        onCancel={() => {
          setMasterKeyPromptOpen(false);
          setPending(null);
        }}
        onSubmit={(key) => {
          setMasterKey(key);
          setMasterKeyPromptOpen(false);
        }}
      />
    </AdminShell>
  );
}

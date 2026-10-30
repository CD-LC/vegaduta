"use client";

import { useCallback, useEffect, useState } from "react";
import { ShieldCheck, ScanLine, ListChecks, Ban, RefreshCw } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Alert, AlertDescription } from "../ui/alert";
import { Skeleton } from "../ui/skeleton";
import { Select } from "../../../components/ui/select";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { SectionCard, SwitchToggle, timeAgo } from "./common";
import { useTranslations } from "../../../lib/i18n";

// Safety & Moderation admin surface - backed by ModerationController
// (/api/platform/moderation/**), which reads and writes the SAME
// platform_setting keys the live guardrail engine resolves on every inbound
// turn. Nothing here is a copy of engine state: a toggle is live on the next
// message. The CSAM pack is deliberately un-enableable server-side (held for
// legal review); it renders locked with the server's own reason.

const PLANS = ["FREE", "PREMIUM", "UNLIMITED"];

function useLoad<T>(fn: () => Promise<T>, deps: any[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setData(await fn());
      setError(null);
    } catch (err: any) {
      setError(err?.message || "Could not load.");
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  return { data, error, loading, refresh };
}

function LoadState({ loading, error, onRetry }: { loading: boolean; error: string | null; onRetry: () => void }) {
  if (loading) return <Skeleton className="h-16 w-full" />;
  if (!error) return null;
  return (
    <Alert variant="destructive">
      <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
        {error}
        <Button size="sm" variant="outline" onClick={onRetry}>
          Retry
        </Button>
      </AlertDescription>
    </Alert>
  );
}

// ------------------------------------------------------------ policy packs

export function PolicyPacksPanel({ delay }: { delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsModeration");
  const [scope, setScope] = useState("GLOBAL");
  const [key, setKey] = useState("FREE");
  const scopeKey = scope === "GLOBAL" ? "" : key.trim();
  const { data, error, loading, refresh } = useLoad<any>(
    () => api.getModerationPolicyPacks(scope, scopeKey),
    [scope, scopeKey]
  );
  const [busy, setBusy] = useState<string | null>(null);

  async function toggle(pack: any) {
    setBusy(pack.id);
    try {
      await api.updateModerationPolicyPacks({ scope, key: scopeKey || null, packs: { [pack.id]: !pack.enabled } });
      toast.success(`${pack.displayName} ${pack.enabled ? "disabled" : "enabled"} - live on the next message.`);
      await refresh();
    } catch {
      // toJson already toasted the reason (e.g. the CSAM 409).
    } finally {
      setBusy(null);
    }
  }

  return (
    <SectionCard
      title={tHC("s31")}
      subtitle="Deterministic blocklist packs, toggled per platform default, plan, or tenant."
      icon={ShieldCheck}
      delay={delay}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Select
            aria-label={tHC("s30")}
            className="w-44"
            value={scope}
            onValueChange={(v) => {
              setScope(v);
              setKey(v === "PLAN" ? "FREE" : "");
            }}
            options={[
              { value: "GLOBAL", label: "Global default" },
              { value: "PLAN", label: "Per plan" },
              { value: "TENANT", label: "Per tenant" },
            ]}
          />
          {scope === "PLAN" && (
            <Select
              aria-label="Plan"
              className="w-36"
              value={key}
              onValueChange={setKey}
              options={PLANS.map((p) => ({ value: p, label: p }))}
            />
          )}
          {scope === "TENANT" && (
            <Input
              aria-label={tHC("s29")}
              className="h-9 w-64"
              placeholder={tHC("s28")}
              value={key}
              onChange={(e) => setKey(e.target.value)}
            />
          )}
        </div>
      }
    >
      <LoadState loading={loading} error={error} onRetry={refresh} />
      {scope === "TENANT" && !scopeKey && (
        <p className="text-xs text-muted-foreground">{tHC("s27")}</p>
      )}
      {!loading &&
        !error &&
        (data?.packs || []).map((pack: any) => (
          <div key={pack.id} className="flex items-start justify-between gap-3 rounded-lg border border-border p-3">
            <div className="min-w-0 space-y-1">
              <div className="flex flex-wrap items-center gap-2 text-sm font-semibold">
                {pack.displayName}
                {!pack.available && <Badge variant="destructive">{tHC("s26")}</Badge>}
                {pack.available && pack.scopeOfEffectiveValue && pack.scopeOfEffectiveValue !== scope && (
                  <Badge variant="outline">{tHC("s25")} {String(pack.scopeOfEffectiveValue).toLowerCase()}</Badge>
                )}
              </div>
              <p className="text-xs text-muted-foreground">{pack.description}</p>
              {!pack.available && pack.unavailableReason && (
                <p className="text-xs text-muted-foreground">{pack.unavailableReason}</p>
              )}
              {pack.updatedBy && (
                <p className="text-[11px] text-muted-foreground/80">
                  {tHC("s24")} {pack.updatedBy} {timeAgo(pack.updatedAt)}
                </p>
              )}
            </div>
            <SwitchToggle
              on={pack.enabled}
              label={`${pack.displayName} pack`}
              disabled={!pack.available || busy === pack.id || (scope === "TENANT" && !scopeKey)}
              onClick={() => toggle(pack)}
            />
          </div>
        ))}
    </SectionCard>
  );
}

// -------------------------------------------------------------- thresholds

export function ThresholdsPanel({ delay }: { delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsModeration");
  const { data, error, loading, refresh } = useLoad<any>(() => api.getModerationThresholds());
  const [draft, setDraft] = useState<any>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (data) setDraft(JSON.parse(JSON.stringify(data)));
  }, [data]);

  function setPack(id: string, patch: any) {
    setDraft((d: any) => ({ ...d, packs: { ...d.packs, [id]: { ...d.packs[id], ...patch } } }));
  }

  async function save() {
    const packs: Record<string, any> = {};
    for (const [id, p] of Object.entries<any>(draft.packs || {})) {
      const review = Number(p.reviewThreshold);
      const block = Number(p.blockThreshold);
      if (![review, block].every((n) => Number.isInteger(n) && n >= 0 && n <= 100)) {
        toast.error(`${id}: thresholds must be whole numbers from 0 to 100.`);
        return;
      }
      if (review > block) {
        toast.error(`${id}: the review threshold must not exceed the block threshold.`);
        return;
      }
      const before = data.packs[id];
      if (
        before.semanticEnabled !== p.semanticEnabled ||
        before.reviewThreshold !== review ||
        before.blockThreshold !== block
      ) {
        packs[id] = { semanticEnabled: p.semanticEnabled, reviewThreshold: review, blockThreshold: block };
      }
    }
    setSaving(true);
    try {
      await api.updateModerationThresholds({
        moderationEnabled: draft.moderationEnabled,
        externalClassifierEnabled: draft.externalClassifierEnabled,
        packs,
      });
      toast.success("Guardrail thresholds saved - live on the next message.");
      await refresh();
    } catch {
      // toJson already toasted.
    } finally {
      setSaving(false);
    }
  }

  const dirty = draft && data && JSON.stringify(draft) !== JSON.stringify(data);

  return (
    <SectionCard
      title={tHC("s23")}
      subtitle="Master switch, external classifier, and per-pack confidence bands (0-100)."
      icon={ScanLine}
      delay={delay}
      actions={
        <Button size="sm" disabled={!dirty || saving} onClick={save}>
          {saving ? "Saving..." : "Save thresholds"}
        </Button>
      }
    >
      <LoadState loading={loading} error={error} onRetry={refresh} />
      {draft && (
        <>
          <div className="flex flex-wrap gap-6">
            <label className="flex items-center gap-2 text-sm">
              <SwitchToggle
                on={draft.moderationEnabled}
                label={tHC("s22")}
                onClick={() => setDraft({ ...draft, moderationEnabled: !draft.moderationEnabled })}
              />
              {tHC("s21")}
            </label>
            <label className="flex items-center gap-2 text-sm">
              <SwitchToggle
                on={draft.externalClassifierEnabled}
                label={tHC("s20")}
                onClick={() => setDraft({ ...draft, externalClassifierEnabled: !draft.externalClassifierEnabled })}
              />
              {tHC("s19")}
            </label>
          </div>
          {!draft.moderationEnabled && (
            <Alert>
              <AlertDescription>
                {tHC("s18")}
              </AlertDescription>
            </Alert>
          )}
          <div className="space-y-2">
            {Object.entries<any>(draft.packs || {}).map(([id, p]) => (
              <div
                key={id}
                className="grid grid-cols-2 items-center gap-3 rounded-lg border border-border p-3 sm:grid-cols-4"
              >
                <code className="col-span-2 text-xs font-semibold sm:col-span-1">{id}</code>
                <label className="flex items-center gap-2 text-xs">
                  <SwitchToggle
                    on={p.semanticEnabled}
                    label={`${id} semantic layer`}
                    onClick={() => setPack(id, { semanticEnabled: !p.semanticEnabled })}
                  />
                  {tHC("s17")}
                </label>
                <label className="flex items-center gap-2 text-xs">
                  {tHC("s16")}
                  <Input
                    type="number"
                    min={0}
                    max={100}
                    className="h-8 w-20"
                    value={p.reviewThreshold}
                    onChange={(e) => setPack(id, { reviewThreshold: e.target.value })}
                  />
                </label>
                <label className="flex items-center gap-2 text-xs">
                  {tHC("s15")}
                  <Input
                    type="number"
                    min={0}
                    max={100}
                    className="h-8 w-20"
                    value={p.blockThreshold}
                    onChange={(e) => setPack(id, { blockThreshold: e.target.value })}
                  />
                </label>
              </div>
            ))}
          </div>
        </>
      )}
    </SectionCard>
  );
}

// ------------------------------------------------------------ review queue

const QUEUE_STATUSES = ["PENDING", "ESCALATED", "APPROVED", "BLOCKED", "ALL"];

export function ReviewQueuePanel({ delay }: { delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsModeration");
  const [status, setStatus] = useState("PENDING");
  const { data, error, loading, refresh } = useLoad<any>(() => api.getModerationReviewQueue(status), [status]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);

  async function decide(item: any, decision: string) {
    setBusy(item.id);
    try {
      await api.decideModerationReview(item.id, {
        tenantId: item.tenantId,
        decision,
        note: (notes[item.id] || "").trim() || null,
      });
      const past: Record<string, string> = { APPROVE: "approved", BLOCK: "blocked", ESCALATE: "escalated" };
      toast.success(`Item ${past[decision]}.`);
      await refresh();
    } catch {
      // toJson already toasted (409 already-decided included).
    } finally {
      setBusy(null);
    }
  }

  const items: any[] = data?.items || [];
  const pg = usePagination(items, 10);

  return (
    <SectionCard
      title={tHC("s14")}
      subtitle={
        data ? `${data.pendingCount} pending across all tenants` : "Flagged content awaiting a trust & safety decision."
      }
      icon={ListChecks}
      delay={delay}
      actions={
        <div className="flex items-center gap-2">
          <Select
            aria-label={tHC("s13")}
            className="w-40"
            value={status}
            onValueChange={setStatus}
            options={QUEUE_STATUSES.map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase() }))}
          />
          <Button size="icon" variant="outline" aria-label={tHC("s12")} onClick={refresh}>
            <RefreshCw />
          </Button>
        </div>
      }
    >
      <LoadState loading={loading} error={error} onRetry={refresh} />
      {!loading && !error && items.length === 0 && (
        <p className="text-sm text-muted-foreground">{tHC("s11")}</p>
      )}
      {pg.pageItems.map((item) => (
        <div key={item.id} className="space-y-2 rounded-lg border border-border p-3">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <Badge variant="secondary">{item.packId}</Badge>
            <Badge variant="outline">{item.decidingLayer}</Badge>
            <Badge variant="outline">confidence {item.confidence}</Badge>
            <Badge variant="outline">{item.direction}</Badge>
            <Badge variant={item.status === "PENDING" ? "accent" : "secondary"}>{item.status}</Badge>
            <span className="text-muted-foreground">
              tenant {item.tenantId} · {timeAgo(item.createdAt)}
            </span>
          </div>
          {item.reason && <p className="text-xs text-muted-foreground">{item.reason}</p>}
          <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md bg-secondary/40 p-2 text-xs">
            {item.content}
          </pre>
          {item.status === "PENDING" || item.status === "ESCALATED" ? (
            <div className="flex flex-wrap items-center gap-2">
              <Input
                aria-label={tHC("s10")}
                className="h-8 min-w-48 flex-1"
                placeholder={tHC("s9")}
                value={notes[item.id] || ""}
                onChange={(e) => setNotes({ ...notes, [item.id]: e.target.value })}
              />
              <Button size="sm" variant="outline" disabled={busy === item.id} onClick={() => decide(item, "APPROVE")}>
                Approve
              </Button>
              <Button size="sm" variant="destructive" disabled={busy === item.id} onClick={() => decide(item, "BLOCK")}>
                Block
              </Button>
              {item.status === "PENDING" && (
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={busy === item.id}
                  onClick={() => decide(item, "ESCALATE")}
                >
                  Escalate
                </Button>
              )}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              {tHC("s8")} {item.reviewedBy || "unknown"} {timeAgo(item.reviewedAt)}
              {item.reviewNotes ? ` - ${item.reviewNotes}` : ""}
            </p>
          )}
        </div>
      ))}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="items"
      />
    </SectionCard>
  );
}

// --------------------------------------------------------------- blocklists

const BLOCKLIST_TYPES = ["USER", "PHONE", "DOMAIN", "REGION"];

export function BlocklistsPanel({ delay }: { delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsModeration");
  const [type, setType] = useState("");
  const { data, error, loading, refresh } = useLoad<any>(() => api.getModerationBlocklists(type), [type]);
  const [newType, setNewType] = useState("DOMAIN");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState<string | null>(null);

  async function upsert(entry: { type: string; value: string; active: boolean; reason?: string | null }, id: string) {
    setBusy(id);
    try {
      await api.upsertModerationBlocklistEntry(entry);
      await refresh();
      return true;
    } catch {
      return false;
    } finally {
      setBusy(null);
    }
  }

  async function add() {
    if (!value.trim()) {
      toast.error("Enter a value to block.");
      return;
    }
    const ok = await upsert({ type: newType, value: value.trim(), active: true, reason: reason.trim() || null }, "new");
    if (ok) {
      toast.success(`${newType} entry blocked.`);
      setValue("");
      setReason("");
    }
  }

  const entries: any[] = data?.entries || [];
  const pg = usePagination(entries, 10);

  return (
    <SectionCard
      title={tHC("s7")}
      subtitle="Users, phone numbers, domains and regions refused on inbound. Unblocking keeps the row for audit."
      icon={Ban}
      delay={delay}
      actions={
        <Select
          aria-label={tHC("s6")}
          className="w-44"
          value={type}
          onValueChange={setType}
          options={[
            { value: "", label: "All types" },
            ...BLOCKLIST_TYPES.map((t) => ({ value: t, label: t.charAt(0) + t.slice(1).toLowerCase() })),
          ]}
        />
      }
    >
      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-dashed border-border p-3">
        <Select
          aria-label={tHC("s5")}
          className="w-40"
          value={newType}
          onValueChange={setNewType}
          options={BLOCKLIST_TYPES.map((t) => ({ value: t, label: t }))}
        />
        <Input
          aria-label={tHC("s4")}
          className="h-9 min-w-48 flex-1"
          placeholder={
            newType === "REGION" ? "ISO country code, e.g. KP" : newType === "PHONE" ? "+919999999999" : "value"
          }
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
        <Input
          aria-label="Reason"
          className="h-9 min-w-48 flex-1"
          placeholder={tHC("s3")}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        <Button size="sm" disabled={busy === "new"} onClick={add}>
          Block
        </Button>
      </div>
      <LoadState loading={loading} error={error} onRetry={refresh} />
      {!loading && !error && entries.length === 0 && <p className="text-sm text-muted-foreground">{tHC("s2")}</p>}
      {pg.pageItems.map((e) => (
        <div key={e.id} className="flex items-center justify-between gap-3 rounded-lg border border-border p-3">
          <div className="min-w-0 space-y-0.5">
            <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
              <Badge variant="secondary">{e.type}</Badge>
              <code className="break-all text-xs">{e.value}</code>
              {!e.active && <Badge variant="outline">unblocked</Badge>}
            </div>
            <p className="text-xs text-muted-foreground">
              {e.reason || "No reason recorded"} {tHC("s1")} {e.createdBy || "unknown"} {timeAgo(e.createdAt)}
            </p>
          </div>
          <SwitchToggle
            on={e.active}
            label={`Block ${e.value}`}
            disabled={busy === e.id}
            onClick={() => upsert({ type: e.type, value: e.value, active: !e.active, reason: e.reason }, e.id)}
          />
        </div>
      ))}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="entries"
      />
    </SectionCard>
  );
}

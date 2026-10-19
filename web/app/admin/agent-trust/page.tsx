"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import type { DelegationGrant, DirectoryEntry, AgentWallet } from "../../lib/api/agentTrust";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState } from "../../components/ax/page-kit";
import { ErrorRetry, FilterPills, ListSkeleton, PagedList } from "../commerce/listKit";
import { usePageReady } from "../../components/RouteTransition";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import LearnThisButton from "../../learn/LearnThisButton";
import { SpendBar, fmtTime, statusBadge } from "./trustFormat";
import { GrantWizard } from "./GrantWizard";
import { MintTokenPanel } from "./MintTokenPanel";
import { DirectoryPanel } from "./DirectoryPanel";

// Agent trust console - the tenant-side surface for the delegated-identity
// stack in core/agents/trust + core/agents/directory: delegation grants and
// minted tokens, per-grant spend caps, revocation, the signed Know-Your-Agent
// manifest, and public-directory publication. Scope gate MANAGE_AGENTS is UX
// only; enforcement stays in SecurityConfig/the controllers.

// ---- Page --------------------------------------------------------------------

export default function AdminAgentTrustPage() {
  const allowed = useRequireScope("MANAGE_AGENTS");
  const t = useTranslations("adminAgentTrustPage");
  const tg = useTranslations("f2GapsAdmin");
  const tu = useTranslations("adminCommerceUi");

  const [agents, setAgents] = useState<any[]>([]);
  const [agentId, setAgentId] = useState("");
  const [grants, setGrants] = useState<DelegationGrant[]>([]);
  const [wallets, setWallets] = useState<AgentWallet[]>([]);
  const [dirEntries, setDirEntries] = useState<DirectoryEntry[]>([]);
  const [revokeTarget, setRevokeTarget] = useState<DelegationGrant | null>(null);
  const [revokeReason, setRevokeReason] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dataLoading, setDataLoading] = useState(false);
  const [grantFilter, setGrantFilter] = useState("");

  usePageReady(loaded);

  const grantStatuses = useMemo(() => Array.from(new Set(grants.map((g) => g.status))).sort(), [grants]);
  const filteredGrants = useMemo(
    () => (grantFilter ? grants.filter((g) => g.status === grantFilter) : grants),
    [grants, grantFilter]
  );

  useEffect(() => {
    if (!allowed) return;
    api.listAgents()
      .then((rows: any) => {
        const list = Array.isArray(rows) ? rows : [];
        setAgents(list);
        if (list.length && !agentId) setAgentId(String(list[0].id));
      })
      .catch((e: any) => {
        setAgents([]);
        setError(e?.message || tu("agentsLoadFailed"));
      })
      .finally(() => setLoaded(true));
    api.myDirectoryEntries()
      .then((rows) => setDirEntries(Array.isArray(rows) ? rows : []))
      .catch(() => setDirEntries([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowed]);

  const loadForAgent = useCallback(async (id: string) => {
    if (!id) return;
    setError(null);
    setDataLoading(true);
    try {
      const [grantRows, walletRows] = await Promise.all([
        api.listDelegationGrants(id),
        api.listWallets(id),
      ]);
      setGrants(Array.isArray(grantRows) ? grantRows : []);
      setWallets(Array.isArray(walletRows) ? walletRows : []);
    } catch (e: any) {
      setError(e?.message || t("loadFailed"));
    } finally {
      setDataLoading(false);
    }
  }, [t]);

  const refreshDirectory = useCallback(() => {
    api.myDirectoryEntries()
      .then((rows) => setDirEntries(Array.isArray(rows) ? rows : []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (allowed && agentId) void loadForAgent(agentId);
  }, [allowed, agentId, loadForAgent]);

  const handleRevoke = async () => {
    if (!revokeTarget) return;
    try {
      await api.revokeDelegationGrant(agentId, revokeTarget.id, revokeReason.trim() || undefined);
      toast.success(t("revokeSuccess"));
      setRevokeTarget(null);
      setRevokeReason("");
      void loadForAgent(agentId);
    } catch (e: any) {
      toast.error(e?.message || t("revokeFailed"));
    }
  };

  if (!allowed) return null;

  const dirEntry = dirEntries.find((e) => e.agentId === agentId);

  return (
    <AppShell eyebrow={tg("eyebrow")}>
      <>
        <PageHeader
          title={t("pageTitle")}
          subtitle={t("pageSubtitle")}
          actions={<LearnThisButton path="/admin/agent-trust" />}
        />

        <div className="mb-5 max-w-md">
          <Label htmlFor="trust-agent">{t("agentPickerLabel")}</Label>
          <Select
            id="trust-agent"
            value={agentId}
            onValueChange={setAgentId}
            placeholder={t("noAgents")}
            options={agents.map((a: any) => ({ value: String(a.id), label: a.name || a.id }))}
          />
        </div>

        <p className="mb-4 text-sm text-muted-foreground">
          {tu("relatedLabel")}:{" "}
          <Link href="/admin/commerce" className="text-primary underline">
            {tu("viewCommerce")}
          </Link>
          {" · "}
          <Link href="/admin/payments" className="text-primary underline">
            {tu("viewPayments")}
          </Link>
        </p>

        {error && <ErrorRetry message={error} onRetry={() => (agentId ? loadForAgent(agentId) : window.location.reload())} />}

        {agentId && dataLoading && grants.length === 0 && <ListSkeleton />}

        {agentId && <DirectoryPanel agentId={agentId} entry={dirEntry} onChanged={refreshDirectory} />}

        <Card className="mt-6 p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">{t("grantsHeading")}</h2>
            {agentId && (
              <GrantWizard agentId={agentId} wallets={wallets} onGranted={() => loadForAgent(agentId)} />
            )}
          </div>
          {grants.length === 0 ? (
            <EmptyState title={t("noGrantsTitle")} description={t("noGrantsHint")} />
          ) : (
            <>
              <div className="mb-3">
                <FilterPills options={grantStatuses} value={grantFilter} onChange={setGrantFilter} />
              </div>
              {filteredGrants.length === 0 ? (
                <p className="text-sm text-muted-foreground">{tu("noMatches")}</p>
              ) : (
                <PagedList
                  items={filteredGrants}
                  itemLabel={tu("itemGrants")}
                  className="grid gap-3"
                  renderItem={(g) => (
                  <div key={g.id} className="rounded-lg border border-border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      {statusBadge(g.status)}
                      <span className="font-mono text-xs">{g.audience}</span>
                      <span className="text-xs text-muted-foreground">
                        {g.scopes.map((s) => (
                          <Badge key={s} variant="outline" className="mr-1">{s}</Badge>
                        ))}
                      </span>
                      <span className="ml-auto text-xs text-muted-foreground">
                        {t("grantExpiryRow", { exp: fmtTime(g.expiresAt) })}
                      </span>
                      {g.status === "ACTIVE" && (
                        <>
                          <MintTokenPanel agentId={agentId} grant={g} />
                          <Button
                            type="button"
                            variant="destructive"
                            size="sm"
                            onClick={() => { setRevokeTarget(g); setRevokeReason(""); }}
                          >
                            {t("revoke")}
                          </Button>
                        </>
                      )}
                    </div>
                    {(g.spendCapMinor != null || g.walletId) && (
                      <div className="mt-3 flex flex-wrap items-center gap-6">
                        <SpendBar spent={g.spentMinor} cap={g.spendCapMinor} />
                        {g.walletId && (
                          <span className="text-xs text-muted-foreground">
                            {t("walletRow")} <code className="font-mono">{g.walletId.slice(0, 8)}…</code>
                          </span>
                        )}
                      </div>
                    )}
                    <div className="mt-2 text-xs text-muted-foreground">
                      {t("delegatedBy", { user: g.grantedBy })} · ttl {g.maxTokenTtlSeconds}s
                    </div>
                    {g.revokeReason && (
                      <div className="mt-1 text-xs text-muted-foreground">
                        {t("revokedReasonRow", { reason: g.revokeReason })}
                      </div>
                    )}
                  </div>
                  )}
                />
              )}
            </>
          )}
        </Card>

        <AlertDialog open={!!revokeTarget} onOpenChange={(v) => !v && setRevokeTarget(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{t("revokeTitle")}</AlertDialogTitle>
              <AlertDialogDescription>{t("revokeBody")}</AlertDialogDescription>
            </AlertDialogHeader>
            <Input
              value={revokeReason}
              placeholder={t("revokeReasonPlaceholder")}
              onChange={(e) => setRevokeReason(e.target.value)}
            />
            <AlertDialogFooter>
              <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
              <AlertDialogAction onClick={handleRevoke}>{t("revokeConfirm")}</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </>
    </AppShell>
  );
}

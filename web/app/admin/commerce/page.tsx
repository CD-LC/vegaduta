"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import type { AgentWallet, CommerceMandate, X402Offer } from "../../lib/api/agentTrust";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState } from "../../components/ax/page-kit";
import { usePageReady } from "../../components/RouteTransition";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card } from "../../components/ui/card";
import { Label } from "../../components/ui/label";
import OfferForm from "./OfferForm";
import { ErrorRetry, FilterPills, ListSkeleton, PagedList } from "./listKit";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import LearnThisButton, { LearnLink } from "../../learn/LearnThisButton";
import { fmtTime, statusBadge } from "./commerceFormat";
import { WalletSection } from "./WalletSection";
import { IntentForm } from "./IntentForm";
import { MandateRow } from "./MandateRow";

// Agentic-commerce console - agent wallets (core/payments/wallet), the
// AP2-style intent->cart mandate chain (core/payments/commerce), and the
// x402 pay-per-call offers an agent sells (core/agents-tools tools/x402). Amounts
// are integer minor units end to end; the UI labels them as such instead of
// pretending a currency conversion.

// ---- Page ----------------------------------------------------------------------

export default function AdminCommercePage() {
  const allowed = useRequireScope("MANAGE_AGENTS");
  const t = useTranslations("adminCommercePage");
  const tu = useTranslations("adminCommerceUi");
  const tl = useTranslations("adminLeftoversUi");

  const [agents, setAgents] = useState<any[]>([]);
  const [agentId, setAgentId] = useState("");
  const [wallets, setWallets] = useState<AgentWallet[]>([]);
  const [mandates, setMandates] = useState<CommerceMandate[]>([]);
  const [offers, setOffers] = useState<X402Offer[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dataLoading, setDataLoading] = useState(false);
  const [mandateFilter, setMandateFilter] = useState("");

  usePageReady(loaded);

  const mandateStatuses = useMemo(() => Array.from(new Set(mandates.map((m) => m.status))).sort(), [mandates]);
  const filteredMandates = useMemo(
    () => (mandateFilter ? mandates.filter((m) => m.status === mandateFilter) : mandates),
    [mandates, mandateFilter]
  );

  useEffect(() => {
    if (!allowed) return;
    api
      .listAgents()
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowed]);

  const loadForAgent = useCallback(
    async (id: string) => {
      if (!id) return;
      setError(null);
      setDataLoading(true);
      try {
        const [walletRows, mandateRows, offerRows] = await Promise.all([
          api.listWallets(id),
          api.listCommerceMandates(id),
          api.listX402Offers(id),
        ]);
        setWallets(Array.isArray(walletRows) ? walletRows : []);
        setMandates(Array.isArray(mandateRows) ? mandateRows : []);
        setOffers(Array.isArray(offerRows) ? offerRows : []);
      } catch (e: any) {
        setError(e?.message || t("loadFailed"));
      } finally {
        setDataLoading(false);
      }
    },
    [t]
  );

  useEffect(() => {
    if (allowed && agentId) void loadForAgent(agentId);
  }, [allowed, agentId, loadForAgent]);

  const handleDisableOffer = async (offer: X402Offer) => {
    try {
      await api.disableX402Offer(agentId, offer.id);
      toast.success(t("offerDisabled"));
      void loadForAgent(agentId);
    } catch (e: any) {
      toast.error(e?.message || t("disableFailed"));
    }
  };

  if (!allowed) return null;

  return (
    <AppShell eyebrow={tl("eyebrowAdministration")}>
      <>
        <PageHeader
          title={t("pageTitle")}
          subtitle={t("pageSubtitle")}
          actions={<LearnThisButton path="/admin/commerce" />}
        />

        <div className="mb-5 max-w-md">
          <Label htmlFor="commerce-agent">{t("agentPickerLabel")}</Label>
          <Select
            id="commerce-agent"
            value={agentId}
            onValueChange={setAgentId}
            placeholder={t("noAgents")}
            options={agents.map((a: any) => ({ value: String(a.id), label: a.name || a.id }))}
          />
        </div>

        <p className="mb-4 text-sm text-muted-foreground">
          {tu("relatedLabel")}:{" "}
          <Link href="/admin/payments" className="text-primary underline">
            {tu("viewPayments")}
          </Link>
          {" · "}
          <Link href="/admin/agent-trust" className="text-primary underline">
            {tu("viewAgentTrust")}
          </Link>
        </p>

        {error && <ErrorRetry message={error} onRetry={() => (agentId ? loadForAgent(agentId) : window.location.reload())} />}

        {agentId && dataLoading && wallets.length === 0 && mandates.length === 0 && <ListSkeleton />}

        {agentId && <WalletSection agentId={agentId} wallets={wallets} onChanged={() => loadForAgent(agentId)} />}

        <Card className="mt-6 p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">{t("mandatesHeading")}</h2>
            {agentId && <IntentForm agentId={agentId} onCreated={() => loadForAgent(agentId)} />}
          </div>
          {mandates.length === 0 ? (
            <EmptyState title={t("noMandatesTitle")} description={t("noMandatesHint")} />
          ) : (
            <>
              <div className="mb-3">
                <FilterPills options={mandateStatuses} value={mandateFilter} onChange={setMandateFilter} />
              </div>
              {filteredMandates.length === 0 ? (
                <p className="text-sm text-muted-foreground">{tu("noMatches")}</p>
              ) : (
                <PagedList
                  items={filteredMandates}
                  itemLabel={tu("itemMandates")}
                  className="grid gap-3"
                  renderItem={(m) => <MandateRow key={m.id} m={m} onChanged={() => loadForAgent(agentId)} />}
                />
              )}
            </>
          )}
        </Card>

        <Card className="mt-6 p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">{t("offersHeading")}</h2>
            {/* l4-13 teaches x402 itself — what a slug's paid endpoint is and
                who pays it — a quieter companion to the header's L17 button. */}
            <LearnLink lessonId="l4-13" fromPath="/admin/commerce" label="Learn" />
            {agentId && <OfferForm agentId={agentId} onCreated={() => loadForAgent(agentId)} />}
          </div>
          {offers.length === 0 ? (
            <EmptyState title={t("noOffersTitle")} description={t("noOffersHint")} />
          ) : (
            <PagedList
              items={offers}
              itemLabel={tu("itemOffers")}
              renderItem={(o) => (
                <div key={o.id} className="rounded-lg border border-border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    {statusBadge(o.status)}
                    <code className="break-all font-mono text-sm">{o.url}</code>
                    <span className="text-xs text-muted-foreground">
                      {o.title} · {o.priceMinor} {o.currency}
                    </span>
                    {o.evm && <Badge variant="outline">EVM</Badge>}
                    <span className="ml-auto text-xs text-muted-foreground">{fmtTime(o.createdAt)}</span>
                    {o.status === "ACTIVE" && (
                      <Button type="button" variant="destructive" size="sm" onClick={() => handleDisableOffer(o)}>
                        {t("disable")}
                      </Button>
                    )}
                  </div>
                </div>
              )}
            />
          )}
        </Card>
      </>
    </AppShell>
  );
}

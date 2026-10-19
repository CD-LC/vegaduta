"use client";

// Agent wallet card for the agentic-commerce console (page.tsx).

import { useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { api } from "../../lib/api";
import type { AgentWallet } from "../../lib/api/agentTrust";
import { EmptyState } from "../../components/ax/page-kit";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import WalletLedger from "./WalletLedger";
import { PagedList } from "./listKit";
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
import { statusBadge } from "./commerceFormat";

// ---- Wallet card -------------------------------------------------------------

export function WalletSection({
  agentId,
  wallets,
  onChanged,
}: {
  agentId: string;
  wallets: AgentWallet[];
  onChanged: () => void;
}) {
  const t = useTranslations("adminCommercePage");
  const tu = useTranslations("adminCommerceUi");
  const [open, setOpen] = useState(false);
  const [currency, setCurrency] = useState("USD");
  const [creditTarget, setCreditTarget] = useState<AgentWallet | null>(null);
  const [creditAmount, setCreditAmount] = useState("");
  const [ledgerFor, setLedgerFor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleOpen = async () => {
    if (currency.trim().length !== 3) {
      toast.error(t("currencyInvalid"));
      return;
    }
    setBusy(true);
    try {
      await api.openWallet(agentId, currency.trim().toUpperCase());
      toast.success(t("walletOpened"));
      setOpen(false);
      onChanged();
    } catch (e: any) {
      toast.error(e?.message || t("walletOpenFailed"));
    } finally {
      setBusy(false);
    }
  };

  const handleCredit = async () => {
    if (!creditTarget) return;
    const amount = Math.round(Number(creditAmount));
    if (!Number.isFinite(amount) || amount <= 0) {
      toast.error(t("amountInvalid"));
      return;
    }
    setBusy(true);
    try {
      await api.creditWallet(
        creditTarget.id,
        amount,
        `ui-credit:${creditTarget.id}:${Date.now()}`,
        t("creditNoteDefault")
      );
      toast.success(t("creditSuccess"));
      setCreditTarget(null);
      setCreditAmount("");
      onChanged();
    } catch (e: any) {
      toast.error(e?.message || t("creditFailed"));
    } finally {
      setBusy(false);
    }
  };

  const toggleLedger = (w: AgentWallet) => setLedgerFor(ledgerFor === w.id ? null : w.id);

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold">{t("walletsHeading")}</h2>
        {open ? (
          <div className="flex items-end gap-2">
            <div className="grid gap-1">
              <Label htmlFor="wallet-ccy">{t("fieldCurrency")}</Label>
              <Input
                id="wallet-ccy"
                className="w-24"
                maxLength={3}
                value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              />
            </div>
            <Button type="button" size="sm" disabled={busy} onClick={handleOpen}>
              {t("openWallet")}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
              {t("cancel")}
            </Button>
          </div>
        ) : (
          <Button type="button" size="sm" onClick={() => setOpen(true)}>
            {t("openWallet")}
          </Button>
        )}
      </div>
      {wallets.length === 0 ? (
        <EmptyState title={t("noWalletsTitle")} description={t("noWalletsHint")} />
      ) : (
        <PagedList
          items={wallets}
          itemLabel={tu("itemWallets")}
          renderItem={(w) => (
            <div key={w.id} className="rounded-lg border border-border p-3">
              <div className="flex flex-wrap items-center gap-2">
                {statusBadge(w.status)}
                <Badge variant="outline">{w.currency}</Badge>
                <span className="font-mono text-sm">{t("balanceRow", { balance: w.balanceMinor })}</span>
                <code className="ml-auto text-xs text-muted-foreground">{w.id}</code>
                {w.status === "ACTIVE" && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setCreditTarget(w);
                      setCreditAmount("");
                    }}
                  >
                    {t("credit")}
                  </Button>
                )}
                <Button type="button" variant="ghost" size="sm" onClick={() => toggleLedger(w)}>
                  {ledgerFor === w.id ? (
                    <ChevronUp size={14} aria-hidden="true" />
                  ) : (
                    <ChevronDown size={14} aria-hidden="true" />
                  )}
                  {t("ledger")}
                </Button>
              </div>
              {ledgerFor === w.id && (
                <div className="mt-2">
                  <WalletLedger walletId={w.id} />
                </div>
              )}
            </div>
          )}
        />
      )}

      <AlertDialog open={!!creditTarget} onOpenChange={(v) => !v && setCreditTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("creditTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {creditTarget && t("creditBody", { currency: creditTarget.currency })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <Input
            inputMode="numeric"
            aria-label={tu("creditAmountLabel")}
            placeholder={t("amountMinorPlaceholder")}
            value={creditAmount}
            onChange={(e) => setCreditAmount(e.target.value)}
          />
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={handleCredit}>{t("creditConfirm")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

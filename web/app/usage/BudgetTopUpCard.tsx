"use client";

import { useCallback, useEffect, useState } from "react";
import { Wallet } from "lucide-react";
import { budgetTopUpApi, type BudgetTopUpOptions } from "../lib/api/budgetTopUp";
import { openOrderCheckout } from "../lib/payments/checkout";
import type { OrderPaymentResult } from "../lib/payments/types";
import { errorMessage } from "../lib/errors";
import { useTranslations } from "../lib/i18n";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Skeleton } from "../components/ui/skeleton";

type Phase = "idle" | "creating" | "checkout" | "confirming";

/**
 * "Top up budget" for the per-user dollar budget (BudgetTopUpController): list the purchasable amounts, create
 * an order, open the gateway checkout, then confirm the payment so the server credits the budget. Nothing is
 * credited by this component: the server credits on a verified confirm, using the amount from its own order.
 * Renders nothing when the platform has top-ups switched off or the endpoint does not exist on this deployment.
 */
export default function BudgetTopUpCard() {
  const t = useTranslations("channelsUsageMcp");
  const [opts, setOpts] = useState<BudgetTopUpOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [hidden, setHidden] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [amount, setAmount] = useState("");
  const [phase, setPhase] = useState<Phase>("idle");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  // A payment the gateway accepted but the server has not confirmed yet; kept so confirm can be retried
  // without paying again.
  const [unconfirmed, setUnconfirmed] = useState<OrderPaymentResult | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError("");
    budgetTopUpApi
      .getBudgetTopUpOptions()
      .then((o) => {
        setOpts(o);
        setHidden(!o.enabled);
        if (o.options?.length) setAmount((cur) => cur || String(o.options[0].usdAmount));
      })
      .catch((err: { status?: number }) => {
        if (err?.status === 404 || err?.status === 403) setHidden(true);
        else setLoadError(errorMessage(err, t("topUpLoadError")));
      })
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  const confirm = useCallback(
    async (paid: OrderPaymentResult) => {
      setPhase("confirming");
      setError("");
      try {
        const res = await budgetTopUpApi.confirmBudgetTopUp(paid.orderId, paid.paymentId, paid.signature);
        setUnconfirmed(null);
        setSuccess(
          res.credited
            ? t("topUpCredited", { amount: String(res.amountUsd) })
            : res.alreadyProcessed
              ? t("topUpAlreadyProcessed")
              : t("topUpNotCredited"),
        );
      } catch (err) {
        setUnconfirmed(paid);
        setError(errorMessage(err, t("topUpConfirmError")));
      } finally {
        setPhase("idle");
      }
    },
    [t],
  );

  async function start() {
    if (!opts) return;
    const usd = Number(amount);
    if (!Number.isFinite(usd) || usd < opts.minUsd || usd > opts.maxUsd) {
      setError(t("topUpAmountRange", { min: String(opts.minUsd), max: String(opts.maxUsd) }));
      return;
    }
    setError("");
    setSuccess("");
    setPhase("creating");
    try {
      const order = await budgetTopUpApi.purchaseBudgetTopUp(usd);
      setPhase("checkout");
      const outcome = await openOrderCheckout(
        {
          gateway: order.gatewayName.toLowerCase(),
          orderId: order.orderId,
          amount: order.amountInSubunits,
          currency: order.currency,
          publicKeyId: order.publicKeyId,
          checkoutMode: "WIDGET",
        },
        {
          description: t("topUpDescription", { amount: String(order.amountUsd) }),
          onPaid: (paid) => void confirm(paid),
          onDismiss: () => setPhase((p) => (p === "checkout" ? "idle" : p)),
        },
      );
      if (outcome === "unavailable") {
        setPhase("idle");
        setError(t("topUpCheckoutUnavailable"));
      }
    } catch (err) {
      setPhase("idle");
      setError(errorMessage(err, t("topUpPurchaseError")));
    }
  }

  if (hidden) return null;
  if (loading) return <Skeleton className="h-28 w-full rounded-xl" data-testid="topup-skeleton" />;

  if (loadError) {
    return (
      <Alert variant="destructive" data-testid="topup-load-error">
        <AlertDescription className="flex flex-wrap items-center gap-3">
          <span>{loadError}</span>
          <Button type="button" size="sm" variant="outline" onClick={load}>
            {t("retry")}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
  if (!opts) return null;

  const busy = phase !== "idle";
  return (
    <Card data-testid="budget-topup">
      <CardContent className="space-y-3">
        <div className="flex items-start gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-primary">
            <Wallet className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0 space-y-0.5">
            <h3 className="text-sm font-semibold text-foreground">{t("topUpTitle")}</h3>
            <p className="text-sm text-muted-foreground">
              {t("topUpBody", { min: String(opts.minUsd), max: String(opts.maxUsd) })}
            </p>
          </div>
        </div>

        {error && (
          <Alert variant="destructive" data-testid="topup-error">
            <AlertDescription className="flex flex-wrap items-center gap-3">
              <span>{error}</span>
              {unconfirmed && (
                <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void confirm(unconfirmed)}>
                  {t("topUpRetryConfirm")}
                </Button>
              )}
            </AlertDescription>
          </Alert>
        )}
        {success && (
          <Alert variant="success" data-testid="topup-success">
            <AlertDescription>{success}</AlertDescription>
          </Alert>
        )}

        <div role="group" aria-label={t("topUpAmountsLabel")} className="flex flex-wrap gap-2">
          {opts.options.map((o) => (
            <Button
              key={o.usdAmount}
              type="button"
              size="sm"
              variant={Number(amount) === o.usdAmount ? "default" : "outline"}
              aria-pressed={Number(amount) === o.usdAmount}
              disabled={busy}
              onClick={() => setAmount(String(o.usdAmount))}
            >
              {o.label}
            </Button>
          ))}
        </div>

        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label htmlFor="topup-amount" className="text-xs text-muted-foreground">
              {t("topUpCustomLabel")}
            </Label>
            <Input
              id="topup-amount"
              type="number"
              inputMode="decimal"
              min={opts.minUsd}
              max={opts.maxUsd}
              step="1"
              value={amount}
              disabled={busy}
              onChange={(e) => setAmount(e.target.value)}
              className="w-32"
            />
          </div>
          <Button type="button" disabled={busy || !amount} onClick={() => void start()}>
            {phase === "creating"
              ? t("topUpCreating")
              : phase === "checkout"
                ? t("topUpAwaitingPayment")
                : phase === "confirming"
                  ? t("topUpConfirming")
                  : t("topUpButton")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

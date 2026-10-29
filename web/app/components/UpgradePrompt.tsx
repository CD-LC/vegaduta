"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { X, Zap, Sparkles } from "lucide-react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Input } from "./ui/input";
import { TriangleAlert } from "lucide-react";
import { describeUpgradeError, type UpgradeErrorView } from "../lib/payments/upgradeError";
import { useTranslations } from "../lib/i18n";
import { isBillingEnabled } from "../lib/billing-flag";
import { planPrice, type PaidTier } from "../lib/payments/pricing";
import { useCurrency } from "../lib/payments/useCurrency";
import CurrencySwitch from "./billing/CurrencySwitch";
import { defaultGateway, openSubscriptionCheckout } from "../lib/payments/checkout";
import type { GatewayList } from "../lib/payments/types";

// #811 - "Wire tenant-console 'Upgrade your plan' text to a real flow."
// Every "Upgrade your plan" mention in this app (AgentsPanel's 5-agent
// limit, WorkflowDesignerPanel's 3-workflow limit, WorkflowCanvas's
// free-tier edit-lock) used to be static, dead-end text - this is the one
// shared component all three now open instead. Same Razorpay Standard
// Checkout client-side pattern signup/page.js already uses (see that file's
// loadRazorpayCheckoutScript/openCheckout for the original) - reused here
// rather than re-invented, cached on window so re-opening this dialog after
// closing it once never re-fetches the script.
//
// Prices/copy match Agentic-AI-Platform-Subscription-Plan-and-GTM-Strategy.md's
// Section 3 table, extended by the 2026-07-31 pricing audit (§5.1): the
// dialog is now region-aware via the same useRegion() signal the public
// /billing page uses for its ₹-vs-$ display, offers a Monthly/Annual pick
// (annual = 10× monthly, "2 months free") and an optional coupon code - all
// three are sent in the upgrade payload (currency/period/couponCode), which
// an older core simply ignores. The ₹ and $ figures are deliberate regional
// price points (India PPP), not FX conversions - each plan card says so.
// `recommended` guides a free-tier customer toward the natural first upgrade
// (the entry paid tier) rather than presenting two equal-weight choices with no
// direction - it gets the accent emphasis + the only saturated primary CTA.
const PLAN_COPY = {
  PREMIUM: {
    labelKey: "planLabelPremium",
    blurbKey: "planBlurbPremium",
    recommended: true,
  },
  UNLIMITED: {
    labelKey: "planLabelUnlimited",
    blurbKey: "planBlurbUnlimited",
    recommended: false,
  },
};

type PlanKey = keyof typeof PLAN_COPY;

// One lookup per page load: the gateway list does not change while a dialog is open. A failure resolves null so an
// older core (no /api/billing/gateways) keeps working off the upgrade handoff alone.
let gatewayListPromise: Promise<GatewayList | null> | null = null;
function loadGateways(): Promise<GatewayList | null> {
  if (gatewayListPromise === null) {
    gatewayListPromise = api.listPaymentGateways().then(
      (l: GatewayList): GatewayList | null => l,
      (): GatewayList | null => null
    );
  }
  return gatewayListPromise;
}

// Elements a keyboard user can land on while tabbing inside the dialog -
// used both to pick the initial focus target and to wrap Tab/Shift+Tab so
// focus can't escape into the page behind the (aria-modal) dialog.
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * @param open whether the dialog is showing
 * @param onClose called on backdrop click, Escape, the close button, or
 *   after a successful checkout handoff
 * @param reason optional context line (e.g. "You've used all 5 agents
 *   included in your plan.") shown above the plan picker - each caller
 *   passes its own specific trigger message rather than this component
 *   guessing why it was opened.
 */
interface UpgradePromptProps {
  open: boolean;
  onClose: () => void;
  reason?: string;
}

// Billing switch (lib/billing-flag.ts): with billing off there is no plan to
// sell, so the dialog never renders - whoever opened it keeps its own message.
// A wrapper (not an early return in the dialog) keeps the hook order fixed.
export default function UpgradePrompt(props: UpgradePromptProps) {
  if (!isBillingEnabled()) return null;
  return <UpgradePromptDialog {...props} />;
}

function UpgradePromptDialog({ open, onClose, reason }: UpgradePromptProps) {
    const tHC = useTranslations("hcComponentsUpgradePrompt");
  const t = useTranslations("upgradePrompt");
  const { currency, detected, choices, choose, annualAvailable } = useCurrency();
  const [mounted, setMounted] = useState(false);
  const [busyPlan, setBusyPlan] = useState<PlanKey | null>(null);
  const [error, setError] = useState<UpgradeErrorView | null>(null);
  // Billing period + optional coupon (pricing audit §5.1) - both travel in
  // the upgrade payload. Currency is derived from region, matching how the
  // public pricing page decides which symbol to show.
  const [period, setPeriod] = useState<"MONTHLY" | "ANNUAL">("MONTHLY");
  // A yearly card is only real when a dedicated yearly plan exists for the chosen currency (never a monthly plan
  // under a "per year" label), so fall back to monthly when it does not.
  const effectivePeriod: "MONTHLY" | "ANNUAL" = annualAvailable ? period : "MONTHLY";
  const [coupon, setCoupon] = useState("");
  const modalRef = useRef<HTMLDivElement | null>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => setMounted(true), []);

  // Escape-to-close plus a Tab/Shift+Tab trap that keeps focus cycling
  // within the dialog's own focusable elements while it's open.
  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key === "Tab" && modalRef.current) {
        const focusable = modalRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // On open: remember what had focus and move focus into the dialog. On
  // close (or unmount): give focus back to whatever triggered the dialog.
  useEffect(() => {
    if (!open || !mounted) return undefined;
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const node = modalRef.current;
    if (node) {
      const focusable = node.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
      if (focusable.length > 0) {
        focusable[0].focus();
      } else {
        node.focus();
      }
    }
    return () => {
      if (previousFocusRef.current && typeof previousFocusRef.current.focus === "function") {
        previousFocusRef.current.focus();
      }
    };
  }, [open, mounted]);

  if (!mounted || !open) return null;

  async function handlePick(plan: PlanKey) {
    setError(null);
    setBusyPlan(plan);
    try {
      const result = await api.initiateTenantUpgrade(plan, {
        currency,
        period: effectivePeriod,
        couponCode: coupon.trim() || undefined,
      });
      const planLabel = PLAN_COPY[plan] ? t(PLAN_COPY[plan].labelKey) : plan;
      // The gateway list says HOW this deployment's gateway wants to be opened (widget vs hosted page); the shared
      // checkout layer picks the driver. A failed lookup (older core) falls back to the handoff's own shape.
      const gateway = defaultGateway(await loadGateways());
      const outcome = await openSubscriptionCheckout(gateway, result, {
        description: t("checkoutDescription", { plan: planLabel }),
        // Same posture as signup/page.js's own handler: this is a UX handoff only, not confirmation the upgrade
        // took effect - that's the gateway webhook's job, server-side. The toast says "shortly," not "done."
        onComplete: () => {
          toast.success(t("paymentReceivedToast", { plan: planLabel }));
          setBusyPlan(null);
          onClose();
        },
        onDismiss: () => setBusyPlan(null),
      });
      if (outcome === "unavailable") {
        setError({ title: "The payment window did not open", detail: t("paymentWindowError"), retryable: true });
        setBusyPlan(null);
      }
    } catch (err) {
      setError(describeUpgradeError(err));
      setBusyPlan(null);
    }
  }

  return createPortal(
    <div
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 1000,
        background: "rgba(0,0,0,0.55)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "1rem",
        overflowY: "auto",
      }}
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={modalRef}
        className="relative max-h-[calc(100dvh-2rem)] w-full max-w-[440px] overflow-y-auto rounded-xl border border-border bg-card p-6 text-card-foreground shadow-xl"
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={t("dialogAriaLabel")}
      >
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onClose}
          aria-label={t("closeAriaLabel")}
          className="absolute right-3.5 top-3.5"
        >
          <X aria-hidden="true" size={16} />
        </Button>

        <h2 className="flex items-center gap-2 text-lg font-semibold tracking-tight text-foreground">
          <Zap aria-hidden="true" size={18} className="text-primary" /> {t("heading")}
        </h2>
        {reason && <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{reason}</p>}
        {error && (
          <div
            role="alert"
            style={{ marginTop: "0.75rem" }}
            className="flex items-start gap-3 rounded-xl border border-destructive/30 bg-destructive/10 p-3"
          >
            <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-destructive/15 text-destructive">
              <TriangleAlert className="size-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold text-foreground">{error.title}</p>
              <p className="mt-0.5 break-words text-sm text-muted-foreground">{error.detail}</p>
              {error.retryable && <p className="mt-1 text-xs text-muted-foreground">{tHC("hcComponentsUpgradePrompt.s1")}</p>}
            </div>
          </div>
        )}

        {/* Monthly / Annual pick - travels as `period` in the payload. */}
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <div
            className="inline-flex items-center rounded-full border border-border bg-secondary/40 p-0.5"
            role="group"
            aria-label={t("periodToggleAria")}
          >
            {((annualAvailable ? ["MONTHLY", "ANNUAL"] : ["MONTHLY"]) as Array<"MONTHLY" | "ANNUAL">).map((p) => (
              <button
                key={p}
                type="button"
                aria-pressed={effectivePeriod === p}
                onClick={() => setPeriod(p)}
                disabled={busyPlan !== null}
                className={`rounded-full border-0 px-3 py-1 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${
                  effectivePeriod === p
                    ? "bg-primary text-primary-foreground"
                    : "bg-transparent text-muted-foreground hover:text-foreground"
                }`}
              >
                {p === "MONTHLY" ? t("periodMonthly") : t("periodAnnual")}
              </button>
            ))}
          </div>
          {effectivePeriod === "ANNUAL" && (
            <span className="text-xs font-medium text-primary">{t("annualTwoMonthsFree")}</span>
          )}
        </div>
        <div className="mt-2">
          <CurrencySwitch
            currency={currency}
            detected={detected}
            choices={choices}
            onChange={choose}
            disabled={busyPlan !== null}
          />
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: "0.9rem", marginTop: "1rem" }}>
          {(Object.entries(PLAN_COPY) as Array<[PlanKey, (typeof PLAN_COPY)[PlanKey]]>).map(([plan, copy]) => {
            const label = t(copy.labelKey);
            const recommended = Boolean(copy.recommended);
            return (
              <div
                key={plan}
                style={{
                  border: `1px solid ${recommended ? "var(--accent-500)" : "var(--surface-border)"}`,
                  borderRadius: 12,
                  padding: "1rem",
                  background: recommended ? "var(--accent-50)" : "transparent",
                  boxShadow: recommended ? "0 0 0 3px var(--accent-glow)" : "none",
                  display: "flex",
                  flexDirection: "column",
                  gap: "0.5rem",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                  <strong style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
                    {recommended && <Sparkles size={14} style={{ color: "var(--accent-600)" }} aria-hidden="true" />}
                    {label}
                  </strong>
                  <Badge variant="secondary">{planPrice(plan as PaidTier, currency, effectivePeriod)}</Badge>
                </div>
                {/* Explicit regional-pricing line (audit §2.5-2): ₹ and $ are
                    two deliberate price points, not one price at FX. */}
                {choices.length > 1 && (
                  <p className="m-0 text-xs text-muted-foreground">
                    {t("regionalPriceLine", {
                      inr: planPrice(plan as PaidTier, "INR", effectivePeriod),
                      usd: planPrice(plan as PaidTier, "USD", effectivePeriod),
                    })}
                  </p>
                )}
                <p className="m-0 text-sm text-muted-foreground">{t(copy.blurbKey)}</p>
                <Button
                  type="button"
                  variant={recommended ? "default" : "outline"}
                  disabled={busyPlan !== null}
                  onClick={() => handlePick(plan)}
                  style={{ marginBottom: 0 }}
                >
                  {busyPlan === plan ? t("openingPaymentWindow") : t("upgradeToButton", { plan: label })}
                </Button>
              </div>
            );
          })}
        </div>

        {/* Optional coupon code - sent as couponCode when non-empty; a core
            without coupon support just ignores the field. */}
        <div style={{ marginTop: "0.9rem" }}>
          <label htmlFor="upgrade-coupon" className="text-xs font-medium text-muted-foreground">
            {t("couponLabel")}
          </label>
          <Input
            id="upgrade-coupon"
            value={coupon}
            onChange={(e) => setCoupon(e.target.value)}
            placeholder={t("couponPlaceholder")}
            autoComplete="off"
            className="mt-1"
          />
        </div>
      </div>
    </div>,
    document.body
  );
}

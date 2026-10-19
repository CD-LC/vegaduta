"use client";

import { useEffect, useState } from "react";
import { Sparkles } from "lucide-react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { Button } from "./ui/button";
import { useTranslations } from "../lib/i18n";
import { isBillingEnabled } from "../lib/billing-flag";

// Event-triggered 7-day Premium trial button (PRICING-MONETIZATION-AUDIT
// 2026-07-31 §5.4 / B5): rendered inside existing upgrade-moment surfaces
// (WhatsApp connect gate, 24h edit-lock banners) at the moment of highest
// intent. One per tenant, no card - granted server-side via the existing
// comp-grant machinery through POST /api/tenant/trial/claim.
//
// Defensive posture (the backend contract is being added in a parallel
// session): GET /api/tenant/trial/status decides visibility, and ANY failure
// on that call - including a 404 from a core that doesn't have the route
// yet - renders nothing. The button can therefore be dropped into any gate
// today without a broken control appearing against an older backend.
// A 409 on claim (already claimed, raced from another tab) is surfaced as a
// plain error toast and the button hides itself.
export default function TrialOfferButton({
  trigger,
  onGranted,
  className,
}: {
  /** Which gate produced this offer - sent as the claim's `trigger` (e.g. "whatsapp-gate", "edit-lock"). */
  trigger: string;
  /** Called after a successful grant so the host screen can refresh its plan state. */
  onGranted?: () => void;
  className?: string;
}) {
  const t = useTranslations("trialOffer");
  const [eligible, setEligible] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // Billing off (lib/billing-flag.ts): a plan trial is a billing offer - never ask.
    if (!isBillingEnabled()) return undefined;
    let cancelled = false;
    api
      .getTrialStatus()
      .then((status) => {
        if (!cancelled) setEligible(!!status?.eligible && !status?.claimed);
      })
      .catch(() => {
        // 404 (endpoint not deployed yet), 401, network - all mean "no offer".
        if (!cancelled) setEligible(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!eligible || !isBillingEnabled()) return null;

  async function handleClaim() {
    setBusy(true);
    try {
      const result = await api.claimTrial(trigger);
      if (result?.granted) {
        toast.success(t("grantedToast"));
        setEligible(false);
        onGranted?.();
      } else {
        // Contract says non-granted comes back as 409, but tolerate a
        // 200-with-granted:false shape too.
        toast.error(t("notAvailableToast"));
        setEligible(false);
      }
    } catch (err: any) {
      // 409 = already claimed (raced elsewhere); anything else is a plain failure.
      toast.error(err?.status === 409 ? t("alreadyClaimedToast") : err?.message || t("claimErrorToast"));
      if (err?.status === 409) setEligible(false);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button type="button" size="sm" disabled={busy} onClick={handleClaim} className={className}>
      <Sparkles size={13} aria-hidden="true" />
      {busy ? t("startingButton") : t("startButton")}
    </Button>
  );
}

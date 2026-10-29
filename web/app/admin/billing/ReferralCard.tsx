"use client";

import { useCallback, useState } from "react";
import { Clipboard as ClipboardDocumentIcon, Gift as GiftIcon } from "lucide-react";
import { api } from "../../lib/api";
import type { TenantReferralResponse } from "../../lib/api";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";

/** D3 referral program ("give a month of Premium, get a month"). */
export default function ReferralCard({
  referral,
  onRedeemed,
}: {
  referral: TenantReferralResponse;
  onRedeemed: () => Promise<void> | void;
}) {
  const t = useTranslations("adminBillingPage");
  const x = useTranslations("adminBillingExtra");
  const [redeemCode, setRedeemCode] = useState("");
  const [redeeming, setRedeeming] = useState(false);

  const copyCode = useCallback(async () => {
    if (!referral.code) return;
    try {
      await navigator.clipboard.writeText(referral.code);
      toast.success(t("toastReferralCopied"));
    } catch {
      toast.error(t("toastClipboardError") + "\n" + referral.code);
    }
  }, [referral, t]);

  // redeemReferralCode surfaces the backend's 409 `reason` text directly in
  // err.message (no global toast), so the toast here is the one place the user
  // hears why it failed.
  const submitRedeem = useCallback(async () => {
    const code = redeemCode.trim();
    if (!code) return;
    setRedeeming(true);
    try {
      const res = await api.redeemReferralCode(code);
      if (res?.granted) {
        toast.success(t("referralRedeemedToast"));
        setRedeemCode("");
        await onRedeemed();
      } else {
        toast.error(t("referralRedeemFailedToast"));
      }
    } catch (err) {
      toast.error(errorMessage(err, t("referralRedeemFailedToast")));
    }
    setRedeeming(false);
  }, [redeemCode, onRedeemed, t]);

  return (
    <Card className="mb-4 gap-0 p-6">
      <div className="mb-1 flex items-center gap-2">
        <GiftIcon width={15} height={15} aria-hidden="true" />
        <h2 className="m-0 text-[0.95rem] font-semibold">{t("referralTitle")}</h2>
      </div>
      <p className="mb-4 mt-1 text-[0.8rem] text-muted-foreground">{t("referralHint")}</p>
      <div className="flex flex-wrap items-center gap-3">
        <code className="rounded-lg border border-border bg-secondary/40 px-3 py-1.5 font-mono text-[0.9rem] tracking-[0.08em]">
          {referral.code}
        </code>
        <Button type="button" variant="outline" size="sm" className="mb-0" onClick={copyCode}>
          <ClipboardDocumentIcon width={13} height={13} className="me-1" aria-hidden="true" />
          {t("referralCopyButton")}
        </Button>
        <span className="font-mono text-xs text-muted-foreground">
          {t("referralRedemptions", { count: referral.redemptionsCount ?? 0 })}
        </span>
      </div>
      {referral.redeemedByMe ? (
        <p className="mt-4 text-xs text-muted-foreground">{t("referralAlreadyRedeemed")}</p>
      ) : referral.canRedeem ? (
        <div className="mt-4">
          <label className="mb-1 block text-[11.5px] text-muted-foreground" htmlFor="referral-redeem">
            {t("referralRedeemLabel")}
          </label>
          <div className="flex flex-wrap gap-2">
            <Input
              id="referral-redeem"
              type="text"
              placeholder={t("referralRedeemPlaceholder")}
              value={redeemCode}
              onChange={(e) => setRedeemCode(e.target.value)}
              maxLength={64}
              className="max-w-[260px]"
              aria-describedby="referral-redeem-help"
            />
            <Button
              type="button"
              variant="outline"
              className="mb-0"
              onClick={submitRedeem}
              disabled={redeeming || !redeemCode.trim()}
            >
              {redeeming ? t("referralRedeemingButton") : t("referralRedeemButton")}
            </Button>
          </div>
          <p id="referral-redeem-help" className="mt-1.5 text-xs text-muted-foreground">
            {x("redeemHelp")}
          </p>
        </div>
      ) : null}
    </Card>
  );
}

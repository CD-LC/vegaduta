"use client";

import { useCallback, useState } from "react";
import { Receipt as ReceiptPercentIcon } from "lucide-react";
import { api } from "../../lib/api";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { GSTIN_PATTERN } from "./billingShared";

const LABEL = "mb-1 block text-[11.5px] text-muted-foreground";
const AREA =
  "flex w-full resize-y rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40";

/** D6 billing profile (GSTIN). The contract has no GET yet, so the form starts blank and PUT overwrites. */
export default function BillingProfileForm() {
  const t = useTranslations("adminBillingPage");
  const [gstin, setGstin] = useState("");
  const [billingName, setBillingName] = useState("");
  const [billingAddress, setBillingAddress] = useState("");
  const [saving, setSaving] = useState(false);

  // Live (as-you-type) GSTIN validity - empty is fine (field is optional).
  const typed = gstin.trim().toUpperCase();
  const looksInvalid = typed.length > 0 && !GSTIN_PATTERN.test(typed);

  const save = useCallback(async () => {
    const normalizedGstin = gstin.trim().toUpperCase();
    if (normalizedGstin && !GSTIN_PATTERN.test(normalizedGstin)) {
      toast.error(t("gstinInvalid"));
      return;
    }
    setSaving(true);
    try {
      await api.updateTenantBillingProfile({
        gstin: normalizedGstin,
        billingName: billingName.trim(),
        billingAddress: billingAddress.trim(),
      });
      setGstin(normalizedGstin);
      toast.success(t("billingProfileSaved"));
    } catch (err) {
      toast.error(errorMessage(err, t("billingProfileSaveError")));
    }
    setSaving(false);
  }, [gstin, billingName, billingAddress, t]);

  return (
    <div className="mt-5 border-t border-border pt-5">
      <div className="mb-1 flex items-center gap-2">
        <ReceiptPercentIcon width={15} height={15} aria-hidden="true" />
        <h3 className="m-0 text-[0.9rem] font-semibold">{t("billingProfileTitle")}</h3>
      </div>
      <p className="mb-3 mt-1 text-xs text-muted-foreground">{t("billingProfileHint")}</p>
      <div className="ax-bill-ticket-grid mb-3 grid gap-3">
        <div>
          <label className={LABEL} htmlFor="billing-gstin">
            {t("gstinLabel")}
          </label>
          <Input
            id="billing-gstin"
            type="text"
            placeholder={t("gstinPlaceholder")}
            value={gstin}
            onChange={(e) => setGstin(e.target.value)}
            maxLength={15}
            aria-invalid={looksInvalid || undefined}
            aria-describedby={looksInvalid ? "billing-gstin-error" : undefined}
            className="font-mono uppercase"
          />
          {looksInvalid && (
            <p id="billing-gstin-error" className="mt-1 text-[0.7rem] text-[var(--ax-red)]">
              {t("gstinInvalid")}
            </p>
          )}
        </div>
        <div>
          <label className={LABEL} htmlFor="billing-name">
            {t("billingNameLabel")}
          </label>
          <Input
            id="billing-name"
            type="text"
            placeholder={t("billingNamePlaceholder")}
            value={billingName}
            onChange={(e) => setBillingName(e.target.value)}
            maxLength={255}
          />
        </div>
      </div>
      <label className={LABEL} htmlFor="billing-address">
        {t("billingAddressLabel")}
      </label>
      <textarea
        id="billing-address"
        className={`${AREA} mb-3`}
        rows={2}
        placeholder={t("billingAddressPlaceholder")}
        value={billingAddress}
        onChange={(e) => setBillingAddress(e.target.value)}
      />
      <Button type="button" variant="outline" className="mb-0" onClick={save} disabled={saving || looksInvalid}>
        {saving ? t("savingBillingProfileButton") : t("saveBillingProfileButton")}
      </Button>
    </div>
  );
}

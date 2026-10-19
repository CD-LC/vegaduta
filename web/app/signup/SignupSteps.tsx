"use client";

// Signup launch steps (page.tsx): paid-tier payment and the free-workspace
// "ready" hand-off to sign-in.

import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Button } from "../components/ui/button";
import type { SignupResult } from "./signupModel";
import { ShakeError } from "./SignupWidgets";

export function PaymentStep({
  result,
  error,
  checkoutOpening,
  onCheckout,
  adminEmail,
}: {
  result: SignupResult;
  error: string | null;
  checkoutOpening: boolean;
  onCheckout: () => void;
  adminEmail: string;
}) {
  const t = useTranslations("signup");
  return (
    <div className="vd-auth__stack">
      <div className="vd-auth__notice" role="status">
        <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
        <span>
          <strong>{result.organizationName}</strong> {t("paymentSuccessBox")}
        </span>
      </div>

      <ShakeError message={error} />

      <Button size="lg" className="w-full" onClick={onCheckout} disabled={checkoutOpening}>
        {checkoutOpening ? t("loadingPaymentWindow") : t("continueToPayment")}
      </Button>

      {result.checkoutUrl && (
        <p className="vd-auth__summary">
          {t("paymentTrouble")}{" "}
          <a href={result.checkoutUrl} target="_blank" rel="noopener noreferrer">
            {t("openInNewTab")}
          </a>
          {t("paymentEmailedLink", { email: adminEmail })}
        </p>
      )}
    </div>
  );
}

export function FreeWorkspaceReadyStep({ result, onContinue }: { result: SignupResult; onContinue: () => void }) {
  const t = useTranslations("signup");
  return (
    <div className="vd-auth__stack">
      <div className="vd-auth__notice vd-auth__notice--success" role="status">
        <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
        <span>
          <strong>{result.organizationName}</strong> {t("freeReadySuccessBox")}
        </span>
      </div>
      {/* Sets the expectation the silent redirect never did: there IS one
          more sign-in, with the credentials just chosen - see the header
          comment on the removed auto-redirect. */}
      <p className="vd-auth__summary vd-auth__summary--left">{t("freeReadyNext")}</p>
      <Button size="lg" className="w-full" onClick={onContinue} autoFocus>
        {t("freeReadyContinue")}
      </Button>
      <p className="vd-auth__hint">{t("freeReadyContinueHint")}</p>
    </div>
  );
}

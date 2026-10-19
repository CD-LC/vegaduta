"use client";

import { CheckCircle2 } from "lucide-react";
import AuthBrandPanel from "../../components/AuthBrandPanel";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { useTranslations } from "../../lib/i18n";

// Landing spot after Razorpay's Standard
// Checkout widget's `handler` callback fires on /signup/page.tsx. Carries the
// login-redirect logic that used to live directly in /signup's own success
// screen (see that file's top comment for the full explanation of why this
// must be a plain full-page navigation to "/" rather than calling
// auth.init() directly on a signup-flow page).
//
// Deliberately does not itself verify the payment/subscription state - that
// authority lives server-side (Stage 4's webhook, which is what actually
// updates Tenant.subscriptionStatus once Razorpay confirms authorization).
// This page is a UX handoff only; if Stage 4's webhook hasn't fired yet by
// the time someone lands here, the trial still starts as soon as it does -
// nothing about landing on this page itself grants access.
//
// Auth-simplify pass (2026-09-24): same centred column, strip and glass card
// as /login and /signup (app/auth.css). This page no longer imports
// signup.css's forced-dark "nova" shell - it was the last importer, and the
// whole funnel is paper + glass in both modes now. Behavior (the single
// "log in now" navigation to "/") is untouched.
export default function SignupCompletePage() {
  const t = useTranslations("signup");
  return (
    <main className="auth-main vd-auth text-foreground antialiased">
      <div className="vd-auth__col">
        <AuthBrandPanel title={t("completeTitle")} />
        <Card className="auth-card vd-auth__card">
          <div className="vd-auth__notice vd-auth__notice--success" role="status">
            <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
            <span>{t("completeSuccessBox")}</span>
          </div>
          <Button size="lg" className="w-full" onClick={() => { window.location.href = "/"; }}>
            {t("completeLogInNow")}
          </Button>
        </Card>
      </div>
    </main>
  );
}

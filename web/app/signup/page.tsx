"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { motion } from "motion/react";
import { ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import { auth } from "../lib/auth";
import { useAuthedHint } from "../lib/hooks/useAuthedHint";
import { useTranslations } from "../lib/i18n";
import AuthBrandPanel from "../components/AuthBrandPanel";
import LanguageSwitcher from "../components/LanguageSwitcher";
import AuthSwitcher from "../components/signup/AuthSwitcher";
import { Card } from "../components/ui/card";
import ThemePicker from "../components/ThemePicker";
import { FREE_PLAN_FACTS } from "../components/FreePlanIncludes";
import { FREE_MODE } from "../lib/freeMode";
import { sendLandingEvent } from "../components/demo-landing/landingBeacon";
import { isBillingEnabled } from "../lib/billing-flag";
import { errorMessage } from "../lib/errors";
import BrandText from "../components/BrandText";
import { StepIndicator } from "./SignupWidgets";
import { IntroStep } from "./IntroStep";
import { DetailsForm } from "./DetailsForm";
import { FreeWorkspaceReadyStep, PaymentStep } from "./SignupSteps";

// Self-service tenant signup on canonical shadcn/ui primitives (Card,
// Button, Input, Label). Auth-simplify pass (2026-09-24): one centred column
// (brand strip -> glass card -> switch line), no side brand panel - see
// AuthBrandPanel.tsx's header for the maintainer feedback and the reference pages;
// layout/styling lives in app/auth.css (.vd-auth*).
//
// Flow logic - the multi-step wizard, POST /api/signup provisioning, the
// FREE-tier → Keycloak hand-off (auth.login with prompt:"login" + loginHint),
// the still-present paid-tier Razorpay path, and all validation - is
// preserved from the pre-shadcn version, with ONE deliberate change (funnel
// audit, 2026-09): the FREE-tier hand-off is no longer a silent 1.2s timer.
// It used to fire auth.login(prompt:"login") on its own, so the person was
// bounced to a password prompt for the password they had typed ten seconds
// earlier, with no warning. FreeWorkspaceReadyStep now says what happens
// next and carries an explicit "Continue to sign-in" button. (Removing the
// second sign-in altogether needs core to return a session or do a
// direct-grant exchange from POST /api/signup - out of this repo's web/
// scope; see the note on continueToSignIn below.)

// Loads Razorpay's Standard Checkout script on demand — from Razorpay's own
// CDN (the widget collects card/UPI details; nothing here touches raw payment
// details). Cached on window so re-opening never re-fetches.
function loadRazorpayCheckoutScript() {
  if (typeof window === "undefined") return Promise.resolve(false);
  if (window.Razorpay) return Promise.resolve(true);
  return new Promise((resolve) => {
    const script = document.createElement("script");
    script.src = "https://checkout.razorpay.com/v1/checkout.js";
    script.onload = () => resolve(true);
    script.onerror = () => resolve(false);
    document.body.appendChild(script);
  });
}

export default function SignupPage() {
  const t = useTranslations("signup");
  const authedHint = useAuthedHint();
  const [mode, setMode] = useState("signup"); // "signup" | "signin"
  const [step, setStep] = useState("intro"); // "intro" -> "details" -> (result)
  const experienceMode = "SIMPLE";
  const [orgName, setOrgName] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [adminEmail, setAdminEmail] = useState("");
  const [adminPassword, setAdminPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [newsOptIn, setNewsOptIn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null); // SignupResponse once POST /api/signup succeeds
  const [checkoutOpening, setCheckoutOpening] = useState(false);
  const [launching, setLaunching] = useState(false);

  // Landing hand-off (2026-09-17). The hero's email starter links here as
  // /signup?email=<addr>; the sandbox and every "keep my agent" link as
  // /signup?demo=<token>. Either one means the visitor has ALREADY read the
  // pitch, so the intro step (a second copy of the free-plan facts) is a
  // screen between them and the form. Skip it, prefill the address, and
  // count the view — signup_view / signup_submit / signup_done are the
  // three counters the landing funnel never had (every landing event
  // stopped at the click to /signup). Fired once per mount.
  useEffect(() => {
    try {
      const params = new URLSearchParams(window.location.search);
      const prefill = params.get("email");
      if (prefill && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(prefill)) {
        setAdminEmail(prefill);
        setStep("details");
      } else if (params.get("demo")) {
        setStep("details");
      }
    } catch {
      /* URL access blocked — start at the intro as before */
    }
    sendLandingEvent("signup_view");
  }, []);

  // Preload the checkout script as soon as we might need it.
  useEffect(() => {
    if (result) {
      void loadRazorpayCheckoutScript();
    }
  }, [result]);

  // Launch flash fires exactly once, only on a real POST /api/signup success.
  useEffect(() => {
    if (!result) return undefined;
    setLaunching(true);
    const id = setTimeout(() => setLaunching(false), 1200);
    return () => clearTimeout(id);
  }, [result]);

  // FREE-tier signup (the only kind produced today) has nothing to check out —
  // hand off to Keycloak via "/". prompt:"login" forces a fresh auth instead
  // of silently reusing a stale SSO session; loginHint pre-fills the
  // just-registered email so the only thing left to type is the password.
  // Fired from FreeWorkspaceReadyStep's explicit button, not a timer (see the
  // header comment). Backend follow-up, out of web/ scope: have POST
  // /api/signup establish a Keycloak session (or do a direct-grant exchange)
  // so this redirect lands already authenticated and the step disappears.
  function continueToSignIn() {
    void auth.login({ redirectUri: `${window.location.origin}/`, loginHint: adminEmail, prompt: "login" });
  }

  function handleSignInRedirect() {
    void auth.login({ redirectUri: `${window.location.origin}/` });
  }

  function openCheckout() {
    setError(null);
    setCheckoutOpening(true);
    void loadRazorpayCheckoutScript().then((loaded) => {
      setCheckoutOpening(false);
      if (!loaded || !window.Razorpay) {
        setError(t("errorPaymentWindowLoad"));
        return;
      }
      const razorpay = new window.Razorpay({
        key: result.gatewayPublicKey,
        subscription_id: result.gatewaySubscriptionId,
        name: "VegaDūta",
        description: t("razorpayPlanDescription", { organizationName: result.organizationName }),
        prefill: { name: `${firstName} ${lastName}`.trim(), email: adminEmail },
        handler: () => {
          void auth.login({ redirectUri: `${window.location.origin}/`, loginHint: adminEmail, prompt: "login" });
        },
        modal: {
          ondismiss: () => {
            setError(t("errorPaymentWindowClosed"));
          },
        },
        theme: { color: "#8b83f8" },
      });
      razorpay.open();
    });
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);

    if (!agreedToTerms) {
      setError(t("errorConsentRequired"));
      return;
    }
    if (adminPassword !== confirmPassword) {
      setError(t("errorPasswordsDontMatch"));
      return;
    }
    // 12 to match Keycloak's realm password policy (2026-10-09); a lower bound here
    // let a password through the form only for Keycloak to reject it mid-signup.
    if (adminPassword.length < 12) {
      setError(t("errorPasswordTooShort"));
      return;
    }

    setBusy(true);
    sendLandingEvent("signup_submit");
    try {
      // Demo→real conversion seam: /try links here with ?demo=<token>, and its
      // one-tap social buttons also stash the token in localStorage under
      // "vd_demo_token" (see components/try/conversion.tsx) in case the query
      // param got lost along the way. Passing demoToken lets the backend link
      // the anonymous sandbox to this new tenant — that link IS the conversion
      // metric. Absent for ordinary signups; older backends ignore the field.
      let demoToken = null;
      try {
        demoToken =
          new URLSearchParams(window.location.search).get("demo") || window.localStorage.getItem("vd_demo_token");
      } catch {
        /* storage/URL access blocked — proceed without the link */
      }
      const response = await api.signup({
        orgName,
        firstName,
        lastName,
        adminEmail,
        adminPassword,
        experienceMode,
        newsOptIn,
        ...(demoToken ? { demoToken } : {}),
      });
      // Conversion recorded (or attempted) — clear the carry key so a later,
      // unrelated signup/onboarding doesn't re-report it. The sandbox's own
      // resume key (vegaduta.demoToken) is deliberately left alone.
      try {
        window.localStorage.removeItem("vd_demo_token");
      } catch {
        /* ignore */
      }
      setResult(response);
      sendLandingEvent("signup_done");
    } catch (err) {
      setError(errorMessage(err, t("errorSignupFailed")));
    } finally {
      setBusy(false);
    }
  }

  const viewKey = result ? "result" : step;

  // The page strip carries the active step's own title + one sentence, so
  // there is exactly one <h1> on the route at every width (the old
  // md:hidden role="heading" mirror existed only because the h1 lived in a
  // display:none brand panel below 768px).
  const strip = result
    ? result.gatewaySubscriptionId
      ? {
          title: (
            <>
              {t("paymentHeadline")} <span className="vd-auth__accent">{t("paymentAccent")}</span>
            </>
          ),
          subtitle: null,
        }
      : {
          title: (
            <>
              {t("freeReadyHeadline")} <span className="vd-auth__accent">{t("freeReadyAccent")}</span>
            </>
          ),
          subtitle: null,
        }
    : step === "intro"
      ? {
          title: (
            <>
              {t("headline")} <span className="vd-auth__accent">{t("accent")}</span>
            </>
          ),
          // Numbers come from FREE_PLAN_FACTS so this sentence can't drift
          // from what the plan actually enforces.
          subtitle: t(FREE_MODE ? "subtitleFreeMode" : "subtitle", FREE_PLAN_FACTS),
        }
      : {
          title: (
            <>
              {t("detailsHeadline")} <span className="vd-auth__accent">{t("detailsAccent")}</span>
            </>
          ),
          subtitle: t("detailsHint"),
        };

  return (
    <main className="auth-main vd-auth text-foreground antialiased">
      {/* One top-right cluster for both controls (they used to overlap: the
          theme picker was fixed top-right and the language switcher sat
          absolute top-right of the form column below md). */}
      <div className="vd-auth__controls">
        <LanguageSwitcher />
        <ThemePicker />
      </div>

      <div className="vd-auth__col">
        <AuthBrandPanel
          eyebrow={step === "intro" && !result ? t("eyebrow") : undefined}
          title={strip.title}
          subtitle={strip.subtitle}
        >
          {!result && (
            <div className="vd-auth__switcher">
              <AuthSwitcher
                mode={mode}
                onChange={(next) => (next === "signin" ? handleSignInRedirect() : setMode(next))}
              />
            </div>
          )}
        </AuthBrandPanel>

        <Card className="auth-card vd-auth__card">
          {/* vd_authed hint: a signed-in tenant who lands on /signup (a stale
              link, a "Start free" on a public page) is told so and given the
              way back to their console, rather than silently being offered a
              second workspace they don't need. Dismissible-by-navigation:
              the form below stays usable for anyone deliberately creating a
              second tenant. */}
          {authedHint && !result && (
            <p className="vd-auth__switch" style={{ marginTop: 0 }}>
              {t("alreadySignedIn")}{" "}
              <Link href="/">
                <BrandText text={t("openConsole")} />
              </Link>
            </p>
          )}
          <StepIndicator active={result ? 3 : step === "details" ? 2 : 1} />

          {/* Keyed remount (not AnimatePresence mode="wait") so each step
              plays its enter animation without a hanging exit blocking the
              next step from mounting. */}
          <motion.div
            key={viewKey}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
          >
            {result ? (
              result.gatewaySubscriptionId ? (
                <PaymentStep
                  result={result}
                  error={error}
                  checkoutOpening={checkoutOpening}
                  onCheckout={openCheckout}
                  adminEmail={adminEmail}
                />
              ) : (
                <FreeWorkspaceReadyStep result={result} onContinue={continueToSignIn} />
              )
            ) : step === "intro" ? (
              <IntroStep onContinue={() => setStep("details")} />
            ) : (
              <DetailsForm
                orgName={orgName}
                setOrgName={setOrgName}
                firstName={firstName}
                setFirstName={setFirstName}
                lastName={lastName}
                setLastName={setLastName}
                adminEmail={adminEmail}
                setAdminEmail={setAdminEmail}
                adminPassword={adminPassword}
                setAdminPassword={setAdminPassword}
                confirmPassword={confirmPassword}
                setConfirmPassword={setConfirmPassword}
                agreedToTerms={agreedToTerms}
                setAgreedToTerms={setAgreedToTerms}
                newsOptIn={newsOptIn}
                setNewsOptIn={setNewsOptIn}
                busy={busy}
                error={error}
                onSubmit={handleSubmit}
              />
            )}
          </motion.div>
        </Card>

        {/* Under the card, the standard switch line: back (details step only),
            "already have a workspace? sign in", pricing. */}
        {!result && (
          <p className="vd-auth__switch">
            {step === "details" && (
              <>
                <button type="button" onClick={() => setStep("intro")}>
                  {t("back")}
                </button>
                <span aria-hidden="true">·</span>
              </>
            )}
            <span>
              {t("alreadyHaveWorkspace")}{" "}
              <button type="button" onClick={handleSignInRedirect}>
                {t("signInInstead")}
              </button>
            </span>
            {isBillingEnabled() && (
              <>
                <span aria-hidden="true">·</span>
                <Link href="/billing">{t("viewPricing")}</Link>
              </>
            )}
          </p>
        )}

        <p className="vd-auth__trust">
          <ShieldCheck className="size-3.5" aria-hidden="true" />
          {t("trustLine")}
        </p>
      </div>

      {/* Launch payoff - fires only on a genuine signup success. */}
      <div
        className={`pointer-events-none fixed inset-0 z-50 transition-opacity duration-500 ${launching ? "opacity-100" : "opacity-0"}`}
        aria-hidden="true"
      >
        <div className="absolute left-1/2 top-1/2 size-40 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary/30 blur-3xl" />
      </div>
    </main>
  );
}

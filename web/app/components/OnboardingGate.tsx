"use client";

import { useState } from "react";
import type { FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "motion/react";
import BrandName from "./BrandName";
import { Building2, ArrowRight, LogOut } from "lucide-react";
import { auth } from "../lib/auth";
import { api } from "../lib/api";
import AnimatedBrandMark from "./AnimatedBrandMark";
import { Alert } from "./ui/alert";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { Input } from "./ui/input";
import { Label } from "./ui/label";

// Shown by AuthProvider when a user is authenticated but has NO tenant — the
// social-login (e.g. Google) path, where Keycloak's IdP first-login created the
// account without running signup. Instead of dropping them into a broken,
// tenant-less dashboard, this one-time step collects an organization name and
// provisions their tenant (POST /api/onboarding), then force-refreshes the
// token so the new tenant_id claim lands and hands control back to AuthProvider
// (onProvisioned) for the real dashboard.
//
// That last step used to be `window.location.reload()`, and it was the FIRST
// half of the reported "after creating the organization it throws me out to the
// landing page". The reload discarded the perfectly good in-memory keycloak-js
// session and rebuilt it from scratch through check-sso / prompt=none — and
// Keycloak answers prompt=none with `error=interaction_required` for any user
// with a pending required action (this realm runs VERIFY_PROFILE and marks
// email/firstName/lastName required in its declarative User Profile,
// re-evaluated at EVERY authentication). keycloak-js 25.0.6 reports that as
// plain "not authenticated" (see lib/auth.ts's SilentAuthError comment), so the
// reload landed on the marketing landing page with a live session and no way
// forward. Nothing here needs a reload: the refreshed token is already in
// memory the moment refreshToken() resolves.
export default function OnboardingGate({
  firstName,
  onProvisioned,
}: {
  firstName?: string | null;
  onProvisioned?: () => void;
}) {
  const router = useRouter();
  const [orgName, setOrgName] = useState("");
  const [agreed, setAgreed] = useState(false);
  const [newsOptIn, setNewsOptIn] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Set when the tenant WAS provisioned but this browser's token could not be
  // brought up to date silently — the only honest way out of that is an
  // interactive trip to Keycloak, offered with an explanation rather than fired
  // behind the person's back. Never a silent prompt=none retry: that is the
  // exact dead end this whole change removes.
  const [needsInteractiveSignIn, setNeedsInteractiveSignIn] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!orgName.trim()) {
      setError("Please enter your organization name.");
      return;
    }
    if (!agreed) {
      setError("Please accept the Terms of Service and Privacy Policy to continue.");
      return;
    }
    setSubmitting(true);
    try {
      // Demo→real conversion seam: /try's one-tap social register stashes the
      // sandbox token under "vd_demo_token" right before the OAuth redirect
      // (see components/try/conversion.tsx). If it's there, this user arrived
      // from the anonymous sandbox — pass it along so the backend can link the
      // demo tenant to this brand-new real one (that link IS the conversion
      // metric). Absent for everyone else; older backends ignore the field.
      let demoToken: string | null = null;
      try {
        demoToken = window.localStorage.getItem("vd_demo_token");
      } catch {
        /* storage blocked — proceed without the link */
      }
      await api.onboarding({
        orgName: orgName.trim(),
        newsOptIn,
        ...(demoToken ? { demoToken } : {}),
      });
      // Conversion recorded (or attempted) — the carry key has done its job.
      // The sandbox's own resume key (vegaduta.demoToken) is left alone.
      try {
        window.localStorage.removeItem("vd_demo_token");
      } catch {
        /* ignore */
      }
      // Provisioning set the tenant_id attribute on this user; force a fresh
      // token so the claim is present. refreshToken() resolves TRUE only when
      // the session is genuinely still alive (see its javadoc), and the claim
      // itself is the thing AuthProvider's gate actually reads — so check both
      // rather than assuming the refresh did what we wanted.
      const refreshed = await auth.refreshToken();
      if (refreshed && auth.getTenantId()) {
        // Hand control back to AuthProvider in place: it re-reads the fresh
        // claims (tenant AND roles — provisioning grants tenant-admin) and its
        // tenant gate lifts, no page reload and no Keycloak round-trip.
        onProvisioned?.();
        // "/" is the console dashboard, and it is also the single source of
        // truth for where an admin belongs: app/page.tsx does
        // `if (isAdmin) router.replace("/admin")` off the very same
        // useAuth().isAdmin AuthProvider derives from ADMIN_ROLE. Sending
        // everyone to "/" therefore lands a tenant-admin on /admin without
        // duplicating that rule here (where `roles` isn't even in scope).
        router.replace("/");
        return;
      }
      // Tenant exists, token doesn't reflect it. Ask for an interactive
      // sign-in instead of guessing — see needsInteractiveSignIn above.
      setNeedsInteractiveSignIn(true);
      setSubmitting(false);
    } catch (err: any) {
      setError(err?.message || "We couldn't create your organization. Please try again.");
      setSubmitting(false);
    }
  }

  // A real, visible, top-level trip to Keycloak — the opposite of the silent
  // prompt=none path that produced this bug. `prompt: "login"` guarantees an
  // interactive authentication (and Keycloak runs any pending required actions,
  // e.g. VERIFY_PROFILE, on the way through) instead of an answer this app
  // cannot act on; loginHint pre-fills the account they already have so the
  // extra step is one click plus a password, not a "who are you?". Returning to
  // the origin root lands them on the dashboard, which routes an admin on to
  // /admin itself (see handleSubmit).
  function startInteractiveSignIn() {
    const hint = auth.getUsername();
    void auth.login({
      redirectUri: window.location.origin + "/",
      prompt: "login",
      ...(hint ? { loginHint: hint } : {}),
    });
  }

  // The organization EXISTS at this point — only this browser's token is behind.
  // Own screen (rather than a branch inside the form below) so the form's own
  // state can't be resubmitted and create a second organization, and so the copy
  // can say plainly what happened before sending anyone back to Keycloak.
  if (needsInteractiveSignIn) {
    return (
      <main className="flex min-h-screen w-full items-center justify-center bg-background px-5 py-10 text-foreground antialiased">
        <motion.div
          className="w-full max-w-md"
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
        >
          <div className="mb-6 flex items-center justify-center gap-2.5">
            <AnimatedBrandMark size={30} />
            <span className="brand-name text-lg font-semibold tracking-tight">
              Vega<span className="text-primary">Dūta</span>
            </span>
          </div>

          <div className="mb-6 text-center">
            <h1 className="text-2xl font-semibold tracking-tight">Your organization is ready</h1>
            <p className="mt-1.5 text-sm text-muted-foreground">
              We couldn&apos;t finish updating this browser&apos;s session, so there&apos;s one last sign-in step — then
              you&apos;ll go straight to your new workspace.
            </p>
          </div>

          <Card className="gap-0 border-border/80 bg-card/80 py-6 shadow-2xl backdrop-blur-xl">
            <div className="space-y-5 px-6">
              <p className="text-sm text-muted-foreground">
                Nothing is lost — <strong className="text-foreground">{orgName.trim()}</strong> already exists. Signing
                in again just re-issues your session so it carries it.
              </p>
              <Button size="lg" className="w-full" onClick={startInteractiveSignIn}>
                Continue to sign in <ArrowRight aria-hidden="true" className="size-4" />
              </Button>
            </div>
          </Card>

          <button
            type="button"
            onClick={() => void auth.logout()}
            className="mx-auto mt-6 flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            <LogOut aria-hidden="true" className="size-3.5" /> Sign out
          </button>
        </motion.div>
      </main>
    );
  }

  return (
    <main className="flex min-h-screen w-full items-center justify-center bg-background px-5 py-10 text-foreground antialiased">
      <motion.div
        className="w-full max-w-md"
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
      >
        <div className="mb-6 flex items-center justify-center gap-2.5">
          <AnimatedBrandMark size={30} />
          <span className="brand-name text-lg font-semibold tracking-tight">
            Vega<span className="text-primary">Dūta</span>
          </span>
        </div>

        <div className="mb-6 text-center">
          <h1 className="text-2xl font-semibold tracking-tight">
            {firstName ? `Welcome, ${firstName}!` : "Almost there!"}
          </h1>
          <p className="mt-1.5 text-sm text-muted-foreground">
            One quick step — name your organization to finish setting up your workspace.
          </p>
        </div>

        <Card className="gap-0 border-border/80 bg-card/80 py-6 shadow-2xl backdrop-blur-xl">
          <form onSubmit={handleSubmit} className="space-y-5 px-6">
            <div className="space-y-2">
              <Label htmlFor="org-name">Organization name</Label>
              <div className="relative">
                <Building2
                  aria-hidden="true"
                  className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                />
                <Input
                  id="org-name"
                  placeholder="e.g. Sharma General Store"
                  value={orgName}
                  onChange={(e) => setOrgName(e.target.value)}
                  className="pl-9"
                  autoFocus
                  maxLength={255}
                />
              </div>
            </div>

            <label className="flex items-start gap-2.5 text-sm text-muted-foreground">
              <input
                type="checkbox"
                checked={agreed}
                onChange={(e) => setAgreed(e.target.checked)}
                className="mt-0.5 size-4 shrink-0 accent-[var(--primary)]"
              />
              <span>
                I agree to the{" "}
                <Link href="/terms" className="text-primary hover:underline">
                  Terms of Service
                </Link>{" "}
                and{" "}
                <Link href="/privacy" className="text-primary hover:underline">
                  Privacy Policy
                </Link>
                .
              </span>
            </label>

            <label className="flex items-start gap-2.5 text-sm text-muted-foreground">
              <input
                type="checkbox"
                checked={newsOptIn}
                onChange={(e) => setNewsOptIn(e.target.checked)}
                className="mt-0.5 size-4 shrink-0 accent-[var(--primary)]"
              />
              <span>
                Send me product updates &amp; news about <BrandName />.
              </span>
            </label>

            {error && <Alert variant="destructive">{error}</Alert>}

            <Button type="submit" size="lg" className="w-full" disabled={submitting}>
              {submitting ? (
                "Creating your workspace…"
              ) : (
                <>
                  Create organization <ArrowRight aria-hidden="true" className="size-4" />
                </>
              )}
            </Button>
          </form>
        </Card>

        <button
          type="button"
          onClick={() => void auth.logout()}
          className="mx-auto mt-6 flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          <LogOut aria-hidden="true" className="size-3.5" /> Sign out
        </button>
      </motion.div>
    </main>
  );
}

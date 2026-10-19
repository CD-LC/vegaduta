import LoginLanding from "../components/LoginLanding";
import LocalLogin from "../components/LocalLogin";
import { isLocalAuth } from "../lib/authMode";

// AXON "Log In" screen - public route (see AuthProvider.js's PUBLIC_ROUTES),
// pixel-matched to Log In Pro.dc.html. See LoginLanding.js for why the
// email/password form doesn't check credentials itself.
export const metadata = {
  title: "Sign in — VegaDūta",
  description: "Sign in to your VegaDūta account to manage your AI agents, workflows and channels.",
  alternates: { canonical: "/login" },
  // openGraph added (SEO audit, 2026-09-09) to match signup/layout.tsx: /login
  // is in sitemap.xml and gets shared in support replies and onboarding
  // emails, and without its own OG block every unfurl fell back to the root
  // layout's "AI Staff for Your Business" card — a marketing card on a
  // sign-in link reads as the wrong page.
  openGraph: {
    title: "Sign in — VegaDūta",
    description: "Sign in to manage your AI agents, workflows and channels.",
    images: ["/og-image.png"],
  },
};

// Local auth mode (NEXT_PUBLIC_AUTH_MODE=local) swaps the SSO-first screen for the email +
// password form; the default Keycloak screen is rendered exactly as before. The mode is runtime
// public config (lib/env.ts), so this must be evaluated per request, not at build time.
export const dynamic = "force-dynamic";

export default function LoginPage() {
  return isLocalAuth() ? <LocalLogin /> : <LoginLanding />;
}

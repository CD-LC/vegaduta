import Image from "next/image";
import Link from "next/link";
import { ENTITY_FACTS } from "../lib/platform-facts";

export const metadata = {
  title: "Privacy Policy — VegaDūta",
  description:
    "How VegaDūta collects, uses, stores and protects your data — account details, agent/workflow content, AI processing, retention and your rights.",
  alternates: { canonical: "/privacy" },
  openGraph: {
    title: "Privacy Policy — VegaDūta",
    description:
      "How VegaDūta collects, uses, stores and protects your data — account details, agent/workflow content, AI processing, retention and your rights.",
    images: ["/og-image.png"],
  },
};

// Baseline Privacy Policy. Public route (see PUBLIC_ROUTES in lib/auth.ts).
// Reflects the platform's real posture (encryption at rest, tenant isolation,
// AI/model processing, data export/erasure). Review/adapt with legal counsel
// (incl. India DPDP Act obligations) before relying on it in production.
const SECTIONS: { h: string; p: string }[] = [
  { h: "1. What we collect", p: "Account details you provide (name, email, organisation), the content and configuration of your agents and workflows, the messages your agents exchange with your customers, and usage/technical logs needed to run and secure the Service." },
  { h: "2. How we use it", p: "To provide and operate the Service, run your AI agents, process payments, secure the platform, provide support, and — only if you opted in — send you product updates and news. We do not sell your personal data." },
  { h: "3. AI & third-party processing", p: "To generate agent responses and speech in Indian languages, message content may be processed by AI/model providers (e.g. Sarvam and other LLM providers) and by the messaging channels you connect (WhatsApp, Telegram) and your payment provider. These are processed to deliver features you enable." },
  { h: "4. Storage & security", p: "Data is stored per-tenant in isolated database schemas. Provider keys and channel secrets are encrypted at rest. Access is authenticated via Keycloak (OAuth2/OIDC with PKCE) and served over HTTPS. See the Security section on our homepage." },
  { h: "5. Email & communications", p: "We send transactional email (verification, password reset, account notices). Marketing/news email is sent only to accounts that opted in, and you can opt out at any time." },
  { h: "6. Your rights", p: "You can access and update your information in-product, export your tenant data, and request erasure of your account and data. Contact us to exercise these rights." },
  { h: "7. Retention", p: "We retain data for as long as your account is active or as needed to provide the Service and meet legal obligations. On account deletion we remove your tenant data as described in our data-erasure process." },
  { h: "8. Cookies", p: "We use essential cookies/local storage for authentication and preferences (such as your language). We keep non-essential tracking to a minimum." },
  // 9-11 added 2026-09-29 for the mobile apps (docs/plans/STORE-COMPLIANCE-CONTRACT.md).
  // Section 9 mirrors the in-app rationale in mobile-app/android/.../sentinel/HealthRationaleActivity.kt
  // and section 10 the one-time consent sheet (contract section 3) - keep them in step.
  { h: "9. Health data (VegaDūta Sentinels)", p: "Sentinels in the VegaDūta mobile app read only the kinds of health data you choose when you add a sentinel (for example heart rate, sleep, steps or blood pressure), read-only, to compare your readings with your own usual range, alert you when something changes and write a short daily brief. This data is stored in an app-private database on your phone, and summaries are written by an on-device model or a fixed template, so no health data is sent to any AI service. Sync is off by default: only if you turn it on for a sentinel and your workspace admin allows it are that sentinel's findings uploaded to your own VegaDūta workspace, and raw readings only if you also choose that. Caregiver alerts go only to people you enter yourself, and only after you miss an \"Are you OK?\" check-in. Health data is never used for advertising, sold, or used to train models. You can pause all agents, delete a sentinel, or erase all sentinel data at any time, and revoke access in Health Connect. Sentinels is a wellness tool, not a medical device, and does not diagnose or treat any condition." },
  { h: "10. Third-party AI providers", p: "Messages you send to cloud agents (chat, quick tasks and cloud assist, on the web and in the mobile apps) are processed by third-party AI model providers (for example OpenAI, Anthropic and Google) selected by your workspace, under contract, to generate replies. The mobile apps ask for your agreement before the first cloud request, you can change that choice in Settings, and on-device mode keeps everything on your phone. Health data from Sentinels is never included unless you paste it yourself." },
  { h: "11. Deleting your account", p: "You can request deletion of your account by contacting us, or from within the mobile app where your deployment offers it. If you are the only person in your workspace, the whole workspace is deleted; in a shared workspace, your account and the data attributable to you are deleted and content owned by the organisation stays with it. See /account-deletion for the full details." },
];

export default function PrivacyPage() {
  return (
    <main className="vd-pub min-h-screen w-full bg-background text-foreground">
      <div className="mx-auto max-w-3xl px-5 py-16 sm:px-8">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          <Image src="/logo-mark-sm.webp" alt="" height={32} width={44} className="h-8 w-auto object-contain" style={{ height: 32, width: "auto" }} draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>
        <h1 className="mt-8 text-3xl font-semibold tracking-tight">Privacy Policy</h1>
        <p className="mt-2 text-sm text-muted-foreground">Last updated: 29 September 2026</p>
        <div className="mt-8 space-y-6">
          {SECTIONS.map((s) => (
            <section key={s.h}>
              <h2 className="text-lg font-semibold">{s.h}</h2>
              <p className="mt-1.5 text-pretty text-muted-foreground">{s.p}</p>
            </section>
          ))}
          <section>
            <h2 className="text-lg font-semibold">12. Contact</h2>
            <p className="mt-1.5 text-muted-foreground">
              Privacy questions or requests? Email{" "}
              <a href={`mailto:${ENTITY_FACTS.contactEmail}`} className="text-primary hover:underline">{ENTITY_FACTS.contactEmail}</a>.
            </p>
          </section>
        </div>
        <div className="mt-12 text-sm">
          <Link href="/terms" className="text-primary hover:underline">Terms of Service</Link>
          <span className="mx-2 text-muted-foreground">·</span>
          <Link href="/signup" className="text-primary hover:underline">Back to sign up</Link>
        </div>
      </div>
    </main>
  );
}

import Link from "next/link";
import { ENTITY_FACTS } from "../lib/platform-facts";

export const metadata = {
  title: "Terms of Service — VegaDūta",
  description:
    "The terms governing your use of VegaDūta: accounts, acceptable use, plans and billing, your content and data, availability, and liability.",
  alternates: { canonical: "/terms" },
  openGraph: {
    title: "Terms of Service — VegaDūta",
    description:
      "The terms governing your use of VegaDūta: accounts, acceptable use, plans and billing, your content and data, availability, and liability.",
    images: ["/og-image.png"],
  },
};

// Baseline Terms of Service. Public route (see PUBLIC_ROUTES in lib/auth.ts).
// This is a sensible starting draft for an Indian SMB AI-agent SaaS — review
// and adapt with legal counsel before relying on it in production.
const SECTIONS: { h: string; p: string }[] = [
  {
    h: "1. Acceptance",
    p: "By creating an account or using VegaDūta (the “Service”), you agree to these Terms of Service and our Privacy Policy. If you use the Service on behalf of a business, you confirm you are authorised to bind that business.",
  },
  {
    h: "2. The Service",
    p: "VegaDūta lets you build and operate AI agents that communicate with your customers across channels such as WhatsApp, voice calls and Telegram, in multiple Indian languages, and automate related workflows and payments.",
  },
  {
    h: "3. Accounts",
    p: "You are responsible for the accuracy of your registration details, for verifying your email, and for keeping your credentials secure. You are responsible for all activity under your account and your agents.",
  },
  {
    h: "4. Acceptable use",
    p: "You will not use the Service to send spam or unlawful content, to violate the rights of others, to bypass platform limits or security, or in a way that breaches WhatsApp/Telegram or payment-provider policies or applicable law.",
  },
  {
    h: "5. Plans, limits & billing",
    p: "Free and paid plans carry usage limits (agents, workflows, team members, knowledge bases, etc.). Paid plans are billed through our payment provider. Fees are exclusive of taxes unless stated. You can change or cancel your plan from your account settings. Refunds and cancellation are governed by our Refund & Cancellation Policy (/refund-policy).",
  },
  {
    h: "6. Your content & data",
    p: "You retain ownership of the content and data you provide. You grant us the limited rights needed to operate the Service for you (including processing messages through AI/model providers). See our Privacy Policy for details.",
  },
  {
    h: "7. Availability & disclaimers",
    p: "We work to keep the Service reliable but provide it “as is” without warranties of uninterrupted or error-free operation. AI outputs may be imperfect; you are responsible for how you use them with your customers.",
  },
  {
    h: "8. Limitation of liability",
    p: "To the extent permitted by law, VegaDūta is not liable for indirect or consequential losses, and our total liability is limited to the fees you paid for the Service in the preceding 12 months.",
  },
  {
    h: "9. Termination",
    p: "You may stop using the Service at any time. We may suspend or terminate accounts that breach these Terms. On termination you may export your data as described in the Privacy Policy.",
  },
  {
    h: "10. Changes",
    p: "We may update these Terms; material changes will be notified in-product or by email. Continued use after changes take effect means you accept them.",
  },
];

export default function TermsPage() {
  return (
    <main className="vd-pub min-h-screen w-full bg-background text-foreground">
      <div className="mx-auto max-w-3xl px-5 py-16 sm:px-8">
        <Link href="/" className="brand-name text-lg font-semibold tracking-tight">
          Vega<span className="text-primary">Dūta</span>
        </Link>
        <h1 className="mt-8 text-3xl font-semibold tracking-tight">Terms of Service</h1>
        <p className="mt-2 text-sm text-muted-foreground">Last updated: 25 July 2026</p>
        <div className="mt-8 space-y-6">
          {SECTIONS.map((s) => (
            <section key={s.h}>
              <h2 className="text-lg font-semibold">{s.h}</h2>
              <p className="mt-1.5 text-pretty text-muted-foreground">{s.p}</p>
            </section>
          ))}
          <section>
            <h2 className="text-lg font-semibold">11. Contact</h2>
            <p className="mt-1.5 text-muted-foreground">
              Questions about these Terms? Email{" "}
              <a href={`mailto:${ENTITY_FACTS.contactEmail}`} className="text-primary hover:underline">
                {ENTITY_FACTS.contactEmail}
              </a>
              .
            </p>
          </section>
        </div>
        <div className="mt-12 text-sm">
          <Link href="/privacy" className="text-primary hover:underline">
            Privacy Policy
          </Link>
          <span className="mx-2 text-muted-foreground">·</span>
          <Link href="/refund-policy" className="text-primary hover:underline">
            Refund &amp; Cancellation
          </Link>
          <span className="mx-2 text-muted-foreground">·</span>
          <Link href="/contact" className="text-primary hover:underline">
            Contact
          </Link>
          <span className="mx-2 text-muted-foreground">·</span>
          <Link href="/signup" className="text-primary hover:underline">
            Back to sign up
          </Link>
        </div>
      </div>
    </main>
  );
}

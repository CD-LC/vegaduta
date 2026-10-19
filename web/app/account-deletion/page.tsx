import Image from "next/image";
import Link from "next/link";
import { BUSINESS_EMAIL } from "../lib/legalEntity";
import BrandName from "../components/BrandName";

const DESCRIPTION =
  "How to delete your VegaDūta account from the Android or iOS app, what is deleted, what is retained, and how to ask us if you can no longer sign in.";

export const metadata = {
  title: "Delete your VegaDūta account",
  description: DESCRIPTION,
  alternates: { canonical: "/account-deletion" },
  openGraph: {
    title: "Delete your VegaDūta account",
    description: DESCRIPTION,
    images: ["/og-image.png"],
  },
};

// Public account-deletion resource required by Google Play (Data safety /
// account deletion) and Apple 5.1.1(v): reachable without sign-in (see
// PUBLIC_ROUTES in app/lib/publicRoutes.ts), names the app, gives the in-app
// steps, what is deleted vs retained, and a contact path for people who can
// no longer sign in. Contract: docs/plans/STORE-COMPLIANCE-CONTRACT.md §2.
// Behaviour described here is DELETE /api/account (core/tenancy
// AccountSelfDeletionService) — keep the two in step. Retention wording is
// deliberately the same as the Privacy Policy's; do not add numbers here that
// the policy does not state.
// Deployment-configured (NEXT_PUBLIC_CONTACT_EMAIL) - never a hosted address.
// Empty when unset: the page then omits the mailto instead of showing a placeholder (SE-46).
const CONTACT_EMAIL = BUSINESS_EMAIL;

const DELETED: string[] = [
  "Your VegaDūta account and sign-in identity (email and password, linked sign-in methods and active sessions).",
  "Your profile details held by the platform (email, phone number, profile picture).",
  "Health and safety findings that VegaDūta Sentinels synced from your phone to the workspace.",
  "If you are the only person in your workspace: the whole workspace — its agents, workflows, knowledge, conversations, connected channels, stored files, devices and settings.",
];

const RETAINED: string[] = [
  "In a workspace shared with other people, agents, workflows and other content you created belong to that organisation and stay with it. The organisation's admin can remove them.",
  "Phones paired as devices to a shared workspace belong to that workspace; its admin can revoke them.",
  "Reports you filed about AI content are kept for safety review, with your identity removed.",
  "In a shared workspace, chat conversations are stored per conversation, not per person, so they stay with the workspace; its admin can delete them, or email us and we will remove the ones you identify.",
  "Ratings and comments you left on AI replies stay in the workspace's quality and usage records.",
  "Records we must keep by law, such as billing, payment and tax records.",
];

export default function AccountDeletionPage() {
  return (
    <main className="vd-pub min-h-screen w-full bg-background text-foreground">
      <div className="mx-auto max-w-3xl px-5 py-16 sm:px-8">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          <Image src="/logo-mark.png" alt="" height={32} width={44} className="h-8 w-auto object-contain" style={{ height: 32, width: "auto" }} draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>
        <h1 className="mt-8 text-3xl font-semibold tracking-tight">Delete your <BrandName /> account</h1>
        <p className="mt-2 text-pretty text-muted-foreground">
          This page explains how to delete your account, in the mobile app where your deployment offers it, and what
          happens to your data when you do.
        </p>
        <div className="mt-8 space-y-8">
          <section>
            <h2 className="text-lg font-semibold">1. Delete your account in the app (where available)</h2>
            <ul className="mt-2 list-disc space-y-1.5 pl-5 text-muted-foreground">
              <li>
                <span className="font-medium text-foreground">Android:</span> open VegaDūta, then go to Profile → Delete
                account.
              </li>
              <li>
                <span className="font-medium text-foreground">iOS:</span> open VegaDūta, then go to Profile → Delete
                account.
              </li>
            </ul>
            <p className="mt-2 text-pretty text-muted-foreground">
              You will be asked to type DELETE to confirm. The app then signs you out and clears the data it kept on
              your phone.
            </p>
          </section>
          <section>
            <h2 className="text-lg font-semibold">2. What is deleted</h2>
            <ul className="mt-2 list-disc space-y-1.5 pl-5 text-muted-foreground">
              {DELETED.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>
          <section>
            <h2 className="text-lg font-semibold">3. What is kept</h2>
            <ul className="mt-2 list-disc space-y-1.5 pl-5 text-muted-foreground">
              {RETAINED.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>
          <section>
            <h2 className="text-lg font-semibold">4. When it happens</h2>
            <p className="mt-1.5 text-pretty text-muted-foreground">
              Your account is deleted as soon as you confirm, and you cannot sign in with it again. After that, data
              is removed as described in our data-erasure process, and records we must keep for legal obligations are
              retained only as long as the law requires. See the{" "}
              <Link href="/privacy" className="text-primary hover:underline">
                Privacy Policy
              </Link>{" "}
              (Retention) for details.
            </p>
          </section>
          <section>
            <h2 className="text-lg font-semibold">5. Can&apos;t sign in?</h2>
            <p className="mt-1.5 text-pretty text-muted-foreground">
              {CONTACT_EMAIL ? (
                <>
                  If you can no longer sign in, email{" "}
                  <a href={`mailto:${CONTACT_EMAIL}?subject=Delete%20my%20VegaD%C5%ABta%20account`} className="text-primary hover:underline">
                    {CONTACT_EMAIL}
                  </a>{" "}
                  from the email address on your account, with the subject &quot;Delete my VegaDūta account&quot;. We will
                  confirm the request with you and then delete the account as described above.
                </>
              ) : (
                <>
                  If you can no longer sign in, contact the operator of this deployment from the email address on your
                  account and ask for the account to be deleted. They will confirm the request with you and then delete
                  the account as described above.
                </>
              )}
            </p>
          </section>
        </div>
        <div className="mt-12 text-sm">
          <Link href="/privacy" className="text-primary hover:underline">Privacy Policy</Link>
          <span className="mx-2 text-muted-foreground">·</span>
          <Link href="/terms" className="text-primary hover:underline">Terms of Service</Link>
        </div>
      </div>
    </main>
  );
}

import LegalPage, { PublicEmail } from "../components/legal/LegalPage";
import { BUSINESS_ADDRESS, BUSINESS_EMAIL, BUSINESS_PHONE, CIN_NUMBER, LEGAL_ENTITY } from "../lib/legalEntity";
import { isOssSite } from "../lib/siteMode";
import { isBillingEnabled } from "../lib/billing-flag";

const TITLE = "Contact Us — VegaDūta";
const DESCRIPTION = "How to reach VegaDūta: support, billing and refund questions, and our company details.";

export const metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: "/contact" },
  openGraph: { title: TITLE, description: DESCRIPTION, images: ["/og-image.png"] },
};

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1 border-b border-border py-3 sm:grid-cols-[11rem_1fr] sm:gap-4">
      <dt className="text-sm font-medium text-foreground">{label}</dt>
      <dd className="m-0 text-muted-foreground">{children}</dd>
    </div>
  );
}

export default function ContactPage() {
  return (
    <LegalPage
      title="Contact Us"
      updated="8 October 2026"
      intro={
        isOssSite()
          ? "Questions about the project, a security report or a deployment? Write to us, or open an issue on GitHub."
          : "Questions about your account, billing, a refund or the product? Write to us and a person will reply within 2 business days."
      }
    >
      <dl className="m-0">
        <Row label={isOssSite() ? "Email" : "Email (support, billing, refunds)"}>
          <PublicEmail />
        </Row>
        {BUSINESS_EMAIL && (
          <Row label="Company email">
            <PublicEmail address={BUSINESS_EMAIL} />
          </Row>
        )}
        <Row label="Legal entity">{LEGAL_ENTITY}</Row>
        {CIN_NUMBER && <Row label="CIN">{CIN_NUMBER}</Row>}
        {BUSINESS_ADDRESS && <Row label="Registered office">{BUSINESS_ADDRESS}</Row>}
        {BUSINESS_PHONE && (
          <Row label="Phone">
            <a href={`tel:${BUSINESS_PHONE.replace(/\s+/g, "")}`} className="text-primary hover:underline">
              {BUSINESS_PHONE}
            </a>
          </Row>
        )}
        {isBillingEnabled() && (
          <Row label="Billing and refunds">
            See the{" "}
            <a href="/refund-policy" className="text-primary hover:underline">
              Refund &amp; Cancellation Policy
            </a>
            ; include your workspace name and payment ID so we can find the payment quickly.
          </Row>
        )}
      </dl>
    </LegalPage>
  );
}

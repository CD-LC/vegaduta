import LegalPage, { SUPPORT_EMAIL, type LegalSection } from "../components/legal/LegalPage";

const TITLE = "Shipping & Delivery Policy — VegaDūta";
const DESCRIPTION =
  "VegaDūta is a digital service: nothing is shipped. Access is activated online as soon as your payment is confirmed.";

export const metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: "/shipping-policy" },
  openGraph: { title: TITLE, description: DESCRIPTION, images: ["/og-image.png"] },
};

const SECTIONS: LegalSection[] = [
  {
    h: "1. A digital service, nothing is shipped",
    p: [
      "VegaDūta is software delivered online. We do not sell or ship physical goods, so there are no shipping charges, delivery addresses or courier timelines.",
    ],
  },
  {
    h: "2. When your plan is activated",
    p: [
      "Your plan is activated on your account as soon as your payment is confirmed by our payment partner. This normally happens within a few minutes. You will see the new plan in your account and receive a receipt by email.",
    ],
  },
  {
    h: "3. If your plan has not activated",
    p: [
      `If more than 24 hours pass after a successful payment and your plan has not changed, email ${SUPPORT_EMAIL} with your workspace name and payment ID. We will fix it or refund you in full.`,
    ],
  },
  {
    h: "4. Customers outside India",
    p: [
      "Delivery is identical worldwide: the service is accessed online from any country where it is available, with no import duties or customs.",
    ],
  },
];

export default function ShippingPolicyPage() {
  return <LegalPage title="Shipping & Delivery Policy" updated="8 October 2026" sections={SECTIONS} />;
}

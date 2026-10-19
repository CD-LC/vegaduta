import LegalPage, { SUPPORT_EMAIL, type LegalSection } from "../components/legal/LegalPage";

const TITLE = "Refund & Cancellation Policy — VegaDūta";
const DESCRIPTION =
  "How cancelling a VegaDūta subscription works, when you can get a refund (a full refund within 7 days of your first charge), and how to request one.";

export const metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: "/refund-policy" },
  openGraph: { title: TITLE, description: DESCRIPTION, images: ["/og-image.png"] },
};

// Public route (see PUBLIC_ROUTES in lib/publicRoutes.ts).
export const REFUND_SECTIONS: LegalSection[] = [
  {
    h: "1. Who this applies to",
    p: [
      "This policy covers paid VegaDūta subscriptions (the Premium and Unlimited plans), billed monthly or annually through our payment partner Razorpay. The Free plan and any complimentary access are never charged, so there is nothing to refund.",
    ],
  },
  {
    h: "2. Complimentary access and the Free plan",
    p: [
      "New accounts start with a period of complimentary full access (currently 3 months of the Unlimited plan) and we do not take payment details for it. When it ends your workspace moves to the Free plan automatically. You are charged only if you choose a paid plan, and we email you before the complimentary period ends.",
    ],
  },
  {
    h: "3. Cancelling your subscription",
    p: [
      "You can cancel at any time from Billing in your account. Cancellation takes effect at the end of the billing period you have already paid for: you keep your plan until then, you are not charged again, and your workspace then moves to the Free plan. Your agents, workflows and data are kept.",
    ],
  },
  {
    h: "4. When you can get a refund",
    p: [
      "First-charge guarantee: if you are not satisfied, ask for a refund within 7 days of the first successful charge on a new subscription (monthly or annual) and we will refund it in full.",
      "Duplicate or erroneous charges: if you were charged twice, or charged an amount that does not match your plan, we refund the difference in full, whenever you tell us.",
      "Everything else: after the 7 days, fees for a billing period that has already started are not refundable, and we do not give pro-rata refunds for unused time on a monthly or annual plan. Renewal charges are not refundable once the new period has started; cancel before the renewal date to avoid them. Usage-based charges for messages or resources already consumed are not refundable.",
    ],
  },
  {
    h: "5. How to request a refund",
    p: [
      `Email ${SUPPORT_EMAIL} from the address on your account with your workspace name and the payment ID or receipt number. We reply within 2 business days. An approved refund is started within 5 to 7 business days and goes back to the original payment method; after that your bank or card issuer typically takes 5 to 10 business days to show it.`,
    ],
  },
  {
    h: "6. Taxes",
    p: ["Where GST or another tax was charged, the tax on the refunded amount is refunded with it."],
  },
  {
    h: "7. Failed renewals",
    p: [
      "If a renewal payment fails we retry and email you so you can update your payment method. If it cannot be collected, the subscription ends and your workspace can move to the Free plan; your data is kept.",
    ],
  },
  {
    h: "8. Chargebacks and disputes",
    p: [
      `Please contact us at ${SUPPORT_EMAIL} before raising a dispute with your bank; we resolve billing questions quickly and can usually fix them faster than a chargeback.`,
    ],
  },
  {
    h: "9. Changes to this policy",
    p: [
      "We may update this policy; the date above shows the latest version. A change does not affect a refund request you have already made.",
    ],
  },
];

export default function RefundPolicyPage() {
  return (
    <LegalPage
      title="Refund & Cancellation Policy"
      updated="8 October 2026"
      intro="Cancel any time, keep what you paid for until the end of the period, and get a full refund if you change your mind in the first 7 days."
      sections={REFUND_SECTIONS}
    />
  );
}

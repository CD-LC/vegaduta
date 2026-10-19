import type { Metadata } from "next";
import BillingPageClient from "../billing/BillingPageClient";

// The public, always-on price list (Free / Premium / Unlimited, INR and USD, monthly and yearly). /billing shows a
// "free for everyone" card while the platform runs in free mode; this route shows the paid plans regardless, so the
// prices are visible to customers and to payment-gateway reviewers. Same component, same figures
// (lib/payments/pricing.ts), no auth.
export const metadata: Metadata = {
  title: "Pricing — VegaDūta",
  description:
    "VegaDūta plans and prices: Free, Premium and Unlimited, billed monthly or yearly in INR or USD. New accounts start with 3 months of Unlimited at no charge.",
  alternates: { canonical: "/pricing" },
  openGraph: {
    title: "Pricing — VegaDūta",
    description:
      "Free, Premium and Unlimited plans in INR or USD. Cancel any time; full refund within 7 days of your first charge.",
    images: ["/og-image.png"],
  },
};

export default function PricingPage() {
  return <BillingPageClient forcePaid />;
}

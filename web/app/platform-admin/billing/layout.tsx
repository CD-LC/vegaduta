import type { ReactNode } from "react";
import { redirect } from "next/navigation";
import { isBillingEnabled } from "../lib/billing-flag";

// Billing switch: with NEXT_PUBLIC_BILLING_ENABLED unset (self-hosted default)
// there are no subscriptions to show, so this route goes back to Overview.
export default function BillingLayout({ children }: { children: ReactNode }) {
  if (!isBillingEnabled()) redirect("/platform-admin");
  return children;
}

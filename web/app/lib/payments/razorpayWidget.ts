// The ONE place the Razorpay checkout.js script is loaded and opened. Before this existed the same loader was
// copy-pasted into signup, the upgrade dialog and the one-off checkout button.
import type { CheckoutOptions, OrderCheckoutOptions, OrderHandoff, SubscriptionHandoff } from "./types";

declare global {
  interface Window {
    Razorpay?: any;
  }
}

const SCRIPT_SRC = "https://checkout.razorpay.com/v1/checkout.js";

/** Loads checkout.js once. Resolves false (never rejects) when it cannot load, e.g. blocked or offline. */
export function loadRazorpayScript(): Promise<boolean> {
  if (typeof window === "undefined") return Promise.resolve(false);
  if (window.Razorpay) return Promise.resolve(true);
  return new Promise((resolve) => {
    const script = document.createElement("script");
    script.src = SCRIPT_SRC;
    script.onload = () => resolve(true);
    script.onerror = () => resolve(false);
    document.body.appendChild(script);
  });
}

/** Opens Standard Checkout against a subscription id. Returns false when the script or the ids are missing. */
export async function openRazorpaySubscription(handoff: SubscriptionHandoff, o: CheckoutOptions): Promise<boolean> {
  if (!handoff.gatewayPublicKey || !handoff.gatewaySubscriptionId) return false;
  if (!(await loadRazorpayScript()) || !window.Razorpay) return false;
  const checkout = new window.Razorpay({
    key: handoff.gatewayPublicKey,
    subscription_id: handoff.gatewaySubscriptionId,
    name: "VegaDūta",
    description: o.description,
    handler: () => o.onComplete(),
    modal: { ondismiss: () => o.onDismiss() },
    theme: { color: "#4f46e5" },
  });
  checkout.open();
  return true;
}

/** Opens Standard Checkout against a one-off order id. Returns false when the script or the order is unusable. */
export async function openRazorpayOrder(order: OrderHandoff, o: OrderCheckoutOptions): Promise<boolean> {
  if (!order.publicKeyId || !order.orderId) return false;
  if (!(await loadRazorpayScript()) || !window.Razorpay) return false;
  const checkout = new window.Razorpay({
    key: order.publicKeyId,
    amount: order.amount,
    currency: order.currency,
    order_id: order.orderId,
    name: "VegaDūta",
    description: o.description,
    handler: (r: { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }) =>
      o.onPaid({
        gateway: order.gateway,
        orderId: r.razorpay_order_id,
        paymentId: r.razorpay_payment_id,
        signature: r.razorpay_signature,
      }),
    modal: { ondismiss: () => o.onDismiss() },
    theme: { color: "#4f46e5" },
  });
  checkout.open();
  return true;
}

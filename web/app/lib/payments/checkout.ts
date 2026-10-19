// Gateway-neutral subscription checkout. The console never asks "is this Razorpay?": it asks the gateway list
// how the gateway wants to be opened (WIDGET vs REDIRECT) and looks up a driver for widget gateways by name.
// Adding a gateway therefore means ONE of:
//   - a hosted-page gateway (Stripe Checkout, PhonePe ...): nothing to add here - its checkoutUrl is followed;
//   - a widget gateway: register a driver in WIDGET_DRIVERS below.
import { openRazorpayOrder, openRazorpaySubscription } from "./razorpayWidget";
import type {
  CheckoutOptions,
  CheckoutOutcome,
  GatewayInfo,
  GatewayList,
  OrderCheckoutOptions,
  OrderHandoff,
  SubscriptionHandoff,
} from "./types";

export interface WidgetDriver {
  /** Opens the gateway's browser widget; false when it could not be opened (script blocked, ids missing). */
  open(handoff: SubscriptionHandoff, o: CheckoutOptions): Promise<boolean>;
}

const WIDGET_DRIVERS: Record<string, WidgetDriver> = {
  razorpay: { open: openRazorpaySubscription },
};

/** The gateway core will use when a request names none: the list's default, else the first configured one. */
export function defaultGateway(list: GatewayList | null | undefined): GatewayInfo | null {
  if (!list || list.gateways.length === 0) return null;
  return (
    list.gateways.find((g) => g.name === list.defaultGateway) ??
    list.gateways.find((g) => g.isDefault) ??
    list.gateways.find((g) => g.configured) ??
    null
  );
}

/**
 * Opens the right checkout for a started subscription. `gateway` may be null (the gateway list could not be
 * fetched, or an older core without /api/billing/gateways): the handoff itself then decides - a subscription id
 * plus a public key is the Razorpay widget shape every older core returned, otherwise a checkoutUrl is followed.
 */
export async function openSubscriptionCheckout(
  gateway: Pick<GatewayInfo, "name" | "checkoutMode"> | null,
  handoff: SubscriptionHandoff,
  o: CheckoutOptions,
  navigate: (url: string) => void = (url) => window.location.assign(url),
): Promise<CheckoutOutcome> {
  const widgetShaped = !!handoff.gatewaySubscriptionId && !!handoff.gatewayPublicKey;
  const mode = gateway?.checkoutMode ?? (widgetShaped ? "WIDGET" : "REDIRECT");
  const driver = WIDGET_DRIVERS[gateway?.name ?? "razorpay"];

  if (mode === "WIDGET" && driver && (await driver.open(handoff, o))) return "opened";
  // A widget that could not open still has a hosted page to fall back to when the gateway gave one.
  if (handoff.checkoutUrl) {
    navigate(handoff.checkoutUrl);
    return "redirecting";
  }
  return "unavailable";
}

/** Browser widgets for ONE-OFF orders, by gateway name (the subscription drivers above are separate). */
const ORDER_DRIVERS: Record<string, (order: OrderHandoff, o: OrderCheckoutOptions) => Promise<boolean>> = {
  razorpay: openRazorpayOrder,
};

/** Opens the right checkout for a created one-off order: a widget for widget gateways, else its hosted page. */
export async function openOrderCheckout(
  order: OrderHandoff,
  o: OrderCheckoutOptions,
  navigate: (url: string) => void = (url) => window.location.assign(url),
): Promise<CheckoutOutcome> {
  const driver = ORDER_DRIVERS[order.gateway];
  if (order.checkoutMode === "WIDGET" && driver && (await driver(order, o))) return "opened";
  if (order.checkoutUrl) {
    navigate(order.checkoutUrl);
    return "redirecting";
  }
  return "unavailable";
}

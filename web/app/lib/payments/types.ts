// Gateway-neutral payment types. They mirror core's /api/billing/gateways response (GatewayInfo) and the
// upgrade handoff (TenantUpgradeResponse). Nothing here names a gateway: which checkout to render is decided by
// `checkoutMode` and by the driver registered for the gateway in ./checkout.ts.

export type CheckoutMode = "WIDGET" | "REDIRECT";

export type GatewayCapability =
  | "SUBSCRIPTIONS"
  | "ONE_TIME_ORDERS"
  | "PAYMENT_VERIFICATION"
  | "DISCOUNT_OFFERS"
  | "USAGE_ADDONS"
  | "UPI_COLLECT"
  | "UPI_QR";

export interface GatewayInfo {
  name: string;
  configured: boolean;
  isDefault: boolean;
  /** Publishable key only - the server never sends a secret. */
  publicKeyId: string;
  checkoutMode: CheckoutMode;
  capabilities: GatewayCapability[];
}

export interface GatewayList {
  defaultGateway: string;
  gateways: GatewayInfo[];
}

/** What core returns when a subscription checkout is started (TenantUpgradeResponse). */
export interface SubscriptionHandoff {
  gatewaySubscriptionId?: string | null;
  gatewayPublicKey?: string | null;
  checkoutUrl?: string | null;
}

export interface CheckoutOptions {
  /** Shown by the gateway's own checkout, e.g. "VegaDūta Premium". */
  description: string;
  /** The browser-side flow finished. This is a UX handoff only - the webhook is what activates the plan. */
  onComplete: () => void;
  /** The customer closed the widget without paying. */
  onDismiss: () => void;
}

/** "opened" = a widget is showing; "redirecting" = navigating to a hosted page; "unavailable" = nothing could be opened. */
export type CheckoutOutcome = "opened" | "redirecting" | "unavailable";

/** What core returns for a one-off order (POST /api/billing/checkout/create-order). */
export interface OrderHandoff {
  gateway: string;
  orderId: string;
  /** Smallest currency unit (paise / cents). */
  amount: number;
  currency: string;
  publicKeyId: string;
  checkoutMode: CheckoutMode;
  /** Set only by hosted-page gateways. */
  checkoutUrl?: string | null;
}

/** What a gateway's widget hands back when an order was paid; the server verifies it before anything is credited. */
export interface OrderPaymentResult {
  gateway: string;
  orderId: string;
  paymentId: string;
  signature: string;
}

export interface OrderCheckoutOptions {
  description: string;
  onPaid: (result: OrderPaymentResult) => void;
  onDismiss: () => void;
}

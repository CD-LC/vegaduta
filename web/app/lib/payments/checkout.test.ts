import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defaultGateway, openOrderCheckout, openSubscriptionCheckout } from "./checkout";
import type { CheckoutOptions, GatewayInfo, GatewayList, OrderHandoff } from "./types";

const gw = (over: Partial<GatewayInfo>): GatewayInfo => ({
  name: "razorpay",
  configured: true,
  isDefault: false,
  publicKeyId: "rzp_test_x",
  checkoutMode: "WIDGET",
  capabilities: ["SUBSCRIPTIONS", "ONE_TIME_ORDERS", "PAYMENT_VERIFICATION"],
  ...over,
});

const opts = (): CheckoutOptions => ({ description: "VegaDūta Premium", onComplete: vi.fn(), onDismiss: vi.fn() });

/** A stand-in for checkout.js: records the options it was opened with. */
function installRazorpay() {
  const opened: any[] = [];
  (window as any).Razorpay = class {
    constructor(public o: any) {}
    open() {
      opened.push(this.o);
    }
  };
  return opened;
}

describe("defaultGateway", () => {
  it("prefers the list's named default, then isDefault, then the first configured one", () => {
    const list: GatewayList = {
      defaultGateway: "stripe",
      gateways: [gw({ name: "razorpay", isDefault: false }), gw({ name: "stripe", checkoutMode: "REDIRECT" })],
    };
    expect(defaultGateway(list)?.name).toBe("stripe");
    expect(defaultGateway({ ...list, defaultGateway: "missing", gateways: [gw({ name: "a" }), gw({ name: "b", isDefault: true })] })?.name).toBe("b");
    expect(defaultGateway({ defaultGateway: "x", gateways: [gw({ name: "a", configured: false }), gw({ name: "b" })] })?.name).toBe("b");
  });
  it("is null for no list or an empty one", () => {
    expect(defaultGateway(null)).toBeNull();
    expect(defaultGateway({ defaultGateway: "razorpay", gateways: [] })).toBeNull();
  });
});

describe("openSubscriptionCheckout", () => {
  let opened: any[];
  beforeEach(() => {
    opened = installRazorpay();
  });
  afterEach(() => {
    delete (window as any).Razorpay;
  });

  it("opens the Razorpay widget with the subscription id and key for a widget gateway", async () => {
    const o = opts();
    const outcome = await openSubscriptionCheckout(
      gw({}),
      { gatewaySubscriptionId: "sub_1", gatewayPublicKey: "rzp_test_x", checkoutUrl: "https://hosted" },
      o,
    );
    expect(outcome).toBe("opened");
    expect(opened).toHaveLength(1);
    expect(opened[0]).toMatchObject({ key: "rzp_test_x", subscription_id: "sub_1", description: "VegaDūta Premium" });
    opened[0].handler();
    expect(o.onComplete).toHaveBeenCalled();
    opened[0].modal.ondismiss();
    expect(o.onDismiss).toHaveBeenCalled();
  });

  it("follows the hosted page for a redirect gateway and never touches a widget", async () => {
    const navigate = vi.fn();
    const outcome = await openSubscriptionCheckout(
      gw({ name: "stripe", checkoutMode: "REDIRECT" }),
      { checkoutUrl: "https://checkout.stripe.test/c/pay_1" },
      opts(),
      navigate,
    );
    expect(outcome).toBe("redirecting");
    expect(navigate).toHaveBeenCalledWith("https://checkout.stripe.test/c/pay_1");
    expect(opened).toHaveLength(0);
  });

  it("without a gateway list, the handoff's own shape decides (older core: id + key = Razorpay widget)", async () => {
    const outcome = await openSubscriptionCheckout(null, { gatewaySubscriptionId: "sub_1", gatewayPublicKey: "rzp_test_x" }, opts());
    expect(outcome).toBe("opened");
  });

  it("falls back to the hosted page when the widget cannot open, and reports unavailable when there is none", async () => {
    delete (window as any).Razorpay;
    // Make the script load fail so the widget cannot open.
    const append = vi.spyOn(document.body, "appendChild").mockImplementation((node: any) => {
      queueMicrotask(() => node.onerror?.());
      return node;
    });
    const navigate = vi.fn();
    expect(
      await openSubscriptionCheckout(gw({}), { gatewaySubscriptionId: "s", gatewayPublicKey: "k", checkoutUrl: "https://hosted" }, opts(), navigate),
    ).toBe("redirecting");
    expect(navigate).toHaveBeenCalledWith("https://hosted");
    expect(await openSubscriptionCheckout(gw({}), { gatewaySubscriptionId: "s", gatewayPublicKey: "k" }, opts(), navigate)).toBe("unavailable");
    append.mockRestore();
  });

  it("is unavailable when nothing usable came back", async () => {
    expect(await openSubscriptionCheckout(gw({ name: "stripe", checkoutMode: "REDIRECT" }), {}, opts())).toBe("unavailable");
  });
});

describe("openOrderCheckout", () => {
  afterEach(() => {
    delete (window as any).Razorpay;
  });
  const order = (over: Partial<OrderHandoff>): OrderHandoff => ({
    gateway: "razorpay",
    orderId: "order_1",
    amount: 100,
    currency: "INR",
    publicKeyId: "rzp_test_x",
    checkoutMode: "WIDGET",
    checkoutUrl: null,
    ...over,
  });

  it("opens the widget against the order and hands back ids + signature when paid", async () => {
    const opened = installRazorpay();
    const onPaid = vi.fn();
    const outcome = await openOrderCheckout(order({}), { description: "Top-up", onPaid, onDismiss: vi.fn() });
    expect(outcome).toBe("opened");
    expect(opened[0]).toMatchObject({ key: "rzp_test_x", order_id: "order_1", amount: 100, currency: "INR" });
    opened[0].handler({ razorpay_order_id: "order_1", razorpay_payment_id: "pay_1", razorpay_signature: "sig" });
    expect(onPaid).toHaveBeenCalledWith({ gateway: "razorpay", orderId: "order_1", paymentId: "pay_1", signature: "sig" });
  });

  it("redirects a hosted-page order", async () => {
    const navigate = vi.fn();
    const outcome = await openOrderCheckout(
      order({ gateway: "stripe", checkoutMode: "REDIRECT", checkoutUrl: "https://pay.example/1" }),
      { description: "x", onPaid: vi.fn(), onDismiss: vi.fn() },
      navigate,
    );
    expect(outcome).toBe("redirecting");
    expect(navigate).toHaveBeenCalledWith("https://pay.example/1");
  });

  it("is unavailable for a widget gateway with no driver and no hosted page", async () => {
    expect(await openOrderCheckout(order({ gateway: "unknownpay" }), { description: "x", onPaid: vi.fn(), onDismiss: vi.fn() })).toBe("unavailable");
  });
});

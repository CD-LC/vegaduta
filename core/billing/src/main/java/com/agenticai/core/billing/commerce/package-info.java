/**
 * Agentic commerce v0 - an agent-callable "collect a UPI payment" flow backed by Razorpay UPI
 * Payment Links, closed by the {@code payment_link.paid} / {@code qr_code.credited} webhooks.
 *
 * <h2>What this is</h2>
 *
 * <ul>
 *   <li>{@link com.agenticai.core.billing.commerce.CheckoutSession} - one row per "please pay this
 *       much" request an agent (or, later, a web console / A2A peer) raised on a tenant's behalf.
 *       Persisted in {@code control_plane.checkout_session} (migration V221) via plain JDBC, same
 *       no-JPA posture as {@code billing_invoice} - see {@link
 *       com.agenticai.core.billing.commerce.CheckoutSessionRepository}.
 *   <li>{@link com.agenticai.core.billing.commerce.CheckoutSessionService} - creates the session,
 *       asks the default {@link com.agenticai.core.billing.gateway.PaymentGatewayClient} for a UPI
 *       Payment Link ({@code createUpiCollectRequest}), and reconciles the webhook confirmation
 *       back onto the row (idempotent, amount-checked).
 *   <li>{@link com.agenticai.core.billing.commerce.CheckoutSessionController} - {@code GET
 *       /api/tenant/checkout-sessions} and {@code /{id}} for the console.
 *   <li>{@link com.agenticai.core.billing.commerce.CommerceProduct} + {@link
 *       com.agenticai.core.billing.commerce.CommerceProductRepository} - the tenant product catalog
 *       ({@code control_plane.commerce_product}, migration V252): the sellable items a tenant's
 *       agents and the public feed can price from, so checkout is no longer bare
 *       amount+description.
 *   <li>{@link com.agenticai.core.billing.commerce.ProductCatalogService} + {@link
 *       com.agenticai.core.billing.commerce.ProductCatalogController} - member CRUD at {@code
 *       /api/tenant/commerce/products}, tenant id from {@code TenantContext} only.
 *   <li>{@link com.agenticai.core.billing.commerce.ProductFeedController} - the PUBLIC ACP-shaped
 *       feed at {@code GET /api/commerce/feed/{tenantId}} (permitAll, published rows only) and the
 *       public cart checkout at {@code POST /api/commerce/feed/{tenantId}/checkout}. Checkout
 *       prices carts server-side from catalog rows - a buyer chooses product + quantity, never an
 *       amount - and is bounded by a per-tenant daily cap ({@code
 *       app.commerce.feed.max-checkouts-per-day}). Completion still lands through the same {@code
 *       payment_link.paid} webhook and {@code recordGatewayPayment} reconciliation as tool-raised
 *       sessions.
 *   <li>{@link com.agenticai.core.billing.commerce.CheckoutSessionCompletedEvent} - the in-JVM
 *       Spring event fired once a session is COMPLETED. {@code core/agents}' {@code
 *       CheckoutPaymentReceivedWorkflowEventBridge} re-publishes it on the workflow eventbus as
 *       {@code commerce.payment.received} (this module cannot depend on {@code core/eventbus} -
 *       eventbus -&gt; tenancy -&gt; billing would be a cycle).
 *   <li>The agent tools ({@code create_payment_link}, {@code check_payment_status}) live in {@code
 *       core/agents}' {@code com.agenticai.core.agents.tools.commerce} package and go through the
 *       standard {@code GuardedToolCallback} approval gate. Because their names contain "payment",
 *       {@code ApprovalPolicyService}'s default {@code *payment*} sensitive pattern gates BOTH out
 *       of the box - money movement never runs without a human tap unless a tenant-admin explicitly
 *       loosens {@code mcp_tool.create_payment_link} / {@code mcp_tool.check_payment_status} via
 *       {@code PUT /api/approvals/policy/**}. There is no bypass path in this package.
 * </ul>
 *
 * <h2>ACP-shaped, NOT ACP-conformant - read this before wiring anything external to it</h2>
 *
 * The session vocabulary deliberately mirrors OpenAI's Agentic Commerce Protocol (ACP) {@code
 * CheckoutSession}: the same status set ({@code not_ready_for_payment} / {@code ready_for_payment}
 * / {@code completed} / {@code canceled} / {@code expired}), a {@code line_items} array with
 * per-item {@code unit_amount}/{@code total} in minor units, and a lowercase-3-letter {@code
 * currency}. That is so a later ChatGPT Instant Checkout / Stripe Shared Payment Token integration
 * is a thin adapter over the same table rather than a rewrite. It is NOT ACP-conformant today:
 *
 * <ul>
 *   <li>No {@code Signature} / request-signing verification of an inbound ACP caller and no {@code
 *       Idempotency-Key} header contract. The {@code /api/commerce/feed/{tenantId}/checkout} POST
 *       is a real public checkout-session creator, but it is ACP-SHAPED like the rest of this
 *       package - it does not implement ACP's request-signing or delegate-payment handshake.
 *   <li>No delegated-payment token: ACP's {@code complete} step takes a PSP-issued {@code
 *       payment_data.token}. Razorpay is not an ACP PSP and issues no such token, so completion
 *       here is "customer opens the UPI link in their own app", confirmed asynchronously by
 *       webhook.
 *   <li>The platform's Razorpay account is the merchant of record - there is no per-tenant (BYOK)
 *       Razorpay credential yet, so a tenant's customer pays into the platform's account.
 *       Per-tenant merchant credentials are the first thing to add before this is used for real SMB
 *       sales.
 * </ul>
 *
 * <h2>Roadmap - documented so it is not re-litigated</h2>
 *
 * <ul>
 *   <li><b>x402</b> (HTTP 402 machine-to-machine payments) settles in USDC on EVM/Solana via a
 *       facilitator ({@code POST /verify}, {@code /settle}). No INR/UPI rail exists for it and
 *       there is no Indian regulatory clarity for stablecoin settlement; it would only ever be
 *       added for machine-to-machine billing of this platform's OWN API endpoints,
 *       public-facilitator-on-testnet first. Not on the path for WhatsApp SMB collections.
 *   <li><b>ACP</b> - see above. Needs an ACP PSP (Stripe Shared Payment Token today) plus OpenAI
 *       merchant onboarding; a Razorpay path does not exist. When it does, the adapter is: expose
 *       {@code /checkout_sessions} over this table, verify the caller signature, swap the UPI-link
 *       step for the delegated-token step.
 *   <li><b>AP2</b> (Google's Agent Payments Protocol) - Intent/Cart/Payment mandates as signed
 *       verifiable credentials over an A2A extension. {@code CheckoutSession} carries what a {@code
 *       CartMandate.contents} needs (id, total, expiry, merchant name via the tenant) so it can be
 *       emitted as one once a UPI credentials provider exists.
 * </ul>
 *
 * <h2>Known v0 limits (all deliberate, all listed here so nobody assumes otherwise)</h2>
 *
 * <ul>
 *   <li>Expiry is now enforced on BOTH sides: {@code expires_at} (default 24h) is passed to the
 *       gateway as Razorpay's {@code expire_by} via the eight-argument {@code
 *       PaymentGatewayClient#createUpiCollectRequest} overload, so the hosted link stops being
 *       payable when the session does. A webhook that somehow still arrives after expiry still
 *       marks the session COMPLETED, because the money genuinely arrived.
 *   <li>The webhook's {@code BillingEvent} carries only the payment-link/QR id. The captured
 *       payment id and paid amount are fetched back from the gateway by {@link
 *       com.agenticai.core.billing.commerce.PaidPaymentLookup} (best-effort); when that lookup is
 *       unavailable the session still completes, with {@code payment_id} left null for manual
 *       reconciliation.
 *   <li>Webhook redelivery is idempotent at the row level (a COMPLETED row is never re-completed,
 *       and the completed event fires only on the actual transition) AND deduplicated on {@code
 *       x-razorpay-event-id} by {@link
 *       com.agenticai.core.billing.gateway.BillingWebhookEventLedger} (V250) - per-consumer claims,
 *       fail-open, so the remaining duplicate risk is a ledger outage, which degrades to row-level
 *       idempotency.
 * </ul>
 */
package com.agenticai.core.billing.commerce;

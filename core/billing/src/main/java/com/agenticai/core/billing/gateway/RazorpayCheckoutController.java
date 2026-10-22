package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Razorpay-ONLY checkout surfaces: the India-specific UPI endpoints. The gateway-neutral order creation and
 * payment verification (and the list of gateways) live in {@link PaymentCheckoutController}, which works for any
 * registered gateway; this class stays because UPI Payment Links and UPI QR codes exist only on Razorpay
 * ({@link GatewayCapability#UPI_COLLECT}, {@link GatewayCapability#UPI_QR}).
 *
 * <ul>
 *   <li>{@code POST /api/billing/checkout/create-upi-collect} - UPI-collect
 *       follow-up: create a UPI Payment Link the customer completes via
 *       their own UPI app's Intent flow (see
 *       {@link GatewayUpiCollectRequest}'s javadoc for why this is
 *       Intent-based, not Razorpay's now-deprecated manual-VPA Collect
 *       flow)</li>
 *   <li>{@code POST /api/billing/checkout/create-upi-qr} - UPI-collect
 *       follow-up: create a scannable UPI QR code for a fixed amount</li>
 *   <li>{@code POST /api/billing/checkout/close-upi-qr/{qrCodeId}} -
 *       UPI-collect follow-up: close a QR code created by {@code
 *       create-upi-qr} so it stops accepting further payments</li>
 * </ul>
 *
 * <p>Both UPI creation endpoints return a hosted link/image, not a synchronous
 * payment result - there is no browser-side modal callback the way Standard
 * Checkout has, so confirming a UPI-collect/QR payment happens later, out of
 * band, via the {@code payment_link.paid}/{@code qr_code.credited} webhooks
 * ({@link BillingEventType#PAYMENT_LINK_PAID}/
 * {@link BillingEventType#QR_CODE_PAYMENT_RECEIVED}) - not this controller.
 *
 * <p>Gated behind the {@code security} profile, same as {@link
 * RazorpayGatewayClient} - all checkout operations are tenant-scoped and
 * require authentication. TenantContext is the single source of identity.
 */
@RestController
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class RazorpayCheckoutController {





  public record CreateUpiCollectRequest(
      long amountInPaise, // UPI is INR-only, so this is always paise
      String description,
      String customerName,    // optional, pre-fills the payment page
      String customerContact, // optional
      String customerEmail    // optional
  ) {}

  public record CreateUpiCollectResponse(
      String id,       // Payment Link id (plink_...)
      String shortUrl, // hosted page for the customer to open and pay via any UPI app
      String status
  ) {}

  public record CreateUpiQrRequest(
      long amountInPaise, // fixed amount the QR code is created for
      String name,
      String description
  ) {}

  public record CreateUpiQrResponse(
      String id,       // QR code id (qr_...)
      String imageUrl, // renderable QR code image the frontend can display directly
      String status,
      long amountInPaise
  ) {}

  public record CloseUpiQrResponse(
      String id,     // QR code id (qr_...) that was closed
      String status  // always "closed" on success - close() throws otherwise
  ) {}

  private final RazorpayGatewayClient razorpayClient;

  public RazorpayCheckoutController(RazorpayGatewayClient razorpayClient) {
    this.razorpayClient = razorpayClient;
  }

  /**
   * Create a Razorpay order for a one-off payment (top-up, test charge).
   * Returns the order_id needed to open the Razorpay payment modal on the
   * frontend. Minimum amount: 100 paise (~₹1 or $0.01).
   */
  /**
   * UPI-collect follow-up: create a UPI Payment Link for a one-off payment.
   * The frontend opens/shares {@code shortUrl} - the customer completes
   * payment via their own UPI app's Intent flow, no browser modal or
   * synchronous callback involved. Confirmation happens later via the
   * {@code payment_link.paid} webhook, not a response from this endpoint.
   */
  @PostMapping("/api/billing/checkout/create-upi-collect")
  public CreateUpiCollectResponse createUpiCollect(@RequestBody CreateUpiCollectRequest request) {
    String tenantId = getTenantIdOrThrow();

    if (request.amountInPaise < 100) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Amount must be at least 100 paise (₹1)"
      );
    }

    try {
      Map<String, String> notes = new HashMap<>();
      notes.put("tenant_id", tenantId);

      GatewayUpiCollectRequest link = razorpayClient.createUpiCollectRequest(
          tenantId,
          request.amountInPaise,
          request.description,
          request.customerName,
          request.customerContact,
          request.customerEmail,
          notes
      );

      return new CreateUpiCollectResponse(link.id(), link.shortUrl(), link.status());
    } catch (PaymentGatewayException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to create UPI payment link: " + e.getMessage()
      );
    }
  }

  /**
   * UPI-collect follow-up: create a scannable UPI QR code for a fixed
   * one-off amount. Same "no synchronous callback, confirmed later via
   * webhook" posture as {@link #createUpiCollect} - here via the {@code
   * qr_code.credited} webhook.
   */
  @PostMapping("/api/billing/checkout/create-upi-qr")
  public CreateUpiQrResponse createUpiQr(@RequestBody CreateUpiQrRequest request) {
    String tenantId = getTenantIdOrThrow();

    if (request.amountInPaise < 100) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Amount must be at least 100 paise (₹1)"
      );
    }

    try {
      Map<String, String> notes = new HashMap<>();
      notes.put("tenant_id", tenantId);

      GatewayUpiQrCode qr = razorpayClient.createUpiQrCode(
          tenantId,
          request.amountInPaise,
          request.name,
          request.description,
          notes
      );

      return new CreateUpiQrResponse(qr.id(), qr.imageUrl(), qr.status(), qr.amountInSubunits());
    } catch (PaymentGatewayException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to create UPI QR code: " + e.getMessage()
      );
    }
  }

  /**
   * UPI-collect follow-up: close a QR code created by {@link #createUpiQr}
   * (Razorpay: {@code POST /v1/payments/qr_codes/:id/close}) so it stops
   * accepting further payments - once this app has recorded the expected
   * payment (via the {@code qr_code.credited} webhook) or the customer has
   * abandoned it. A closed QR code cannot be reopened.
   *
   * <p>Ownership check: {@link RazorpayGatewayClient#closeUpiQrCode} itself
   * does not scope the close call by tenant (Razorpay's close API is a bare
   * id lookup), so before closing, this fetches the QR code ({@link
   * RazorpayGatewayClient#fetchUpiQrCode}, Razorpay: {@code GET
   * /v1/payments/qr_codes/:id}) and compares its {@code notes.tenant_id} -
   * stamped by {@link #createUpiQr} at creation time - against the caller's
   * {@link TenantContext} tenant id. A mismatch, or a QR code with no
   * {@code tenant_id} note at all, is rejected with {@code 403 Forbidden}
   * (fail closed, never open) rather than proceeding to close another
   * tenant's QR code.
   */
  @PostMapping("/api/billing/checkout/close-upi-qr/{qrCodeId}")
  public CloseUpiQrResponse closeUpiQr(@PathVariable String qrCodeId) {
    String tenantId = getTenantIdOrThrow();

    if (qrCodeId == null || qrCodeId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "qrCodeId is required"
      );
    }

    try {
      GatewayQrCodeDetails qrCode = razorpayClient.fetchUpiQrCode(qrCodeId);
      String ownerTenantId = qrCode.notes() == null ? null : qrCode.notes().get("tenant_id");
      if (ownerTenantId == null || !ownerTenantId.equals(tenantId)) {
        // Fail closed: no tenant_id note at all is treated the same as a
        // mismatch, never as "unowned, so anyone may close it".
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "QR code does not belong to this tenant"
        );
      }

      razorpayClient.closeUpiQrCode(qrCodeId);
      return new CloseUpiQrResponse(qrCodeId, "closed");
    } catch (PaymentGatewayException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to close UPI QR code: " + e.getMessage()
      );
    }
  }

  private String getTenantIdOrThrow() {
    return TenantContext.current()
        .map(TenantContext::getTenantId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "No tenant context - authentication required"
        ));
  }
}

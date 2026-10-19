package com.agenticai.core.agents.tools.x402;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public pay-per-call surface: {@code /x402/{slug}}.
 *
 * <ul>
 *   <li>No {@code X-PAYMENT} header - responds {@code 402 Payment Required} with the x402 v1
 *       requirements body ({@code {x402Version:1, accepts:[...]}}) plus the {@code
 *       PAYMENT-REQUIRED} header for header-only clients.
 *   <li>With {@code X-PAYMENT} - {@link X402SellerService} verifies and settles, then the paid
 *       resource is served with an {@code X-Payment-Response} receipt header.
 * </ul>
 *
 * <p>{@code permitAll} in {@code SecurityConfig}: the endpoint IS the credential check - the
 * payment authorizes the request. The offer lookup is schema-free ({@code
 * control_plane.x402_offer}); settlement hops into the buyer's and seller's tenant schemas
 * explicitly.
 */
@RestController
public class X402ResourceController {

    private static final Logger log = LoggerFactory.getLogger(X402ResourceController.class);

    private final X402SellerService sellerService;
    private final X402RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = JsonMappers.plain();

    public X402ResourceController(X402SellerService sellerService, X402RateLimiter rateLimiter) {
        this.sellerService = sellerService;
        this.rateLimiter = rateLimiter;
    }

    @RequestMapping(
            value = "/x402/{slug}",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Object> paidResource(
            @PathVariable String slug, HttpServletRequest request) {
        // The surface is permitAll by design - bound anonymous request volume
        // per client IP before doing any verification work.
        if (!rateLimiter.tryAcquire(clientKey(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                            Map.of(
                                    "error",
                                    Map.of(
                                            "code", "RATE_LIMITED",
                                            "message", "too many requests - retry shortly")));
        }
        X402Offer offer = sellerService.findOffer(slug).orElse(null);
        if (offer == null) {
            // Unknown/disabled slug 404s - a published-then-disabled offer
            // must not reveal that it once existed.
            return ResponseEntity.notFound().build();
        }
        String resourceUrl = resourceUrl(request, slug);
        String xPayment = request.getHeader("X-PAYMENT");
        if (xPayment == null || xPayment.isBlank()) {
            Map<String, Object> requirements =
                    sellerService.paymentRequirements(offer, resourceUrl);
            String encoded =
                    Base64.getEncoder()
                            .encodeToString(toJson(requirements).getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("PAYMENT-REQUIRED", encoded)
                    .body(requirements);
        }
        try {
            X402PaymentRecord payment = sellerService.settle(offer, xPayment);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("offer", offer.getSlug());
            body.put("title", offer.getTitle());
            body.put(
                    "payment",
                    Map.of(
                            "id", payment.getId().toString(),
                            "scheme", payment.getScheme(),
                            "status", payment.getStatus().name(),
                            "amount", payment.getAmountAtomic()));
            Object payload = readJson(offer.getResourcePayload());
            body.put(
                    "resource",
                    payload != null
                            ? payload
                            : Map.of("note", "offer has no configured resource payload"));
            Map<String, Object> receipt =
                    Map.of(
                            "success", true,
                            "paymentId", payment.getId().toString(),
                            "scheme", payment.getScheme(),
                            "network", payment.getNetwork(),
                            "settledAt",
                                    payment.getSettledAt() == null
                                            ? ""
                                            : payment.getSettledAt().toString());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                            "X-Payment-Response",
                            Base64.getEncoder()
                                    .encodeToString(
                                            toJson(receipt).getBytes(StandardCharsets.UTF_8)))
                    .body(body);
        } catch (X402Exception e) {
            // A refused payment re-issues the requirements - the caller can
            // correct and retry. Rejections are recorded by the service.
            Map<String, Object> body =
                    new LinkedHashMap<>(sellerService.paymentRequirements(offer, resourceUrl));
            body.put("error", Map.of("code", e.getCode(), "message", e.getMessage()));
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (RuntimeException e) {
            log.error("x402 settlement blew up on offer {}: {}", slug, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(
                            Map.of(
                                    "error",
                                    Map.of(
                                            "code",
                                            "SETTLEMENT_ERROR",
                                            "message",
                                            "settlement failed")));
        }
    }

    /** First forwarded hop if present, else the socket address. */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private String resourceUrl(HttpServletRequest request, String slug) {
        String proto = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host =
                firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null) {
            host = request.getServerName();
        }
        return proto + "://" + host + "/x402/" + slug;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.split(",")[0].trim() : b;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object readJson(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}

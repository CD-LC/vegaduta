package com.agenticai.core.omnichannel.email;

import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Public, unauthenticated unsubscribe endpoint ({@code permitAll} in {@code
 * SecurityConfig}). The HMAC token from {@link EmailUnsubscribeTokenService}
 * is the only credential.
 *
 * <p>GET only renders a confirmation button, it never mutates - mail scanners
 * and link prefetchers open every URL in an email, and must not unsubscribe
 * people. POST performs the suppression and is also the RFC 8058 one-click
 * target advertised via {@code List-Unsubscribe-Post}: the token rides in the
 * query string, so a client's {@code List-Unsubscribe=One-Click} body works.
 */
@RestController
@RequestMapping(EmailUnsubscribeTokenService.PATH)
public class EmailUnsubscribeController {

    private static final Logger log = LoggerFactory.getLogger(EmailUnsubscribeController.class);

    private final EmailUnsubscribeTokenService tokens;
    private final EmailSuppressionService suppressionService;

    public EmailUnsubscribeController(EmailUnsubscribeTokenService tokens, EmailSuppressionService suppressionService) {
        this.tokens = tokens;
        this.suppressionService = suppressionService;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(@RequestParam("t") String token) {
        if (tokens.verify(token).isEmpty()) {
            return page(404, "Link not valid", "This unsubscribe link is invalid or has expired.");
        }
        String form = "<form method=\"post\" action=\"" + EmailUnsubscribeTokenService.PATH + "?t="
            + HtmlUtils.htmlEscape(token) + "\"><button type=\"submit\">Confirm unsubscribe</button></form>";
        return page(200, "Unsubscribe", "Click below to stop receiving these emails." + form);
    }

    @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam("t") String token) {
        Optional<EmailUnsubscribeTokenService.Claims> claims = tokens.verify(token);
        if (claims.isEmpty()) {
            return page(404, "Link not valid", "This unsubscribe link is invalid or has expired.");
        }
        TenantContext.set(TenantContext.of(claims.get().tenantId(), "system:email-unsubscribe", Set.of()));
        try {
            suppressionService.suppress(claims.get().email(), EmailSuppression.REASON_UNSUBSCRIBE);
        } catch (RuntimeException e) {
            log.warn("Email unsubscribe failed for a verified token", e);
            return page(500, "Something went wrong", "We could not process your request. Please try again later.");
        } finally {
            TenantContext.clear();
        }
        return page(200, "Unsubscribed", "You have been unsubscribed and will not receive further emails.");
    }

    private static ResponseEntity<String> page(int status, String title, String bodyHtml) {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta name=\"robots\" content=\"noindex\"><title>" + title + "</title></head>"
            + "<body style=\"font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem\">"
            + "<h1>" + title + "</h1><p>" + bodyHtml + "</p></body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(html);
    }
}

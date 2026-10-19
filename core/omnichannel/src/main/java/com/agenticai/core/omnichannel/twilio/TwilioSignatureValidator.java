package com.agenticai.core.omnichannel.twilio;

import com.twilio.security.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Verifies an inbound Twilio webhook request actually came from Twilio, per
 * <a href="https://www.twilio.com/docs/usage/webhooks/webhooks-security">Twilio's
 * documented HMAC-SHA1 scheme</a>: Twilio signs the exact URL it called plus
 * the POSTed form parameters using the account's auth token, and sends the
 * result as {@code X-Twilio-Signature}. Uses the Twilio SDK's own {@code
 * RequestValidator} rather than reimplementing the HMAC scheme by hand.
 *
 * <p><b>The URL Twilio signed against must match exactly</b> - scheme, host,
 * port, path. Behind a reverse proxy or an ngrok tunnel (the normal way to
 * expose a local {@code core} to Twilio for testing - Twilio needs a public
 * HTTPS URL), the URL this server reconstructs from the incoming request can
 * differ from what Twilio actually called (e.g. Tomcat sees {@code
 * http://localhost:8080/...} while Twilio called {@code
 * https://abcd1234.ngrok.io/...}). {@code app.omnichannel.public-base-url}
 * exists specifically to override the reconstructed URL with the real public
 * one in that situation - see the README's ngrok verification steps.
 */
@Component
public class TwilioSignatureValidator {

    private final String publicBaseUrlOverride;

    public TwilioSignatureValidator(
        @Value("${app.omnichannel.public-base-url:}") String publicBaseUrlOverride
    ) {
        this.publicBaseUrlOverride = publicBaseUrlOverride;
    }

    public void validateOrThrow(
        HttpServletRequest request, Map<String, String> params, String signatureHeader, String authToken
    ) {
        if (!StringUtils.hasText(signatureHeader)) {
            throw new TwilioWebhookSignatureException("Missing X-Twilio-Signature header - request did not come through Twilio.");
        }
        String url = resolveSignedUrl(request);
        boolean valid = new RequestValidator(authToken).validate(url, params, signatureHeader);
        if (!valid) {
            throw new TwilioWebhookSignatureException(
                "X-Twilio-Signature did not match for URL " + url + " - either this request isn't really from Twilio, "
                    + "or app.omnichannel.public-base-url doesn't match the public URL Twilio actually called (see class javadoc)."
            );
        }
    }

    /**
     * {@code HttpServletRequest#getRequestURL()} (and {@code
     * #getRequestURI()}, used on the override branch) never include the
     * query string, by servlet spec - but Twilio's own signing scheme signs
     * the exact URL it called, INCLUDING any query string, when one is
     * present. Every webhook this validator guarded until the voice-menu
     * fast-follow (V308) used a bare action URL with no query string, so
     * this omission was invisible; {@code TwilioVoiceWebhookController}'s
     * {@code /voice/menu-collect} action URL is the first to carry one (a
     * {@code requestId} correlating the DTMF collection to its pending
     * {@code workflow_voice_menu_request} row - the standard, Twilio-
     * documented way to thread per-gather context through an action URL).
     * Appending the query string here, on both branches, is the fix.
     */
    private String resolveSignedUrl(HttpServletRequest request) {
        String query = request.getQueryString();
        String querySuffix = StringUtils.hasText(query) ? "?" + query : "";
        if (StringUtils.hasText(publicBaseUrlOverride)) {
            String base = publicBaseUrlOverride.replaceAll("/+$", "");
            return base + request.getRequestURI() + querySuffix;
        }
        return request.getRequestURL().toString() + querySuffix;
    }
}

package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.WhatsAppDeliveryRejectedException;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Meta Graph API calls this platform needs for WhatsApp Cloud API sending, plus (as of Embedded
 * Signup, see below) the platform-level OAuth code exchange - no SDK dependency, same "plain {@link
 * HttpClient} for a handful of REST calls" choice {@code TelegramOutputService} already makes, for
 * the identical reason: pulling in Meta's (Facebook's) Java Business SDK for this few endpoints
 * would be more dependency than this surface area justifies.
 *
 * <p><b>Two ways a credential ends up activated</b>, both converging on the same {@code
 * MetaWhatsAppCredentialService.activate(MetaActivationRequest)}:
 *
 * <ul>
 *   <li>Manual BYOK (original, still fully supported, never removed): a tenant creates their own
 *       Meta App at developers.facebook.com, adds the WhatsApp product, and pastes the resulting
 *       Phone Number ID/WABA ID/ access token/App Secret into {@code
 *       MetaWhatsAppCredentialController} directly.
 *   <li>Embedded Signup (this class's {@link #exchangeCodeForToken} and {@link
 *       #subscribeAppToWaba}): the tenant instead clicks one button, completes Meta's own hosted
 *       signup popup (Facebook Login for Business), and this platform - using ITS OWN Meta App, not
 *       the tenant's - exchanges the resulting authorization code for a token and subscribes to
 *       that WABA's webhooks on the tenant's behalf. See {@code MetaEmbeddedSignupService}'s
 *       javadoc for the full choreography and, importantly, what the platform operator (not each
 *       tenant) must set up once with Meta before this path works at all - App Review approval for
 *       the embedded-signup extension, Business verification, and a live HTTPS webhook domain are
 *       all real Meta-side requirements, not something either this class or a tenant can shortcut.
 * </ul>
 */
@Component
public class MetaGraphApiClient {

    private static final String GRAPH_VERSION = "v21.0";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;

    /**
     * Optional {@code meta-graph} circuit breaker (default off); see {@link MetaGraphGuard}.
     * Setter-injected so tests that build this by hand are untouched.
     */
    private volatile CircuitBreakerRegistry breakerRegistry;

    public MetaGraphApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
        this.breakerRegistry = registry;
    }

    /**
     * Validates a phone number ID + access token pair with a real, non-billed GET call (same "prove
     * the credential works before persisting it" reasoning as {@code
     * TwilioCredentialService.validate}), and returns the number's own display info to store
     * alongside the credential for a friendlier console display than a raw numeric ID.
     */
    public PhoneNumberInfo fetchPhoneNumberInfo(String phoneNumberId, String accessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "https://graph.facebook.com/"
                                                + GRAPH_VERSION
                                                + "/"
                                                + phoneNumberId
                                                + "?fields=display_phone_number,verified_name"))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelConfigurationException(
                        "whatsapp",
                        "Meta rejected this Phone Number ID/access token ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            return new PhoneNumberInfo(
                    node.path("display_phone_number").asString(null),
                    node.path("verified_name").asString(null));
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    /** Meta's {@code error.code} from a failed response body, or 0 when absent or unparseable. */
    static int metaErrorCode(String body) {
        try {
            return new tools.jackson.databind.ObjectMapper()
                    .readTree(body)
                    .path("error")
                    .path("code")
                    .asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The phone number's {@code quality_rating} and {@code status} (documented fields of the phone
     * number node). Either may be null when Meta omits it.
     */
    public PhoneNumberHealth fetchPhoneNumberHealth(String phoneNumberId, String accessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "https://graph.facebook.com/"
                                                + GRAPH_VERSION
                                                + "/"
                                                + urlEncode(phoneNumberId)
                                                + "?fields=quality_rating,status"))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelConfigurationException(
                        "whatsapp",
                        "Meta would not return this number's quality rating ("
                                + response.statusCode()
                                + ")");
            }
            JsonNode node = objectMapper.readTree(response.body());
            return new PhoneNumberHealth(
                    node.path("quality_rating").asString(null), node.path("status").asString(null));
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    public record PhoneNumberHealth(String qualityRating, String status) {}

    /**
     * @return a short, human-readable log line describing what happened.
     */
    public String sendTextMessage(
            String phoneNumberId, String accessToken, String to, String body) {
        try {
            Map<String, Object> payload =
                    Map.of(
                            "messaging_product",
                            "whatsapp",
                            "to",
                            normalizeRecipient(to),
                            "type",
                            "text",
                            "text",
                            Map.of("body", body));
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "https://graph.facebook.com/"
                                                    + GRAPH_VERSION
                                                    + "/"
                                                    + phoneNumberId
                                                    + "/messages"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "whatsapp",
                        "Meta Graph API send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            // Direct feedback: "is that message sent?" - Meta's success
            // response body carries a real message id (messages[0].id, a
            // "wamid...." string) proving Meta actually accepted it, same
            // parity as the Twilio path's SID. Best-effort parse: if the
            // response shape ever changes, this degrades to "no id" rather
            // than failing a send that Meta already accepted (statusCode was
            // already checked above).
            String messageId = "unknown";
            try {
                JsonNode messages = objectMapper.readTree(response.body()).path("messages");
                if (messages.isArray() && messages.size() > 0) {
                    messageId = messages.get(0).path("id").asString("unknown");
                }
            } catch (Exception ignored) {
                // Leave messageId as "unknown" - see comment above.
            }
            return "Sent \""
                    + truncate(body, 160)
                    + "\" to "
                    + to
                    + " via Meta Cloud API (id "
                    + messageId
                    + ").";
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    /**
     * Sends a pre-approved message template (Meta's "HSM"). This is the ONLY kind of message the
     * Cloud API delivers to a customer who has not written in during the last 24 hours, which is
     * exactly the scheduled-reminder case a free-text {@link #sendTextMessage} cannot serve.
     *
     * <p>Supports templates whose variables are positional BODY placeholders ({{1}}, {{2}}, ...). A
     * template with header or button variables, or named parameters, is rejected by Meta with a
     * parameter-mismatch error, which surfaces as the usual {@link ChannelDeliveryException}.
     *
     * @param bodyParams values for {{1}}..{{n}} in order; null or empty for a template without
     *     variables
     * @return a short, human-readable log line describing what happened.
     */
    public String sendTemplateMessage(
            String phoneNumberId,
            String accessToken,
            String to,
            String templateName,
            String languageCode,
            List<String> bodyParams) {
        try {
            Map<String, Object> payload =
                    templatePayload(normalizeRecipient(to), templateName, languageCode, bodyParams);
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "https://graph.facebook.com/"
                                                    + GRAPH_VERSION
                                                    + "/"
                                                    + phoneNumberId
                                                    + "/messages"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new WhatsAppDeliveryRejectedException(
                        "Meta Graph API template send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body(),
                        metaErrorCode(response.body()));
            }
            // Same best-effort id parse as sendTextMessage: Meta already accepted the send.
            String messageId = "unknown";
            try {
                JsonNode messages = objectMapper.readTree(response.body()).path("messages");
                if (messages.isArray() && messages.size() > 0) {
                    messageId = messages.get(0).path("id").asString("unknown");
                }
            } catch (Exception ignored) {
                // Leave messageId as "unknown".
            }
            return "Sent template \""
                    + templateName
                    + "\" ("
                    + languageCode
                    + ") to "
                    + to
                    + " via Meta Cloud API (id "
                    + messageId
                    + ").";
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    /**
     * The Cloud API request body for a template send. Static and package-private so the wire shape
     * is unit-tested without an HTTP round trip. {@code digitsOnlyTo} is already normalised.
     */
    static Map<String, Object> templatePayload(
            String digitsOnlyTo,
            String templateName,
            String languageCode,
            List<String> bodyParams) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", languageCode));
        if (bodyParams != null && !bodyParams.isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String value : bodyParams) {
                // Meta rejects an empty text parameter outright; a single space keeps the slot
                // filled.
                String text = normalizeTemplateParam(value);
                parameters.add(Map.of("type", "text", "text", text.isEmpty() ? " " : text));
            }
            template.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", digitsOnlyTo);
        payload.put("type", "template");
        payload.put("template", template);
        return payload;
    }

    /**
     * A template text parameter as Meta accepts it: no newlines or tabs and no run of more than
     * four spaces (Meta rejects the whole send otherwise, and expression-resolved values such as
     * addresses or notes often contain them). Each is replaced by / collapsed to a single space.
     */
    static String normalizeTemplateParam(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ");
    }

    /** Upper bound on pages followed when listing templates (200 per page). */
    static final int MAX_TEMPLATE_PAGES = 5;

    /**
     * The WhatsApp Business Account's APPROVED message templates that {@link #sendTemplateMessage}
     * can actually send (see {@link #parseApprovedTemplates}). Needs the token to carry {@code
     * whatsapp_business_management}; without it Meta answers 4xx and this throws. Follows Meta's
     * cursor paging for at most {@link #MAX_TEMPLATE_PAGES} pages; the cursor is appended to our
     * own URL, so the bearer token is never sent to an address taken from a response.
     */
    public List<MessageTemplate> listMessageTemplates(String wabaId, String accessToken) {
        List<MessageTemplate> out = new ArrayList<>();
        String after = null;
        try {
            for (int page = 0; page < MAX_TEMPLATE_PAGES; page++) {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                "https://graph.facebook.com/"
                                                        + GRAPH_VERSION
                                                        + "/"
                                                        + urlEncode(wabaId)
                                                        + "/message_templates?fields=name,language,status,category,components,parameter_format"
                                                        + "&status=APPROVED&limit=200"
                                                        + (after == null
                                                                ? ""
                                                                : "&after=" + urlEncode(after))))
                                .timeout(TIMEOUT)
                                .header("Authorization", "Bearer " + accessToken)
                                .GET()
                                .build();
                HttpResponse<String> response =
                        MetaGraphGuard.send(breakerRegistry, httpClient, request);
                if (response.statusCode() >= 300) {
                    throw new ChannelConfigurationException(
                            "whatsapp",
                            "Meta would not list this account's message templates ("
                                    + response.statusCode()
                                    + "): "
                                    + response.body());
                }
                JsonNode root = objectMapper.readTree(response.body());
                out.addAll(parseApprovedTemplates(root));
                after = nextTemplateCursor(root);
                if (after == null) {
                    break;
                }
            }
            return out;
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    /** The {@code after} cursor of a paged response, or null when this was the last page. */
    static String nextTemplateCursor(JsonNode root) {
        JsonNode paging = root == null ? null : root.path("paging");
        if (paging == null || paging.path("next").asString("").isBlank()) {
            return null;
        }
        String after = paging.path("cursors").path("after").asString("");
        return after.isBlank() ? null : after;
    }

    private static final Set<String> UNSENDABLE_BUTTON_TYPES =
            Set.of("COPY_CODE", "OTP", "FLOW", "CATALOG", "MPM");

    /**
     * True when {@code entry} can be sent with positional BODY text parameters alone, which is all
     * {@link #sendTemplateMessage} builds: no named parameters, no media / variable header, no
     * button that needs a parameter, no carousel or limited-time-offer component.
     */
    static boolean sendableWithBodyParamsOnly(JsonNode entry) {
        if ("NAMED".equalsIgnoreCase(entry.path("parameter_format").asString(""))) {
            return false;
        }
        for (JsonNode component : entry.path("components")) {
            String type = component.path("type").asString("").toUpperCase(java.util.Locale.ROOT);
            switch (type) {
                case "HEADER" -> {
                    String format = component.path("format").asString("TEXT");
                    if (!"TEXT".equalsIgnoreCase(format)
                            || component.path("text").asString("").contains("{{")) {
                        return false;
                    }
                }
                case "BODY" -> {
                    Matcher matcher =
                            TEMPLATE_PLACEHOLDER.matcher(component.path("text").asString(""));
                    while (matcher.find()) {
                        if (!matcher.group(1).chars().allMatch(Character::isDigit)) {
                            return false;
                        }
                    }
                }
                case "BUTTONS" -> {
                    for (JsonNode button : component.path("buttons")) {
                        String buttonType =
                                button.path("type").asString("").toUpperCase(java.util.Locale.ROOT);
                        if (UNSENDABLE_BUTTON_TYPES.contains(buttonType)
                                || button.path("url").asString("").contains("{{")) {
                            return false;
                        }
                    }
                }
                case "CAROUSEL", "LIMITED_TIME_OFFER" -> {
                    return false;
                }
                default -> {
                    // FOOTER and anything static needs no parameter.
                }
            }
        }
        return true;
    }

    private static final Pattern TEMPLATE_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");

    /**
     * APPROVED entries of a {@code message_templates} response that can be sent with body
     * parameters alone; anything else (PENDING, REJECTED, PAUSED, or a template needing header /
     * button / named parameters - see {@link #sendableWithBodyParamsOnly}) is dropped so the picker
     * never offers a template that would fail at send time.
     */
    static List<MessageTemplate> parseApprovedTemplates(JsonNode root) {
        List<MessageTemplate> out = new ArrayList<>();
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray()) {
            return out;
        }
        for (JsonNode entry : data) {
            String name = entry.path("name").asString("");
            String language = entry.path("language").asString("");
            if (name.isBlank()
                    || language.isBlank()
                    || !"APPROVED".equalsIgnoreCase(entry.path("status").asString(""))
                    || !sendableWithBodyParamsOnly(entry)) {
                continue;
            }
            String body = "";
            for (JsonNode component : entry.path("components")) {
                if ("BODY".equalsIgnoreCase(component.path("type").asString(""))) {
                    body = component.path("text").asString("");
                }
            }
            Set<String> placeholders = new LinkedHashSet<>();
            Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(body);
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
            out.add(
                    new MessageTemplate(
                            name,
                            language,
                            entry.path("category").asString(null),
                            body,
                            placeholders.size()));
        }
        return out;
    }

    /**
     * One approved template: {@code bodyText} is its BODY with the {{n}} placeholders still in it.
     */
    public record MessageTemplate(
            String name, String language, String category, String bodyText, int bodyParamCount) {}

    // Meta's `to` field wants digits only (country code + number, no "+",
    // no "whatsapp:" prefix) - unlike Twilio's `whatsapp:+1555...` convention.
    private String normalizeRecipient(String to) {
        return to == null ? "" : to.replaceAll("[^0-9]", "");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /**
     * Embedded Signup step 1: trades the short-lived authorization {@code code} the Facebook JS
     * SDK's {@code FB.login} callback returns for an access token, using the PLATFORM's own Meta
     * App credentials (never a tenant's) - this is the defining difference from manual BYOK, where
     * the tenant's own app credentials never pass through this platform's backend at all.
     *
     * <p>The token Meta returns for an Embedded-Signup-flow code is already a long-lived System
     * User token in the standard product configuration - no separate {@code fb_exchange_token} step
     * is included here. If your Meta App's Embedded Signup configuration differs (check the current
     * Meta docs - this API has shifted before and will again), add that second exchange call here
     * rather than assuming this one always suffices.
     */
    public String exchangeCodeForToken(
            String code, String platformAppId, String platformAppSecret, String redirectUri) {
        String uri =
                "https://graph.facebook.com/"
                        + GRAPH_VERSION
                        + "/oauth/access_token"
                        + "?client_id="
                        + urlEncode(platformAppId)
                        + "&client_secret="
                        + urlEncode(platformAppSecret)
                        + "&redirect_uri="
                        + urlEncode(redirectUri)
                        + "&code="
                        + urlEncode(code);
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(uri)).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelConfigurationException(
                        "whatsapp",
                        "Meta rejected the Embedded Signup authorization code ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            String accessToken = node.path("access_token").asString(null);
            if (accessToken == null) {
                throw new ChannelConfigurationException(
                        "whatsapp",
                        "Meta's token exchange response had no access_token: " + response.body());
            }
            return accessToken;
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "whatsapp", "Could not reach Meta's OAuth token endpoint: " + e.getMessage());
        }
    }

    /**
     * Embedded Signup step 2: subscribes the platform's Meta App to this WABA's webhook events.
     * Without this call, Meta never sends inbound message/status webhooks for a WABA connected via
     * Embedded Signup at all - unlike manual BYOK, where the tenant did this themselves from their
     * own Meta App dashboard when they first configured the webhook subscription.
     */
    public void subscribeAppToWaba(String wabaId, String accessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "https://graph.facebook.com/"
                                                + GRAPH_VERSION
                                                + "/"
                                                + wabaId
                                                + "/subscribed_apps"))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelConfigurationException(
                        "whatsapp",
                        "Could not subscribe to this WhatsApp Business Account's webhooks ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record PhoneNumberInfo(String displayPhoneNumber, String verifiedName) {}

    // --- Social (Messenger + Instagram) - added alongside MetaSocialCredentialService.
    // Reuses this same client rather than a separate one: identical HTTP
    // plumbing (plain HttpClient, same Graph API host/version, same
    // Authorization-header-Bearer-token convention), just different
    // endpoints/payload shapes than the WhatsApp Cloud API calls above.

    /**
     * Validates a Page ID + Page Access Token pair with a real, non-billed GET call - same "prove
     * it before persisting" reasoning as {@link #fetchPhoneNumberInfo}. Also confirms the token
     * actually has messaging permission on this Page (Meta returns 400/403 for a token that
     * doesn't), which is exactly the failure mode worth catching at activation time rather than on
     * the tenant's first real send.
     */
    public PageInfo fetchPageInfo(String pageId, String pageAccessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "https://graph.facebook.com/"
                                                + GRAPH_VERSION
                                                + "/"
                                                + pageId
                                                + "?fields=name"))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + pageAccessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelConfigurationException(
                        "messenger",
                        "Meta rejected this Page ID/access token ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            return new PageInfo(node.path("name").asString(null));
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "messenger", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    /**
     * Looks up the Instagram Professional account's own username for a friendlier console display
     * than a raw numeric ID - purely cosmetic, failures here don't block activation (see {@code
     * MetaSocialCredentialService.activate}, which treats this as best-effort).
     */
    public String fetchInstagramUsername(
            String instagramBusinessAccountId, String pageAccessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "https://graph.facebook.com/"
                                                + GRAPH_VERSION
                                                + "/"
                                                + instagramBusinessAccountId
                                                + "?fields=username"))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + pageAccessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                return null;
            }
            return objectMapper.readTree(response.body()).path("username").asString(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sends a Messenger text message via the Send API. {@code messaging_type: RESPONSE} is the
     * correct type for "replying to something the user sent us" (as opposed to {@code UPDATE} or a
     * message-tag-gated proactive send) - the only mode this platform's approval-gated auto-reply
     * flow ever uses.
     */
    public String sendMessengerMessage(String pageAccessToken, String recipientPsid, String text) {
        return sendViaMessagesEndpoint(
                "messenger", pageAccessToken, recipientPsid, text, "Sent Messenger message to");
    }

    /**
     * Sends an Instagram DM. Structurally the exact same Send API call as Messenger (Meta proxies
     * Instagram business messaging through the linked Page's token) - kept as a separate method
     * rather than one generic {@code sendMessage(platform, ...)} because the two platforms' payload
     * shapes have already diverged before (quick replies, generic templates) and are likely to
     * again; a shared private helper below keeps the actual HTTP call from being duplicated in the
     * meantime.
     */
    public String sendInstagramMessage(String pageAccessToken, String recipientIgsid, String text) {
        return sendViaMessagesEndpoint(
                "instagram", pageAccessToken, recipientIgsid, text, "Sent Instagram DM to");
    }

    private String sendViaMessagesEndpoint(
            String channelLabel,
            String pageAccessToken,
            String recipientId,
            String text,
            String successVerb) {
        try {
            Map<String, Object> payload =
                    Map.of(
                            "recipient", Map.of("id", recipientId),
                            "message", Map.of("text", text),
                            "messaging_type", "RESPONSE");
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "https://graph.facebook.com/"
                                                    + GRAPH_VERSION
                                                    + "/me/messages"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + pageAccessToken)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        channelLabel,
                        "Meta Graph API send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            return successVerb + " " + recipientId + ".";
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    channelLabel, "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    public record PageInfo(String name) {}
}

package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link GoogleGmailClient} request-mapping and error-translation tests on the injected stub
 * transport (see {@link HttpStubs} for why not MockRestServiceServer). {@link GoogleOAuthService}
 * is a Mockito mock handing out a fixed access token, so no OAuth machinery or DB is touched.
 *
 * <p>The regression this file cares most about is {@code sendMessage}'s RFC 2822 assembly: the
 * "raw" field must be base64url (not standard base64 - Gmail rejects {@code +}/{@code /}), and the
 * headers/body must be CRLF separated - both invisible in code review if subtly broken, both fatal
 * at the API.
 */
class GoogleGmailClientTest {

    private static final String TOKEN = "at-123";

    private GoogleOAuthService oauthService;
    private GoogleGmailClient client;
    private HttpStubs.RecordingHttpClient http;
    private final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();

    @BeforeEach
    void setUp() {
        oauthService = mock(GoogleOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn(TOKEN);
        client = new GoogleGmailClient(objectMapper, oauthService);
        http = HttpStubs.install(client);
    }

    // ---- send ---------------------------------------------------------------

    @Test
    void sendMessagePostsBase64UrlRfc2822PayloadWithBearerAuth() throws Exception {
        http.enqueue(200, "{\"id\":\"m-sent\"}");

        String result = client.sendMessage("dev@example.com", "Build status", "All green.");

        HttpRequest request = http.onlyRequest();
        assertEquals(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElse(null));
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));

        JsonNode body = objectMapper.readTree(HttpStubs.bodyOf(request));
        String raw = body.path("raw").asString();
        // Must decode with the URL decoder - if the client ever regressed to
        // standard base64, '+'/'/' chars would make this decode blow up.
        String rfc2822 = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        assertEquals(
                "To: dev@example.com\r\nSubject: Build status\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nAll green.",
                rfc2822);
        assertEquals("Sent email to dev@example.com via Gmail.", result);
    }

    @Test
    void sendForbiddenByMissingScopeIsATypedErrorCarryingGooglesAnswer() {
        // A tenant that granted gmail.readonly but not gmail.send gets a 403
        // from Google - the tool layer must receive a GoogleApiException whose
        // message says what failed and why, not a raw HTTP stack.
        http.enqueue(
                403,
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Insufficient Permission\"}}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.sendMessage("dev@example.com", "Hi", "Body"));

        assertTrue(ex.getMessage().contains("Gmail send failed (403)"));
        assertTrue(ex.getMessage().contains("Insufficient Permission"));
    }

    // ---- list + read --------------------------------------------------------

    @Test
    void listRecentClampsMaxResultsEncodesQueryAndHydratesEachSummary() {
        http.enqueue(200, "{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}");
        http.enqueue(
                200,
                "{\"snippet\":\"first snippet\",\"payload\":{\"headers\":["
                        + "{\"name\":\"Subject\",\"value\":\"Hello\"},{\"name\":\"From\",\"value\":\"a@example.com\"}]}}");
        http.enqueue(
                200,
                "{\"snippet\":\"second snippet\",\"payload\":{\"headers\":["
                        + "{\"name\":\"SUBJECT\",\"value\":\"Case test\"}]}}");

        List<GoogleGmailClient.MessageSummary> summaries = client.listRecent("is:unread", 99);

        // 99 must clamp to the API's max of 10 - an agent asking for "all my
        // mail" shouldn't trigger a Gmail 400.
        assertEquals(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=10&q=is%3Aunread",
                http.requests.get(0).uri().toString());
        // Each listed id is hydrated via a metadata-only fetch (subject/from,
        // not full bodies).
        assertEquals(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/m1?format=metadata&metadataHeaders=Subject&metadataHeaders=From",
                http.requests.get(1).uri().toString());
        assertEquals(3, http.requests.size());

        assertEquals(2, summaries.size());
        assertEquals(
                new GoogleGmailClient.MessageSummary(
                        "m1", "a@example.com", "Hello", "first snippet"),
                summaries.get(0));
        // Header-name matching is case-insensitive; a missing From maps to null.
        assertEquals(
                new GoogleGmailClient.MessageSummary("m2", null, "Case test", "second snippet"),
                summaries.get(1));
    }

    @Test
    void readMessageExtractsPlainTextFromNestedMultipartParts() {
        String plainText =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "Hello from the plain part".getBytes(StandardCharsets.UTF_8));
        // Realistic multipart/alternative: html part first, text/plain nested
        // deeper - the walker must find text/plain wherever it hides.
        http.enqueue(
                200,
                "{\"snippet\":\"fallback snippet\",\"payload\":{\"mimeType\":\"multipart/alternative\",\"parts\":["
                        + "{\"mimeType\":\"text/html\",\"body\":{\"data\":\"aGk=\"}},"
                        + "{\"mimeType\":\"multipart/related\",\"parts\":["
                        + "{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""
                        + plainText
                        + "\"}}]}]}}");

        String text = client.readMessage("m1");

        assertEquals("Hello from the plain part", text);
        assertEquals(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/m1?format=full",
                http.onlyRequest().uri().toString());
    }

    @Test
    void readMessageFallsBackToSnippetWhenNoPlainTextPartExists() {
        http.enqueue(
                200,
                "{\"snippet\":\"only html here\",\"payload\":{\"mimeType\":\"text/html\","
                        + "\"body\":{\"data\":\"aGk=\"}}}");

        assertEquals("only html here", client.readMessage("m1"));
    }

    @Test
    void rateLimitedReadTranslatesToGoogleApiExceptionWithStatus() {
        http.enqueue(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");

        GoogleApiException ex =
                assertThrows(GoogleApiException.class, () -> client.readMessage("m1"));

        assertTrue(ex.getMessage().contains("Gmail API call failed (429)"));
        assertTrue(ex.getMessage().contains("RESOURCE_EXHAUSTED"));
    }

    // ---- not-connected propagation ------------------------------------------

    @Test
    void notConnectedTenantFailsBeforeAnyHttpCallWithTheActionableMessage() {
        // The token is resolved while building the request, so the clean
        // "connect on the Integrations page" error reaches the tool layer
        // without a single byte sent to Gmail.
        when(oauthService.currentAccessToken()).thenThrow(new GoogleNotConnectedException());

        GoogleNotConnectedException ex =
                assertThrows(GoogleNotConnectedException.class, () -> client.listRecent("", 5));

        assertTrue(ex.getMessage().contains("Integrations page"));
        assertTrue(http.requests.isEmpty(), "no request may be sent without a token");
    }
}

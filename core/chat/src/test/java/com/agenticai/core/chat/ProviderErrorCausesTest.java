package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The cause-chain readers behind {@link ProviderErrorTranslator}: the two message shapes a Spring
 * AI provider client is known to encode an upstream status into, the missing-tool-callback
 * signature, the timeout type hunt, and the readable chain description - each walked to {@link
 * ProviderErrorCauses#MAX_CAUSE_DEPTH} and guarded against a self-referential cause.
 */
class ProviderErrorCausesTest {

    // --- findCause ---------------------------------------------------------

    @Test
    void findCauseReturnsTheErrorItselfWhenItAlreadyMatches() {
        IOException io = new IOException("disk");
        assertSame(io, ProviderErrorCauses.findCause(io, IOException.class));
    }

    @Test
    void findCauseFindsATypedFailureBuriedUnderGenericWrappers() {
        WebClientResponseException wcre =
                WebClientResponseException.create(
                        500,
                        "Server Error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8);
        RuntimeException wrapped = new RuntimeException("outer", new RuntimeException("mid", wcre));

        assertSame(wcre, ProviderErrorCauses.findCause(wrapped, WebClientResponseException.class));
    }

    private static RuntimeException selfCaused() {
        return new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
    }

    @Test
    void findCauseReturnsNullWhenNothingMatchesAndSurvivesASelfCause() {
        assertNull(ProviderErrorCauses.findCause(selfCaused(), IOException.class));
        assertNull(ProviderErrorCauses.findCause(new RuntimeException("plain"), IOException.class));
    }

    @Test
    void findCauseStopsAtTheDepthCap() {
        Throwable current = new IOException("too deep");
        for (int i = 0; i < ProviderErrorCauses.MAX_CAUSE_DEPTH + 5; i++) {
            current = new RuntimeException("wrap-" + i, current);
        }
        assertNull(ProviderErrorCauses.findCause(current, IOException.class));
    }

    // --- findStructuredStatus ----------------------------------------------

    @Test
    void theAnthropicResponseExceptionShapeYieldsStatusAndBody() {
        RuntimeException e =
                new RuntimeException(
                        "Response exception, Status: [529 Service Unavailable], Body:[{\"error\":\"overloaded\"}]");

        ProviderErrorCauses.StatusAndBody found = ProviderErrorCauses.findStructuredStatus(e);

        assertEquals(529, found.status());
        assertEquals("{\"error\":\"overloaded\"}", found.body());
    }

    @Test
    void theSpringAiRetryMessageShapeYieldsStatusAndBody() {
        // NonTransient/TransientAiException's ENTIRE message is "<status> - <body>".
        RuntimeException e =
                new RuntimeException(
                        "Failed to generate content",
                        new RuntimeException(
                                "404 - {\"error\":{\"message\":\"models/x is not found\"}}"));

        ProviderErrorCauses.StatusAndBody found = ProviderErrorCauses.findStructuredStatus(e);

        assertEquals(404, found.status());
        assertEquals("{\"error\":{\"message\":\"models/x is not found\"}}", found.body());
    }

    @Test
    void theAnthropicShapeWinsAtTheSameChainLevel() {
        // A message matching BOTH would be odd, but RESPONSE_EXCEPTION_PATTERN is tried first
        // deliberately - the more specific of the two shapes.
        RuntimeException e = new RuntimeException("404 - Status: [503 x], Body:[down]");

        ProviderErrorCauses.StatusAndBody found = ProviderErrorCauses.findStructuredStatus(e);

        assertEquals(503, found.status());
    }

    @Test
    void noRecognisedShapeAnywhereInTheChainIsANullSignalNotAGuess() {
        assertNull(
                ProviderErrorCauses.findStructuredStatus(new RuntimeException("connection reset")));
        assertNull(ProviderErrorCauses.findStructuredStatus(new RuntimeException((String) null)));
        // A status buried mid-sentence is not the "<status> - <body>" shape (anchored at ^), and
        // a 4-digit prefix is not a 3-digit HTTP status either.
        assertNull(
                ProviderErrorCauses.findStructuredStatus(
                        new RuntimeException("error code: 500, retry later")));
        assertNull(
                ProviderErrorCauses.findStructuredStatus(
                        new RuntimeException("1234 - looks like a status but is four digits")));
    }

    @Test
    void findStructuredStatusRespectsTheDepthCapAndSelfCauseGuard() {
        Throwable current = new RuntimeException("404 - {\"error\":{}}");
        for (int i = 0; i < ProviderErrorCauses.MAX_CAUSE_DEPTH + 3; i++) {
            current = new RuntimeException("wrap", current);
        }
        assertNull(ProviderErrorCauses.findStructuredStatus(current));

        assertNull(ProviderErrorCauses.findStructuredStatus(selfCaused()));
    }

    // --- findMissingToolCallbackName -----------------------------------------

    @Test
    void theMissingToolCallbackShapeNamesTheTool() {
        RuntimeException e =
                new RuntimeException(
                        "outer",
                        new IllegalStateException(
                                "No ToolCallback found for tool name: get_weather"));

        assertEquals("get_weather", ProviderErrorCauses.findMissingToolCallbackName(e));
    }

    @Test
    void theSameMessageOnTheWrongExceptionTypeIsNotAToolCallbackMiss() {
        // Only IllegalStateException carries this meaning - a RuntimeException saying the
        // same words is a different failure that must keep its own classification.
        assertNull(
                ProviderErrorCauses.findMissingToolCallbackName(
                        new RuntimeException("No ToolCallback found for tool name: get_weather")));
        assertNull(
                ProviderErrorCauses.findMissingToolCallbackName(
                        new IllegalStateException((String) null)));
        assertNull(
                ProviderErrorCauses.findMissingToolCallbackName(
                        new IllegalStateException("other")));
    }

    // --- findTimeoutCause ----------------------------------------------------

    @Test
    void recognisedTimeoutTypesAreFoundAnywhereInTheChain() {
        assertEquals(
                TimeoutException.class,
                ProviderErrorCauses.findTimeoutCause(new TimeoutException("t")).getClass());

        SocketTimeoutException socket = new SocketTimeoutException("read");
        RuntimeException wrapped = new RuntimeException("call failed", socket);
        assertSame(socket, ProviderErrorCauses.findTimeoutCause(wrapped));
    }

    @Test
    void aTypeWhoseNameMentionsTimeoutCountsToo() {
        // Spring's HttpClientErrorException-family members and Netty's timeout types are
        // matched by class NAME, not instanceof - emulate with a local type named like one.
        Throwable named = new RuntimeException("x") {
                    // anonymous class name contains no "Timeout" - use the message-less check below
                };
        assertNull(ProviderErrorCauses.findTimeoutCause(new RuntimeException("just slow", named)));
        assertNull(
                ProviderErrorCauses.findTimeoutCause(new RuntimeException("timed out in prose")));
    }

    @Test
    void findTimeoutCauseSurvivesASelfReferentialChain() {
        assertNull(ProviderErrorCauses.findTimeoutCause(selfCaused()));
    }

    // --- describeCauseChain ---------------------------------------------------

    @Test
    void genericWrappersLoseTheirClassNameSoTheRealCauseIsNotHidden() {
        RuntimeException e =
                new RuntimeException(
                        "Failed to generate content", new IllegalStateException("the real reason"));

        String chain = ProviderErrorCauses.describeCauseChain(e);

        // "RuntimeException: " is noise and must be skipped; the specific type's name is kept.
        assertTrue(
                chain.startsWith(
                        "Failed to generate content -> IllegalStateException: the real reason"),
                chain);
    }

    @Test
    void repeatedMessagesAreNotDuplicatedInTheTrail() {
        // The wrapper and its cause carry the SAME message (Spring AI's "Failed to generate
        // content" is not it - this is a copy-paste wrapper): the duplicate is dropped whole,
        // type name and all, leaving the first occurrence plus the genuinely new detail.
        RuntimeException e =
                new RuntimeException(
                        "same", new IllegalStateException("same", new IOException("different")));

        String chain = ProviderErrorCauses.describeCauseChain(e);

        assertEquals("same -> IOException: different", chain);
        assertEquals(
                chain.indexOf("same"),
                chain.lastIndexOf("same"),
                "the duplicated message must appear exactly once");
    }

    @Test
    void aChainWithNoMessagesAtAllGetsTheHonestFallbackPhrase() {
        String chain = ProviderErrorCauses.describeCauseChain(new RuntimeException((String) null));

        assertEquals("no further detail available", chain);
    }

    @Test
    void blankMessagesAreSkippedLikeAbsentOnes() {
        RuntimeException e = new RuntimeException("   ", new IllegalStateException("real"));

        assertEquals("IllegalStateException: real", ProviderErrorCauses.describeCauseChain(e));
    }
}

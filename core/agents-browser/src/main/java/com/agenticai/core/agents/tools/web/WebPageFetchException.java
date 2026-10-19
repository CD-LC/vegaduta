package com.agenticai.core.agents.tools.web;

/**
 * A web-page fetch that could not produce usable text, carrying a SHORT, person/model-facing reason
 * ("the URL points inside the platform's own network", "the page returned HTTP 404", ...) rather
 * than a raw stack-trace message. {@link FetchWebPageToolCallback} catches this and turns it into
 * the plain-text tool result {@code "Could not fetch <url>: <reason>"} - the same "a failure must
 * come back as something the model can relay to a person, not an exception blob" posture {@code
 * GenerateDocumentToolCallback#consumerReason} established for document generation.
 *
 * <p>Unchecked (extends {@link RuntimeException}) purely so {@link WebPageFetcher}'s internal
 * helpers can throw it through the redirect loop without checked-exception plumbing; it never
 * escapes the tool callback.
 */
public class WebPageFetchException extends RuntimeException {

    public WebPageFetchException(String reason) {
        super(reason);
    }

    public WebPageFetchException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

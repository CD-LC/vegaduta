package com.agenticai.core.agents.tools.web;

/**
 * A search failure whose message is already worded for a person — mirrors {@link
 * WebPageFetchException}'s contract so {@code WebSearchToolCallback} can relay {@code
 * e.getMessage()} verbatim instead of re-wording each case.
 */
public class WebSearchException extends RuntimeException {

    public WebSearchException(String message) {
        super(message);
    }
}

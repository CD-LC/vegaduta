package com.agenticai.core.agents.tools.browseruse;

/**
 * A browser-use tool action that could not complete, carrying a SHORT, person/model-facing reason
 * ("the URL points inside the platform's own network", "the browser action timed out", "no element
 * matched that selector") rather than a raw Playwright stack-trace message. Every {@code
 * Browser*ToolCallback} catches this and turns it into a plain-text tool result - the same "a
 * failure must come back as something the model can relay to a person, not an exception blob"
 * posture {@link com.agenticai.core.agents.tools.web.WebPageFetchException} established for {@code
 * fetch_web_page} and {@code GenerateDocumentToolCallback#consumerReason} established for document
 * generation.
 *
 * <p>Unchecked (extends {@link RuntimeException}) purely so {@link BrowserUseSessionManager}'s
 * executor-thread callables can throw it back through the {@code Future} without checked-exception
 * plumbing; it never escapes a tool callback.
 */
public class BrowserUseException extends RuntimeException {

    public BrowserUseException(String reason) {
        super(reason);
    }

    public BrowserUseException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

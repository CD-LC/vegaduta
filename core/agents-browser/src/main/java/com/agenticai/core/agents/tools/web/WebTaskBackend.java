package com.agenticai.core.agents.tools.web;

/**
 * Backend SPI behind the {@code web_task} built-in - the same "one tool, pick a backend" posture
 * {@code SpeakBackend} established for voice: the model sees a single {@code web_task} tool and the
 * platform chooses which managed-browser provider actually runs it. Kept as an interface rather
 * than inlined into the callback so a future CDP-direct or local-Playwright backend can slot in
 * without touching the tool definition or its tests.
 *
 * <p><b>Provider contract</b> (what {@link HttpWebTaskBackend} POSTs): {@code {baseUrl}/v1/tasks}
 * accepts {@code {"task", "startUrl", "maxSteps", "timeoutSeconds"}} and returns {@code {"status":
 * "success|failed|timeout", "result": "...", "steps": [...]}}. Steel, Browserbase and self-hosted
 * Steel-compatible gateways all fit this shape behind a thin adapter; the platform deliberately
 * does NOT hardcode one vendor's proprietary API shape.
 */
public interface WebTaskBackend {

    /**
     * Executes one managed-browser task. Never throws for a *task-level* failure - reports it in
     * the result; may throw for transport errors the caller converts to a relayable sentence.
     */
    WebTaskResult run(WebTaskRequest request) throws WebTaskException;

    record WebTaskRequest(String task, String startUrl, Integer maxSteps, int timeoutSeconds) {}

    record WebTaskResult(String status, String result, String detail) {}

    class WebTaskException extends Exception {
        public WebTaskException(String message) {
            super(message);
        }

        public WebTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

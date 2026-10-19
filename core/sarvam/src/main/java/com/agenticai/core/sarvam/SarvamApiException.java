package com.agenticai.core.sarvam;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Any failed call to a Sarvam AI REST endpoint (chat completion in Stage 2,
 * translate/speech-to-text/text-to-speech/transliteration/language-ID in Stage 3) - carries the
 * real {@code error.code}/{@code error.message} Sarvam returns (e.g. {@code invalid_api_key_error},
 * {@code rate_limit_exceeded_error}, {@code insufficient_quota_error}) rather than a generic
 * message, same "translate the upstream error, don't swallow it" discipline {@code
 * UpstreamProviderException}/{@code ProviderErrorTranslator} already apply to every other
 * LLM/channel provider in this codebase.
 *
 * <p><b>Gotcha worth calling out explicitly:</b> Sarvam's auth failures return HTTP 403, not 401 -
 * every other provider integrated so far (Anthropic, OpenAI, Grok, Gemini, OpenRouter, Twilio) uses
 * 401 for "bad key," so any Sarvam-specific error translation added in Stage 2/3 must not assume
 * 401 means auth failure and 403 means something else, the way a generic translator built from
 * those other providers' behavior would.
 */
public class SarvamApiException extends RuntimeException implements HttpStatusMapped {

    private final int upstreamStatus;

    public SarvamApiException(int upstreamStatus, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    /**
     * 502: an outbound call to the third-party API failed. Answered by the central exception
     * handler.
     */
    @Override
    public int httpStatus() {
        return 502;
    }

    @Override
    public boolean logAtWarn() {
        return true;
    }
}

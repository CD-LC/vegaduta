package com.agenticai.core.speech.call;

/**
 * The tenant's voice-generation quota is used up. The message is already written for a person
 * ("upgrade for a higher limit") and is shown as-is.
 */
public class CallSpeechQuotaExceededException extends RuntimeException {

    public CallSpeechQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.agenticai.core.common.improve;

/** The LLM verdict for one failure cluster could not be parsed into a suggestion. */
public class ImprovementGenerationException extends RuntimeException {
    public ImprovementGenerationException(String message) {
        super(message);
    }
}

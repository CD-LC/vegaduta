package com.agenticai.core.common.improve;

/** 404/409 surface for the improvement loop - missing agent, missing suggestion, or already-decided. */
public class ImprovementNotFoundException extends RuntimeException {
    public ImprovementNotFoundException(String message) {
        super(message);
    }
}

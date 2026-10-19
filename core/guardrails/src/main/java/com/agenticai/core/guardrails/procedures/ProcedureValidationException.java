package com.agenticai.core.guardrails.procedures;

/** A procedure payload failed authoring-time validation (guard clause without its required fields) - mapped to 400. */
public class ProcedureValidationException extends RuntimeException {

    public ProcedureValidationException(String message) {
        super(message);
    }
}

package com.agenticai.core.guardrails.procedures;

/** Thrown when a procedure (or an agent-procedure attachment) does not exist - mapped to 404 by ProcedureController. */
public class ProcedureNotFoundException extends RuntimeException {

    public ProcedureNotFoundException(String message) {
        super(message);
    }
}

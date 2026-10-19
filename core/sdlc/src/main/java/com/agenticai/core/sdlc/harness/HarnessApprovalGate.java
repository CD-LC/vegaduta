package com.agenticai.core.sdlc.harness;

/**
 * Human confirmation gate. The harness asks before it hands changes back to the
 * user. To be adapted in the agents module to the existing tool confirmation/park
 * mechanism; there is deliberately no auto-approve implementation in main code.
 */
@FunctionalInterface
public interface HarnessApprovalGate {

    boolean approve(String action, String summary);
}

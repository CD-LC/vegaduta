package com.agenticai.core.agents.a2a;

import java.time.Instant;
import java.util.UUID;

/**
 * The serialised half of a task: everything {@link A2aTask#restore} needs, with instants as
 * epoch millis and the agent id as a string so a plain ObjectMapper round-trips it (no JavaTime
 * module on this path). Package-private - only {@link A2aTaskStore} uses it.
 */
record Stored(
        String id,
        String contextId,
        String agentId,
        String tenantId,
        String subject,
        String userMessageId,
        String userText,
        String artifactId,
        long createdAtMs,
        A2aTaskState state,
        long stateTimestampMs,
        String reply,
        String failureMessage,
        String ownerInstance,
        long heartbeatMs) {

    static Stored of(A2aTask task, String ownerInstance, long nowMs) {
        A2aTask.Snapshot snap = task.snapshot();
        return new Stored(
                task.id(),
                task.contextId(),
                task.agentId().toString(),
                task.tenantId(),
                task.subject(),
                task.userMessageId(),
                task.userText(),
                task.artifactId(),
                task.createdAt().toEpochMilli(),
                snap.state(),
                snap.timestamp().toEpochMilli(),
                snap.reply(),
                snap.failureMessage(),
                ownerInstance,
                nowMs);
    }

    /** This record's task, failed because its owning instance is gone. */
    Stored interrupted(String byInstance, long nowMs) {
        return new Stored(
                id,
                contextId,
                agentId,
                tenantId,
                subject,
                userMessageId,
                userText,
                artifactId,
                createdAtMs,
                A2aTaskState.FAILED,
                nowMs,
                reply,
                A2aTaskStore.INTERRUPTED_MESSAGE,
                byInstance,
                nowMs);
    }

    boolean terminal() {
        return state != null && state.isTerminal();
    }

    A2aTask toTask() {
        return A2aTask.restore(
                id,
                contextId,
                UUID.fromString(agentId),
                tenantId,
                subject,
                userMessageId,
                userText,
                artifactId,
                Instant.ofEpochMilli(createdAtMs),
                state,
                Instant.ofEpochMilli(stateTimestampMs),
                reply,
                failureMessage);
    }
    }

package com.agenticai.core.agents.tools.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

/**
 * The callback must run the script's CURRENT expression and refuse to run a disabled or deleted
 * script, even though its snapshot (and the tool offered to the model) came from the lookup cache.
 */
class UserToolScriptCallbackTest {

    private static final UUID ID = UUID.randomUUID();

    private UserToolScriptService service;
    private UserToolScriptEvaluator evaluator;
    private UserToolScriptCallback callback;

    @BeforeEach
    void setUp() {
        service = mock(UserToolScriptService.class);
        evaluator = mock(UserToolScriptEvaluator.class);
        callback =
                new UserToolScriptCallback(
                        new UserToolScriptSnapshot(ID, "double_it", "Doubles x", "#x * 2", null),
                        evaluator,
                        service);
    }

    @Test
    void runsTheCurrentExpressionNotTheSnapshotOne() {
        when(service.findRunnableExpression(ID)).thenReturn(Optional.of("#x * 3"));
        when(evaluator.evaluate("#x * 3", "{\"x\":2}")).thenReturn("6");

        assertThat(callback.call("{\"x\":2}")).isEqualTo("6");
        verify(evaluator, never()).evaluate("#x * 2", "{\"x\":2}");
    }

    @Test
    void aDisabledOrDeletedScriptDoesNotRun() {
        when(service.findRunnableExpression(ID)).thenReturn(Optional.empty());

        assertThat(callback.call("{\"x\":2}"))
                .contains("\"error\"")
                .contains("no longer available");
        verify(evaluator, never()).evaluate(anyString(), anyString());
    }

    @Test
    void failsClosedWhenTheRowCannotBeRead() {
        when(service.findRunnableExpression(ID)).thenThrow(new IllegalStateException("db down"));

        assertThat(callback.call("{}")).contains("\"error\"");
        verify(evaluator, never()).evaluate(anyString(), anyString());
    }
}

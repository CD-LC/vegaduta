package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link WorkflowDefinition}: updates, the schedule lifecycle and the tuning/limit setters. */
class WorkflowDefinitionTest {

    private static WorkflowDefinition def() {
        return new WorkflowDefinition("Flow", "desc", "{}", WorkflowStatus.DRAFT);
    }

    @Test
    void constructionStampsBothTimestamps() {
        WorkflowDefinition d = def();
        assertEquals("Flow", d.getName());
        assertEquals("desc", d.getDescription());
        assertEquals("{}", d.getGraphJson());
        assertEquals(WorkflowStatus.DRAFT, d.getStatus());
        assertNotNull(d.getCreatedAt());
        assertNotNull(d.getUpdatedAt());
        assertNull(d.getId(), "the id is generated on persist");
        assertNull(d.getVersion(), "no optimistic-lock value before persist");
        assertFalse(d.isScheduleEnabled());
        assertNull(d.getScheduleCron());
        assertNull(d.getNextRunAt());
    }

    @Test
    void updateReplacesTheEditableFields() {
        WorkflowDefinition d = def();
        d.update("New", "new desc", "{\"nodes\":[]}", WorkflowStatus.ACTIVE);
        assertEquals("New", d.getName());
        assertEquals("new desc", d.getDescription());
        assertEquals("{\"nodes\":[]}", d.getGraphJson());
        assertEquals(WorkflowStatus.ACTIVE, d.getStatus());
    }

    @Test
    void scheduleEnableDisableAndAdvance() {
        WorkflowDefinition d = def();
        Instant next = Instant.parse("2030-01-01T00:00:00Z");
        d.enableSchedule("0 * * * *", next);
        assertTrue(d.isScheduleEnabled());
        assertEquals("0 * * * *", d.getScheduleCron());
        assertEquals(next, d.getNextRunAt());

        Instant later = Instant.parse("2030-01-01T01:00:00Z");
        d.advanceSchedule(later);
        assertEquals(later, d.getNextRunAt());
        assertTrue(d.isScheduleEnabled(), "advancing keeps the schedule live");

        d.disableSchedule();
        assertFalse(d.isScheduleEnabled());
        assertNull(d.getNextRunAt());
        assertEquals("0 * * * *", d.getScheduleCron(), "the cron text is kept for re-enable");
    }

    @Test
    void tuningSettersFallBackToDefaultsOnNull() {
        WorkflowDefinition d = def();
        assertEquals(ReasoningDepth.FAST, d.getReasoningDepth());
        assertEquals(TuningMode.MANUAL, d.getTuningMode());
        d.setReasoningDepth(ReasoningDepth.RIGOROUS);
        assertEquals(ReasoningDepth.RIGOROUS, d.getReasoningDepth());
        d.setReasoningDepth(null);
        assertEquals(ReasoningDepth.FAST, d.getReasoningDepth());
        d.setTuningMode(TuningMode.SIMPLE);
        assertEquals(TuningMode.SIMPLE, d.getTuningMode());
        d.setTuningMode(null);
        assertEquals(TuningMode.MANUAL, d.getTuningMode());
    }

    @Test
    void costRoutingKeepsTheModelOnlyWithAProvider() {
        WorkflowDefinition d = def();
        assertFalse(d.isCostRoutingEnabled());
        d.setCostRouting(true, ProviderName.OPENAI, "gpt-4o-mini");
        assertTrue(d.isCostRoutingEnabled());
        assertEquals(ProviderName.OPENAI, d.getEconomyProviderName());
        assertEquals("gpt-4o-mini", d.getEconomyModel());
        d.setCostRouting(true, null, "ignored");
        assertNull(d.getEconomyProviderName());
        assertNull(d.getEconomyModel(), "a null provider cannot carry a model");
        d.setStepCachingEnabled(true);
        assertTrue(d.isStepCachingEnabled());
    }

    @Test
    void executionLimitsAndRetriesAreClamped() {
        WorkflowDefinition d = def();
        d.setExecutionLimits(0, -5);
        assertEquals(1, d.getMaxStepsPerRun());
        assertEquals(1, d.getStepTimeoutSeconds());
        d.setExecutionLimits(200, 90);
        assertEquals(200, d.getMaxStepsPerRun());
        assertEquals(90, d.getStepTimeoutSeconds());

        d.setMaxRetryAttempts(99);
        assertEquals(5, d.getMaxRetryAttempts());
        d.setMaxRetryAttempts(-3);
        assertEquals(0, d.getMaxRetryAttempts());
        d.setMaxRetryAttempts(2);
        assertEquals(2, d.getMaxRetryAttempts());
    }

    @Test
    void createdByIsWrittenOnce() {
        WorkflowDefinition d = def();
        assertNull(d.getCreatedBy());
        d.setCreatedBy("user-1");
        assertEquals("user-1", d.getCreatedBy());
    }
}

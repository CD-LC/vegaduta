package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowCreateRequest;
import com.agenticai.core.workflow.dto.WorkflowExecutionLimitsUpdateRequest;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowRunRequest;
import com.agenticai.core.workflow.dto.WorkflowUpdateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bean-validation bounds on the workflow request DTOs: a bad value is a 400, never a 500 from the column. */
class WorkflowRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final WorkflowGraphDto graph = new WorkflowGraphDto(List.of(), List.of());

    @Test
    void aNameLongerThanTheColumnIsRejectedOnCreateAndUpdate() {
        assertEquals(1, validator.validate(new WorkflowCreateRequest("x".repeat(201), null, graph)).size());
        assertEquals(
                1,
                validator.validate(new WorkflowUpdateRequest("x".repeat(201), null, graph, WorkflowStatus.DRAFT)).size());
        assertTrue(validator.validate(new WorkflowCreateRequest("x".repeat(200), null, graph)).isEmpty());
    }

    @Test
    void aBlankNameIsStillRejected() {
        assertEquals(1, validator.validate(new WorkflowCreateRequest("  ", null, graph)).size());
    }

    @Test
    void executionLimitsAcceptPartialBodiesButStillRangeCheckWhatIsSent() {
        assertTrue(validator.validate(new WorkflowExecutionLimitsUpdateRequest(10, null, null)).isEmpty());
        assertTrue(validator.validate(new WorkflowExecutionLimitsUpdateRequest(null, null, 0)).isEmpty());
        var violations = validator.validate(new WorkflowExecutionLimitsUpdateRequest(-5, null, null));
        assertEquals(1, violations.size());
        assertEquals("maxStepsPerRun", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void theRunCommentIsBounded() {
        assertTrue(validator.validate(new WorkflowRunRequest("in", null, null, null, null, null, null, "ok")).isEmpty());
        assertEquals(
                1,
                validator.validate(new WorkflowRunRequest("in", null, null, null, null, null, null, "c".repeat(2001)))
                        .size());
    }
}

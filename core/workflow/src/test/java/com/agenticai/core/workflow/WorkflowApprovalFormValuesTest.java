package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowApprovalFormValuesTest {

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();

    @Test
    void approverAnswersAreAppendedToTheResumedNodeInput() throws Exception {
        String out =
                WorkflowApprovalActionExecutor.withFormValues(
                        "Invoice 4417 from Acme",
                        mapper.readTree("{\"Cost centre\":\"OPS-12\",\"Pay by\":\"Friday\"}"));

        assertTrue(out.startsWith("Invoice 4417 from Acme"));
        assertTrue(out.contains("- Cost centre: OPS-12"));
        assertTrue(out.contains("- Pay by: Friday"));
    }

    @Test
    void noAnswersLeavesTheInputUntouched() throws Exception {
        assertEquals(
                "x", WorkflowApprovalActionExecutor.withFormValues("x", mapper.readTree("{}")));
        assertEquals("x", WorkflowApprovalActionExecutor.withFormValues("x", null));
    }
}

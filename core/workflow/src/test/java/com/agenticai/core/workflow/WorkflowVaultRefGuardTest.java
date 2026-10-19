package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the vault-reference guard extracted from {@link WorkflowExpressionResolver}. */
class WorkflowVaultRefGuardTest {

    @Test
    void protectSwapsVaultRefsForTokensAndRestoreSwapsThemBack() {
        String template = "Bearer {{secret.API_KEY}} and {{ secret : other }}";
        WorkflowVaultRefGuard.Protected p = WorkflowVaultRefGuard.protect(template);

        assertFalse(p.text().contains("secret"));
        assertEquals(template, p.restore(p.text()));
    }

    @Test
    void protectLeavesTextWithoutVaultRefsUntouched() {
        WorkflowVaultRefGuard.Protected p = WorkflowVaultRefGuard.protect("hello {{name}}");

        assertEquals("hello {{name}}", p.text());
        assertEquals("x", p.restore("x"));
    }

    @Test
    void neutralizeBreaksVaultShapedDataInStringsMapsAndLists() {
        Object out =
                WorkflowVaultRefGuard.neutralizeVaultRefs(
                        Map.of("k", List.of("{{secret.TOKEN}}", "plain")));

        String neutral = (String) ((List<?>) ((Map<?, ?>) out).get("k")).get(0);
        assertNotEquals("{{secret.TOKEN}}", neutral);
        assertTrue(neutral.contains("​"));
        assertEquals("plain", ((List<?>) ((Map<?, ?>) out).get("k")).get(1));
    }
}

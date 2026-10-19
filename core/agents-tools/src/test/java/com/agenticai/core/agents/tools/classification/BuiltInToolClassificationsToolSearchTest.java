package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.search.CallToolToolCallback;
import com.agenticai.core.agents.tools.search.SearchToolsToolCallback;
import org.junit.jupiter.api.Test;

/**
 * search_tools / call_tool are declared (fail-closed table) under the names the callbacks actually
 * use.
 */
class BuiltInToolClassificationsToolSearchTest {

    @Test
    void bothToolsAreDeclaredUnderTheirCallbackNames() {
        for (String name : new String[] {SearchToolsToolCallback.NAME, CallToolToolCallback.NAME}) {
            ToolClassification declared = BuiltInToolClassifications.all().get(name);

            assertNotNull(declared, name + " must be classified in the same commit as the tool");
            assertEquals(name, declared.toolName());
            assertTrue(
                    declared.needsToolContext(),
                    name + " follows the needsToolContext=true house convention");
            assertFalse(
                    declared.determinism() == ToolDeterminism.PURE,
                    name + " must not be declared PURE");
        }
    }

    @Test
    void theDispatcherDoesNotAddAConfirmationOnTopOfTheTargetsOwn() {
        // The target of call_tool is classified and gated under its own name; declaring call_tool
        // at the
        // worst case would only make every deferred call confirm twice.
        ToolClassification search =
                BuiltInToolClassifications.all().get(SearchToolsToolCallback.NAME);
        ToolClassification call = BuiltInToolClassifications.all().get(CallToolToolCallback.NAME);

        assertFalse(search.irreversible());
        assertFalse(call.irreversible());
        assertFalse(search.requiresHumanConfirmation());
        assertFalse(call.requiresHumanConfirmation());
        // search_tools introduces no taint: its output is the tool definitions the agent already
        // holds.
        assertFalse(search.trust() == ToolTrustTier.UNTRUSTED_EXTERNAL);
        assertFalse(search.sensitivity().isTaintSink());
    }

    @Test
    void theToolNamesAreTheDocumentedOnes() {
        assertEquals("search_tools", SearchToolsToolCallback.NAME);
        assertEquals("call_tool", CallToolToolCallback.NAME);
    }
}

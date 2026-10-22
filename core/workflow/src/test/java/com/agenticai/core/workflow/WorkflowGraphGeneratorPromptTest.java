package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.expression.BuiltIn;
import com.agenticai.core.workflow.expression.BuiltIns;

import org.junit.jupiter.api.Test;

/**
 * The generator prompt must teach the expression language without dropping the legacy templating.
 */
class WorkflowGraphGeneratorPromptTest {

    private static final String PROMPT =
            WorkflowGeneratorPrompts.buildSystemPrompt("- id=\"a1\" name=\"Agent\"\n", true);
    private static final String PROMPT_OFF =
            WorkflowGeneratorPrompts.buildSystemPrompt("- id=\"a1\" name=\"Agent\"\n", false);

    /**
     * Same text with runs of whitespace collapsed, so assertions ignore the prompt's line wrapping.
     */
    private static final String FLAT = PROMPT.replaceAll("\\s+", " ");

    private static void has(String haystack, String needle) {
        assertTrue(haystack.contains(needle), "prompt is missing: " + needle);
    }

    @Test
    void promptDocumentsExpressionRoots() {
        has(FLAT, "$node[\"Label\"].path");
        has(FLAT, "$json.path");
        has(FLAT, "$trigger.path");
        has(FLAT, "$item, $index");
        has(FLAT, "a ? b : c");
        has(FLAT, "??");
    }

    @Test
    void promptListsEveryRegisteredBuiltInByName() {
        for (BuiltIn fn : BuiltIns.registry().values()) {
            has(PROMPT, "  " + fn.category() + ": ");
            has(FLAT, fn.name());
        }
    }

    @Test
    void legacyTemplatingStillDocumented() {
        has(FLAT, "\"{{input}}\" is this node's raw input");
        has(FLAT, "\"{{fieldName}}\" is a top-level field");
        has(FLAT, "{{input_json}}");
    }

    @Test
    void conditionExpressionModeDocumentedAndAgentListStillInterpolated() {
        has(FLAT, "\"mode\": \"expression\"");
        has(FLAT, "id=\"a1\" name=\"Agent\"");
    }

    @Test
    void flagOffPromptTeachesNoExpressionSyntaxButKeepsLegacyTemplating() {
        String flat = PROMPT_OFF.replaceAll("\\s+", " ");
        for (String forbidden :
                new String[] {
                    "$node[",
                    "$json.path",
                    "$trigger",
                    "\"mode\": \"expression\"",
                    "Built-in functions"
                }) {
            assertTrue(!flat.contains(forbidden), "flag-off prompt must not mention: " + forbidden);
        }
        has(flat, "\"{{input}}\" is this node's raw input");
        has(flat, "{{input_json}}");
        has(flat, "id=\"a1\" name=\"Agent\"");
    }

    @Test
    void serviceOnlyTeachesExpressionsWhenResolverIsWiredAndEnabled() throws Exception {
        var m = WorkflowGraphGeneratorService.class.getDeclaredMethod("expressionsWired");
        m.setAccessible(true);
        var objectMapper = com.agenticai.core.json.JsonMappers.plain();
        var unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        var u = (sun.misc.Unsafe) unsafe.get(null);
        var svc =
                (WorkflowGraphGeneratorService)
                        u.allocateInstance(WorkflowGraphGeneratorService.class);
        assertTrue(!(boolean) m.invoke(svc), "no resolver wired -> legacy prompt");
        svc.setExpressions(WorkflowExpressionResolver.standalone(objectMapper));
        assertTrue((boolean) m.invoke(svc), "enabled resolver wired -> expression prompt");
        svc.setExpressions(
                new WorkflowExpressionResolver(
                        (RunDataStore) null,
                        (WorkflowRunRepository) null,
                        objectMapper,
                        false,
                        false));
        assertTrue(!(boolean) m.invoke(svc), "kill switch off -> legacy prompt");
    }

    // --- nlflow W2: full node catalog, schedule contract, approval rule, edit mode ---

    @Test
    void promptTeachesTheNodeTypesItUsedToOmit() {
        for (String flat : new String[] {FLAT, PROMPT_OFF.replaceAll("\\s+", " ")}) {
            has(flat, "- \"human_input\": config {\"message\"");
            has(flat, "- \"bulk_email\": config {\"recipientsSource\": \"items\"|\"static\"");
            has(flat, "\"subject\" and \"body\" are REQUIRED");
            has(flat, "- \"crm_lead\": config {\"action\": \"upsert\"|\"stage\"|\"activity\"");
            has(flat, "- \"sub_flow\": config {\"itemsField\": \"\", \"body\": {\"nodes\"");
        }
    }

    @Test
    void perItemDestinationIsOnlyTaughtWhenExpressionsAreOn() {
        has(FLAT, "\"whatsappTo\": \"{{ $json.phone }}\"");
        assertTrue(
                !PROMPT_OFF.contains("$json.phone"),
                "destination fields have no legacy templating");
    }

    @Test
    void directAddendumCarriesTheScheduleContractAndTheApprovalRuleOnlyWhenEnforced() {
        String on = WorkflowGeneratorSupport.directAddendum(true).replaceAll("\\s+", " ");
        String off = WorkflowGeneratorSupport.directAddendum(false).replaceAll("\\s+", " ");
        has(on, "ALWAYS place an \"approval\" node directly before any node that sends");
        has(on, "BEFORE the loop / fan_out node");
        assertTrue(!off.contains("SAFETY RULE"), "flag off must not instruct an approval");
        for (String flat : new String[] {on, off}) {
            has(flat, "\"schedule\": {\"cron\": \"0 9 * * 1\", \"label\": \"Every Monday 09:00\"}");
            has(flat, "a schedule is NOT a node");
            has(flat, "exactly FIVE space-separated fields");
            has(flat, "\"secretsNeeded\"");
            has(flat, "\"knowledgeNeeded\"");
        }
        // The Brief planner's reference-mode prompt is built without this addendum.
        assertTrue(
                !PROMPT.contains("SCHEDULE:"),
                "base prompt must stay free of the direct-generation addendum");
    }

    @Test
    void editSuffixHandsOverTheCurrentGraphAsAChangeRequest() {
        String suffix =
                WorkflowGeneratorSupport.editSuffix("{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[]}");
        has(suffix, "CHANGE REQUEST");
        has(suffix, "keep every node and edge the request does not concern exactly as it is");
        has(suffix, "return the COMPLETE updated graph");
        assertTrue(suffix.endsWith("{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[]}"));
    }
}

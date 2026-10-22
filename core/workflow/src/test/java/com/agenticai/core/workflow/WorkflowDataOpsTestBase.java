package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture of the {@link WorkflowDataOps} tests: JSON builders and a small filter data set.
 */
abstract class WorkflowDataOpsTestBase {

    static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();
    final WorkflowDataOps ops = new WorkflowDataOps(M);

    // ---- helpers

    static JsonNode j(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("bad test JSON: " + s, e);
        }
    }

    static void assertJson(String expected, String actual) {
        assertEquals(j(expected), j(actual), "actual: " + actual);
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static Map<String, Object> cond(String field, String op, String value) {
        return m("field", field, "operator", op, "value", value);
    }

    static Map<String, Object> assign(String name, Object value, String type) {
        return m("name", name, "value", value, "type", type);
    }

    /** The text of {@code field} of every element of a JSON array. */
    static List<String> col(String json, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : j(json)) {
            out.add(WorkflowDataOps.text(WorkflowDataOps.get(n, field)));
        }
        return out;
    }

    static final String F =
            "[{\"n\":\"a\",\"p\":5,\"t\":\"x@y.com\",\"d\":\"2024-01-05\"},"
                    + "{\"n\":\"b\",\"p\":15,\"t\":\"\",\"d\":\"2024-03-01\"},"
                    + "{\"n\":\"c\",\"p\":null,\"t\":\"z@y.org\",\"d\":\"2023-12-31\"},"
                    + "{\"n\":\"A\",\"p\":\"20\"}]";

    List<String> filterNames(String op, String field, String value, boolean ignoreCase) {
        Outcome o =
                ops.filter(
                        m("conditions", List.of(cond(field, op, value)), "ignoreCase", ignoreCase),
                        F);
        return col(o.output(), "n");
    }
}

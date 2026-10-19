package com.agenticai.core.agents.version;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The field-level bundle diff {@link AgentVersionService#diff} answers, as a pure function. */
final class AgentVersionDiff {

    private AgentVersionDiff() {}

    /**
     * Every {@link AgentConfigSnapshot} record component that differs between {@code from} and
     * {@code to}, as {@code {field, from, to}} rows with values trimmed for display, plus both
     * version summaries and an {@code unchanged} flag.
     */
    static Map<String, Object> of(
            AgentVersion from, AgentVersion to, AgentConfigSnapshot a, AgentConfigSnapshot b) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (RecordComponent component : AgentConfigSnapshot.class.getRecordComponents()) {
            String field = component.getName();
            Object va;
            Object vb;
            try {
                va = component.getAccessor().invoke(a);
                vb = component.getAccessor().invoke(b);
            } catch (ReflectiveOperationException e) {
                fields.add(Map.of("field", field, "from", "?", "to", "?"));
                continue;
            }
            if (java.util.Objects.equals(va, vb)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field);
            row.put("from", displayValue(va));
            row.put("to", displayValue(vb));
            fields.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", AgentVersionResponse.summary(from));
        result.put("to", AgentVersionResponse.summary(to));
        result.put("fields", fields);
        result.put("unchanged", fields.isEmpty());
        return result;
    }

    /**
     * Long values (a 4k system prompt) would swamp a confirm dialog - 240 chars is enough to SEE
     * what moved.
     */
    private static Object displayValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }
}

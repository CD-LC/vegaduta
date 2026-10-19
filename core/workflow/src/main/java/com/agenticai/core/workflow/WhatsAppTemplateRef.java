package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The approved WhatsApp template an Output node asks for: config {@code whatsappTemplate: {name,
 * language}} plus {@code whatsappTemplateParams: ["value for {{1}}", "value for {{2}}", ...]}
 * (already expression-resolved by the time this is read). Pure parsing, no I/O.
 */
record WhatsAppTemplateRef(String name, String language, List<String> params) {

    /** Meta allows far fewer body variables than this; the cap only bounds a hostile config. */
    static final int MAX_PARAMS = 32;

    /** The node's template, or null when it has none (absent, not an object, or name / language blank). */
    static WhatsAppTemplateRef of(WorkflowNodeDto node) {
        Map<String, Object> config = node.config();
        if (!(config.get("whatsappTemplate") instanceof Map<?, ?> template)) {
            return null;
        }
        String name = text(template.get("name"));
        String language = text(template.get("language"));
        if (name.isEmpty() || language.isEmpty()) {
            return null;
        }
        List<String> params = new ArrayList<>();
        if (config.get("whatsappTemplateParams") instanceof Collection<?> raw) {
            for (Object value : raw) {
                if (params.size() >= MAX_PARAMS) {
                    break;
                }
                params.add(value == null ? "" : value.toString());
            }
        }
        return new WhatsAppTemplateRef(name, language, List.copyOf(params));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }
}

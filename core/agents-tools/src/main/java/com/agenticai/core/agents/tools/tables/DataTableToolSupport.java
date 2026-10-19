package com.agenticai.core.agents.tools.tables;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;

/** Shared argument parsing and failure rendering for the two data-table tools. */
final class DataTableToolSupport {

    static final ObjectMapper MAPPER =
            JsonMappers.plain()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** The most characters one tool result may carry back into the model's context. */
    static final int MAX_RESULT_CHARS = 12_000;

    private DataTableToolSupport() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArgs(String toolInput) {
        try {
            Object parsed =
                    MAPPER.readValue(
                            toolInput == null || toolInput.isBlank() ? "{}" : toolInput,
                            Object.class);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (JsonProcessingException e) {
            // fall through
        }
        throw new IllegalArgumentException("The tool input must be a JSON object");
    }

    static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    static int intArg(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    static String json(Object value) {
        try {
            String out = MAPPER.writeValueAsString(value);
            if (out.length() > MAX_RESULT_CHARS) {
                return out.substring(0, MAX_RESULT_CHARS)
                        + "\n[truncated at "
                        + MAX_RESULT_CHARS
                        + " characters - narrow the filter or lower the limit]";
            }
            return out;
        } catch (JsonProcessingException e) {
            return "Could not render the result as JSON.";
        }
    }

    /**
     * A failure the model can relay to a person: never a thrown exception (a throw fails the whole
     * turn).
     */
    static String failure(String what, RuntimeException e) {
        String reason;
        if (e instanceof ResponseStatusException rse) {
            reason = rse.getReason() == null ? "the request was refused" : rse.getReason();
        } else if (e instanceof IllegalArgumentException
                || e instanceof TenantLimitExceededException) {
            reason = e.getMessage();
        } else {
            reason = "an unexpected error occurred - try again";
        }
        return "Could not " + what + ": " + reason;
    }

    /**
     * A row as one flat object: the row id as _id, then its columns (no timestamps - fewer tokens
     * for the model).
     */
    static Map<String, Object> flatten(RowView row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_id", row.id() == null ? null : row.id().toString());
        if (row.data() != null) {
            out.putAll(row.data());
        }
        return out;
    }

    static List<Object> list(Object v) {
        return v instanceof List<?> l ? new java.util.ArrayList<Object>(l) : List.of();
    }
}

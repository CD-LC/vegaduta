package com.agenticai.core.common.apicontract;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes an error from a servlet filter in the same shape {@code ErrorResponseFactory} gives the
 * {@code @RestControllerAdvice} chain (RFC 9457 Problem Details, or the legacy body when that is
 * switched off), so a filter-level rejection looks like every other error to a client.
 */
final class ProblemResponses {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private ProblemResponses() {}

    static void write(
            ErrorResponseFactory errors,
            HttpServletResponse response,
            HttpStatus status,
            String message,
            String typeSlug,
            Map<String, Object> extensions)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(
                errors.isProblemDetailsEnabled()
                        ? MediaType.APPLICATION_PROBLEM_JSON_VALUE
                        : MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write(
                        MAPPER.writeValueAsString(
                                errors.body(status, message, typeSlug, extensions)));
    }
}

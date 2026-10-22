package com.agenticai.core.agents.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders every failure of {@link OpenAiCompatController} and {@link OpenResponsesController} in
 * OpenAI's error shape instead of the platform's own. Scoped to those two controllers only, and
 * ordered ahead of {@code GlobalExceptionHandler}, so no other endpoint changes.
 */
@RestControllerAdvice(
        assignableTypes = {OpenAiCompatController.class, OpenResponsesController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "app.openai-compat", name = "enabled", havingValue = "true")
public class OpenAiCompatAdvice {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatAdvice.class);

    private final ObjectMapper mapper;

    public OpenAiCompatAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception e) {
        OpenAiCompatErrors.Info info = OpenAiCompatErrors.classify(e);
        if (info.status() >= 500) {
            log.error("OpenAI-compatible endpoint failed", e);
        }
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(HttpStatusCode.valueOf(info.status()))
                        .contentType(MediaType.APPLICATION_JSON);
        if (info.retryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(info.retryAfterSeconds()));
        }
        return builder.body(OpenAiCompatErrors.toJson(mapper, info));
    }
}

package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The settings API's error bodies: 400 {@code {code: "SETTING_INVALID", key, message, min?, max?, enumValues?}},
 * 409 {@code {code: "SETTING_LOCKED", key, message}} (the operator pinned the key) or {@code "SETTING_CONFLICT"} (two
 * writers raced on one key and this one lost; nothing was stored), 404 {@code {code: "SETTING_UNKNOWN", key, message}}. Scoped to
 * the two settings controllers only.
 *
 * <p>Highest precedence, like {@code OpenAiCompatAdvice}: the app's generic advice ({@code
 * FallbackExceptionHandler}) is {@code @Order(LOWEST_PRECEDENCE)} and handles {@code Exception}, so an advice with
 * no {@code @Order} (which defaults to the same lowest precedence) can lose to it. That is how a security setting
 * made weaker than the value it inherits came back as a 500 ("Unhandled exception") instead of this 400. The
 * {@code assignableTypes} scope keeps the advice to its own controllers.
 */
@RestControllerAdvice(assignableTypes = {SettingsController.class, AgentSettingsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SettingsExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String key, String message, Number min, Number max, List<String> enumValues) {}

    @ExceptionHandler(SettingInvalidException.class)
    ResponseEntity<ErrorBody> invalid(SettingInvalidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("SETTING_INVALID", e.key(), e.getMessage(), e.min(), e.max(), e.enumValues()));
    }

    @ExceptionHandler(SettingLockedException.class)
    ResponseEntity<ErrorBody> locked(SettingLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("SETTING_LOCKED", e.key(), e.getMessage(), null, null, null));
    }

    @ExceptionHandler(SettingConflictException.class)
    ResponseEntity<ErrorBody> conflict(SettingConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("SETTING_CONFLICT", e.key(), e.getMessage(), null, null, null));
    }

    @ExceptionHandler(SettingNotFoundException.class)
    ResponseEntity<ErrorBody> unknown(SettingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("SETTING_UNKNOWN", e.key(), e.getMessage(), null, null, null));
    }
}

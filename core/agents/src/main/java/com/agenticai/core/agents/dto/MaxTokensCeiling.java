package com.agenticai.core.agents.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value may not exceed {@code app.agents.max-tokens-ceiling} (default 8192). What {@code @Max(8192)} was,
 * with the limit read from the platform setting instead of written into the annotation; null passes (pair
 * it with {@code @NotNull} where the field is required).
 */
@Documented
@Constraint(validatedBy = MaxTokensCeilingValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxTokensCeiling {

    String message() default "maxTokens must be at most the platform ceiling";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

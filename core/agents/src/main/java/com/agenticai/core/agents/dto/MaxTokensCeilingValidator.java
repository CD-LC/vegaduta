package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.generation.AgentGenerationProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Validates {@link MaxTokensCeiling}. Spring's constraint-validator factory autowires the bound {@link
 * AgentGenerationProperties}; a plain Bean Validation run (no Spring) uses the shipped default.
 */
public class MaxTokensCeilingValidator implements ConstraintValidator<MaxTokensCeiling, Integer> {

    private AgentGenerationProperties generation = new AgentGenerationProperties();

    @Autowired(required = false)
    public void setGeneration(AgentGenerationProperties generation) {
        this.generation = generation;
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        int ceiling = generation.getMaxTokensCeiling();
        if (value == null || value <= ceiling) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("maxTokens must be at most " + ceiling).addConstraintViolation();
        return false;
    }
}

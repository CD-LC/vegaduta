package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.agents.generation.AgentGenerationProperties;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.providers.ProviderName;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * The agent max-tokens ceiling ({@code app.agents.max-tokens-ceiling}) is one setting that every bound reads:
 * the create and update request validation, the Simple-mode tuning and the hosted-pool reservation. These
 * tests exercise the real validation path (Spring's validator factory with the bound properties bean).
 */
class AgentMaxTokensCeilingTest {

    private static LocalValidatorFactoryBean validatorWith(AgentGenerationProperties properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(AgentGenerationProperties.class, () -> properties);
        ctx.refresh();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setApplicationContext(ctx);
        validator.afterPropertiesSet();
        return validator;
    }

    private static AgentGenerationParamsUpdateRequest update(int maxTokens) {
        return new AgentGenerationParamsUpdateRequest(maxTokens, 0.7, 1.0);
    }

    private static Set<String> messages(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void theShippedCeilingIsTheOneTheLiteralWas() {
        assertThat(new AgentGenerationProperties().getMaxTokensCeiling()).isEqualTo(8192);
        assertThat(new AgentGenerationProperties().getInferredMaxTokensFloor()).isEqualTo(128);
    }

    @Test
    void theUpdateRequestRejectsAboveTheDefaultCeilingWithTheSameMessage() {
        LocalValidatorFactoryBean validator = validatorWith(new AgentGenerationProperties());
        assertThat(validator.validate(update(8192))).isEmpty();
        assertThat(messages(validator.validate(update(8193)))).contains("maxTokens must be at most 8192");
    }

    @Test
    void aLoweredCeilingRejectsWhatTheDefaultAllowedAndNamesTheLimit() {
        AgentGenerationProperties lowered = new AgentGenerationProperties();
        lowered.setMaxTokensCeiling(2048);
        LocalValidatorFactoryBean validator = validatorWith(lowered);
        assertThat(validator.validate(update(2048))).isEmpty();
        assertThat(messages(validator.validate(update(4096)))).contains("maxTokens must be at most 2048");
    }

    @Test
    void aRaisedCeilingAllowsWhatTheDefaultRejected() {
        AgentGenerationProperties raised = new AgentGenerationProperties();
        raised.setMaxTokensCeiling(16384);
        assertThat(validatorWith(raised).validate(update(16384))).isEmpty();
    }

    @Test
    void theCreateRequestFollowsTheSameCeiling() {
        AgentGenerationProperties lowered = new AgentGenerationProperties();
        lowered.setMaxTokensCeiling(1000);
        LocalValidatorFactoryBean validator = validatorWith(lowered);
        AgentCreateRequest request = AgentCreateRequestFixtures.withMaxTokens(2000);
        assertThat(messages(validator.validate(request))).contains("maxTokens must be at most 1000");
        assertThat(validator.validate(AgentCreateRequestFixtures.withMaxTokens(1000))).isEmpty();
        assertThat(validator.validate(AgentCreateRequestFixtures.withMaxTokens(null))).isEmpty();
    }

    @Test
    void theSimpleModeTuningClampsToTheCeilingAndTheFloor() {
        AgentGenerationProperties custom = new AgentGenerationProperties();
        custom.setMaxTokensCeiling(2000);
        custom.setInferredMaxTokensFloor(300);
        GenerationParamsInferenceService service = new GenerationParamsInferenceService(
            null, null, null, null, mock(FreeTierChatClientService.class), new ObjectMapper(), 1024);
        service.useGenerationProperties(custom);
        assertThat(service.parse("{\"temperature\":0.5,\"topP\":1,\"maxTokens\":9000}").maxTokens()).isEqualTo(2000);
        assertThat(service.parse("{\"temperature\":0.5,\"topP\":1,\"maxTokens\":100}").maxTokens()).isEqualTo(300);
    }

    /** Minimal valid create request; only the maxTokens varies. */
    private static final class AgentCreateRequestFixtures {
        static AgentCreateRequest withMaxTokens(Integer maxTokens) {
            return new AgentCreateRequest(
                "n", "p", ProviderName.ANTHROPIC, null, maxTokens,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        }
    }
}

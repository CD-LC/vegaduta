package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * JAVA-022: every public per-field mutator on {@link AgentService}
 * ({@code set*} returning the updated agent) saves the entity AND writes the
 * audit version snapshot; without {@code @Transactional} those two commit
 * separately. {@code setBiometricConsentEnabled} was the one that had lost it.
 */
class AgentServiceTransactionalMutatorsTest {

    @Test
    void everyPublicSetterIsTransactional() {
        List<String> missing = Arrays.stream(AgentService.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(m -> m.getName().startsWith("set"))
            .filter(m -> m.getReturnType() != void.class)
            .filter(m -> !AnnotatedElementUtils.hasAnnotation(m, Transactional.class))
            .map(Method::getName)
            .sorted()
            .toList();

        assertThat(missing).isEmpty();
    }
}

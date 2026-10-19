package com.agenticai.core.common.improve;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.platform.TranslationJobSettings;
import com.agenticai.core.platform.TranslationJobSettingsResponse;
import java.beans.Introspector;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * JAVA-016: controllers now return response records instead of JPA entities.
 * The wire contract must not change - each record exposes exactly the JSON
 * properties the entity's public getters produced before.
 */
class EntityResponseDtoContractTest {

    @Test
    void suggestionResponseMatchesTheEntityJson() {
        assertThat(components(ImprovementDtos.SuggestionResponse.class)).isEqualTo(getterProperties(AgentImprovementSuggestion.class));
    }

    @Test
    void healthScoreResponseMatchesTheEntityJson() {
        assertThat(components(ImprovementDtos.HealthScoreResponse.class)).isEqualTo(getterProperties(AgentHealthScore.class));
    }

    @Test
    void translationJobResponseMatchesTheEntityJson() {
        assertThat(components(TranslationJobSettingsResponse.class)).isEqualTo(getterProperties(TranslationJobSettings.class));
    }

    private static Set<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /** What Jackson serializes by default: public no-arg getX()/isX() methods. */
    private static Set<String> getterProperties(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                continue;
            }
            String n = m.getName();
            if (n.startsWith("get") && n.length() > 3) {
                names.add(Introspector.decapitalize(n.substring(3)));
            } else if (n.startsWith("is") && n.length() > 2 && (m.getReturnType() == boolean.class)) {
                names.add(Introspector.decapitalize(n.substring(2)));
            }
        }
        return names;
    }
}

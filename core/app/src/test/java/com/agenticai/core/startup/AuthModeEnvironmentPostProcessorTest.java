package com.agenticai.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.AuthModeEnvironmentPostProcessor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class AuthModeEnvironmentPostProcessorTest {

    private final AuthModeEnvironmentPostProcessor processor = new AuthModeEnvironmentPostProcessor();

    private String modeAfterProcessing(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        processor.postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("app.auth.mode");
    }

    @Test
    void anAbsentPropertyStaysAbsent() {
        assertThat(modeAfterProcessing(Map.of())).isNull();
    }

    @Test
    void aBlankPropertyMeansKeycloak() {
        assertThat(modeAfterProcessing(Map.of("app.auth.mode", ""))).isEqualTo("keycloak");
        assertThat(modeAfterProcessing(Map.of("app.auth.mode", "   "))).isEqualTo("keycloak");
    }

    @Test
    void aPaddedOrUpperCaseValueIsNormalised() {
        assertThat(modeAfterProcessing(Map.of("app.auth.mode", " Local "))).isEqualTo("local");
        assertThat(modeAfterProcessing(Map.of("app.auth.mode", "KEYCLOAK"))).isEqualTo("keycloak");
    }

    @Test
    void aWellFormedOrUnknownValueIsLeftAlone() {
        assertThat(modeAfterProcessing(Map.of("app.auth.mode", "local"))).isEqualTo("local");
        // A typo must still reach AuthModeValidator, which names the property and stops the boot.
        assertThat(modeAfterProcessing(Map.of("app.auth.mode", "ldap"))).isEqualTo("ldap");
    }
}

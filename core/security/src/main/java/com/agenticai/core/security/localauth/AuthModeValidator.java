package com.agenticai.core.security.localauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails the boot when {@code app.auth.mode} is set to something other than {@code keycloak} or
 * {@code local}. Without it a typo ("Local ", "kecloak") would match neither mode's wiring and the
 * context would die later with an unrelated "no JwtDecoder" error; this names the property instead.
 */
@Component
class AuthModeValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AuthModeValidator.class);

    private final Environment environment;

    AuthModeValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String mode = LocalAuth.mode(environment);
        if (LocalAuth.LOCAL.equals(mode)) {
            log.info("Authentication mode: local (core issues its own tokens; Keycloak is not used)");
        }
    }
}

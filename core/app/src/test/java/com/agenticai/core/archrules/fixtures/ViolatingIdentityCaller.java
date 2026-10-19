package com.agenticai.core.archrules.fixtures;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Deliberately breaks the identity-port rule. Only {@code IdentityAdminPortRulesTest} imports it,
 * to prove the rule can fail, including through a generic argument. Not a Spring component.
 */
public class ViolatingIdentityCaller {

    private final ObjectProvider<KeycloakAdminClient> lazy;

    public ViolatingIdentityCaller(ObjectProvider<KeycloakAdminClient> lazy) {
        this.lazy = lazy;
    }

    public Object use() {
        return lazy.getIfAvailable();
    }
}

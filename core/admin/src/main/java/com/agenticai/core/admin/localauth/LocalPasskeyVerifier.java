package com.agenticai.core.admin.localauth;

import com.agenticai.core.security.localauth.LocalCredentialVerifier;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Passkey sign-in as a {@link LocalCredentialVerifier}, so it shares the throttle, the exempt-address
 * rule and the session start with passwords. The identifier is the challenge id from {@code
 * passkey/login-options}; the credentials carry the assertion ({@code PublicKeyCredential.toJSON()})
 * as one JSON string under {@code assertion}. Recorded as {@code amr: hwk}.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalPasskeyVerifier implements LocalCredentialVerifier {

    public static final String TYPE = "passkey";

    private final LocalPasskeyService passkeys;
    private final LocalAccountStore accounts;

    public LocalPasskeyVerifier(LocalPasskeyService passkeys, LocalAccountStore accounts) {
        this.passkeys = passkeys;
        this.accounts = accounts;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String authenticationMethod() {
        return "hwk";
    }

    @Override
    public Optional<String> verify(String identifier, Map<String, String> submitted) {
        String assertion = submitted == null ? null : submitted.get("assertion");
        return passkeys
                .verifyAssertion(identifier, assertion)
                .flatMap(accounts::findById)
                .filter(a -> a.enabled() && !a.setupPending())
                .map(LocalAccount::id);
    }
}

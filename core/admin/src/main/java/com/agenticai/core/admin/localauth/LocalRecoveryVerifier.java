package com.agenticai.core.admin.localauth;

import com.agenticai.core.security.localauth.LocalCredentialVerifier;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Recovery-code sign-in: the way back in after every device is lost. The identifier is the literal
 * {@code recovery}; the credential is {@code code}. A code is spent by being presented, whether or
 * not the account can then sign in. Recorded as {@code amr: rec}.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalRecoveryVerifier implements LocalCredentialVerifier {

    public static final String TYPE = "recovery";

    private final LocalRecoveryCodes codes;
    private final LocalAccountStore accounts;

    public LocalRecoveryVerifier(LocalRecoveryCodes codes, LocalAccountStore accounts) {
        this.codes = codes;
        this.accounts = accounts;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String authenticationMethod() {
        return "rec";
    }

    @Override
    public Optional<String> verify(String identifier, Map<String, String> submitted) {
        return codes.consume(submitted == null ? null : submitted.get("code"))
                .flatMap(accounts::findById)
                .filter(a -> a.enabled() && !a.setupPending())
                .map(LocalAccount::id);
    }
}

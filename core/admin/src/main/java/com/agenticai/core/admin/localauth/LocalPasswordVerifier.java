package com.agenticai.core.admin.localauth;

import com.agenticai.core.security.localauth.LocalCredentialVerifier;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The shipped {@link LocalCredentialVerifier}: e-mail address plus password. Always does one hash
 * verification, against a dummy when the account is unknown, disabled or has no password yet, so
 * the response time does not reveal which case it was. A hash made with an older algorithm is
 * upgraded in place on the first successful sign-in.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalPasswordVerifier implements LocalCredentialVerifier {

    public static final String TYPE = "password";

    private final LocalAccountStore store;
    private final LocalPasswords passwords;

    public LocalPasswordVerifier(LocalAccountStore store, LocalPasswords passwords) {
        this.store = store;
        this.passwords = passwords;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String authenticationMethod() {
        return "pwd";
    }

    @Override
    public Optional<String> verify(String identifier, Map<String, String> credentials) {
        LocalAccount account = store.findByEmail(identifier).orElse(null);
        String password = credentials == null ? null : credentials.get("password");
        String hash = account == null || !account.enabled() || account.setupPending() ? null : account.passwordHash();
        boolean ok = passwords.matches(password, hash);
        if (!ok || account == null) {
            return Optional.empty();
        }
        if (passwords.needsUpgrade(hash)) {
            store.update(account.withPassword(passwords.hash(password), account.mustChangePassword(), false));
        }
        return Optional.of(account.id());
    }
}

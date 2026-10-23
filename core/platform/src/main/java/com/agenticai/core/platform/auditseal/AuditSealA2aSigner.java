package com.agenticai.core.platform.auditseal;

import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.audit.seal.AuditSealSigner;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Signs audit seals with the platform's existing Ed25519 identity ({@link A2aCardSigningService},
 * the key published at {@code /.well-known/jwks.json}). Lives here, not in core/audit, because
 * audit is a leaf module and the signing service belongs to core/agents.
 *
 * <p>Seals are immutable, so one signed with an ephemeral key could never be re-signed later. With
 * no pinned {@code app.a2a.signing.private-key-pem} the signing service mints an EPHEMERAL key per
 * boot (per replica), and an offline verifier could not pin a single published key for those seals.
 * This signer therefore reports itself UNAVAILABLE (no seal is created) until the key is pinned
 * (APP_A2A_SIGNING_PRIVATE_KEY + APP_A2A_SIGNING_PUBLIC_KEY).
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class AuditSealA2aSigner implements AuditSealSigner {

    private final A2aCardSigningService signing;
    private final A2aProperties properties;

    public AuditSealA2aSigner(A2aCardSigningService signing, A2aProperties properties) {
        this.signing = signing;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return signing.isEnabled() && keyPinned();
    }

    private boolean keyPinned() {
        return StringUtils.hasText(properties.getSigning().getPrivateKeyPem());
    }

    @Override
    public Optional<Signed> sign(Map<String, Object> payload) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "audit-seal+jws");
        String jws = signing.signCompactJws(header, payload);
        if (jws == null) {
            return Optional.empty();
        }
        Object publicKey = signing.publicJwk().get("x");
        if (publicKey == null) {
            return Optional.empty();
        }
        return Optional.of(new Signed(jws, signing.keyId(), publicKey.toString()));
    }
}

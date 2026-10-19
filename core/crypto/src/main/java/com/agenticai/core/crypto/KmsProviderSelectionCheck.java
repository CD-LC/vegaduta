package com.agenticai.core.crypto;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup when {@code app.crypto.kms.provider} names something that is not the active {@link
 * KeyManagementProvider}: a typo, or a cloud provider selected in a build without the {@code
 * crypto-kms} module. Without this the platform would silently keep wrapping new data keys with the
 * local key.
 */
@Component
public class KmsProviderSelectionCheck {

    static final Set<String> KNOWN = Set.of("local", "aws", "azure", "gcp");

    public KmsProviderSelectionCheck(
            @Value("${app.crypto.kms.provider:local}") String selected,
            KeyManagementProvider active) {
        verify(selected, active.providerId());
    }

    /**
     * {@code @ConditionalOnProperty(havingValue=...)} on the cloud providers compares
     * case-insensitively, and an empty value (an env var set to nothing) matches none of them, so
     * the local provider stays active. This check mirrors both: it lower-cases the value and reads
     * an empty one as {@code local}.
     */
    static void verify(String rawSelected, String activeProviderId) {
        String selected = rawSelected.isEmpty() ? "local" : rawSelected.toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(selected)) {
            throw new IllegalStateException(
                    "app.crypto.kms.provider='"
                            + selected
                            + "' is not one of "
                            + new TreeSet<>(KNOWN));
        }
        boolean matches =
                "local".equals(selected)
                        ? LocalKeyManagementProvider.PROVIDER_ID.equals(activeProviderId)
                        : activeProviderId.startsWith(selected + ":");
        if (!matches) {
            throw new IllegalStateException(
                    "app.crypto.kms.provider='"
                            + selected
                            + "' but the active key management provider is '"
                            + activeProviderId
                            + "'. Is the crypto-kms module on the classpath and its key id set?");
        }
    }
}

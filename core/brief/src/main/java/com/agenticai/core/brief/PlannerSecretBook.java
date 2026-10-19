package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief.SecretSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Secrets declared so far, deduplicated by name+scope; the only place a secret ref (s1, s2, ...) is minted. */
final class PlannerSecretBook {

    static final int MAX_SECRETS = 10;

    private final Map<String, SecretSpec> byName = new LinkedHashMap<>();
    private final Map<String, String> keyToRef = new LinkedHashMap<>();
    private final List<String> warnings;

    PlannerSecretBook(List<String> warnings) {
        this.warnings = warnings;
    }

    /** @return the ref, or null when the name is invalid or the ten-secret cap is reached. */
    String declare(String key, String rawName, String scope, String why, String obtainAt) {
        String name = PlannerText.sanitizeSecretName(rawName);
        if (name == null) {
            warnings.add("Skipped a secret with an unusable name ('" + rawName + "').");
            return null;
        }
        String sc = "USER".equalsIgnoreCase(scope) ? "USER" : "TENANT";
        String id = name + "/" + sc;
        SecretSpec existing = byName.get(id);
        if (existing == null) {
            if (byName.size() >= MAX_SECRETS) {
                warnings.add("More than " + MAX_SECRETS + " secrets were needed; '" + name + "' was not added.");
                return null;
            }
            existing = new SecretSpec("s" + (byName.size() + 1), name, sc, PlannerText.orElse(why, "Needed by this solution."),
                looksLikeValue(obtainAt) ? null : obtainAt);
            if (looksLikeValue(obtainAt)) {
                warnings.add("Dropped the 'where to get it' text for " + name + ": it looked like a credential value.");
            }
            byName.put(id, existing);
        }
        if (key != null) {
            keyToRef.put(key, existing.ref());
        }
        return existing.ref();
    }

    String refForKey(String key) {
        return key == null ? null : keyToRef.get(key);
    }

    List<SecretSpec> specs() {
        return new ArrayList<>(byName.values());
    }

    private static boolean looksLikeValue(String s) {
        return s != null && s.strip().matches("^(sk|pk|rk|ghp|gho|xox[abps]|AKIA|AIza)[-_A-Za-z0-9]{12,}$|^[A-Za-z0-9_\\-]{40,}$");
    }
}

package com.agenticai.core.posture;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Evaluates {@link PostureChecks} against the running environment once at construction, logs one WARN line
 * listing every permissive setting, and exposes the result read-only. Nothing here changes behaviour.
 */
@Component
public class PostureReport {

    private static final Logger log = LoggerFactory.getLogger(PostureReport.class);

    private final Posture posture;
    private final List<String> permissive;

    public PostureReport(Environment env) {
        List<String> found = new ArrayList<>();
        int core = 0;
        int corePermissive = 0;
        for (PostureChecks.Check c : PostureChecks.ALL) {
            String value = env.getProperty(c.key(), c.absent());
            if (c.core()) {
                core++;
            }
            if (!c.safe().test(value)) {
                if (c.core()) {
                    corePermissive++;
                }
                found.add(c.key() + "=" + show(value) + " (strict: " + show(c.strictValue()) + ")");
            }
        }
        this.permissive = List.copyOf(found);
        this.posture = found.isEmpty() ? Posture.STRICT
            : corePermissive == core ? Posture.PERMISSIVE : Posture.MIXED;
        if (found.isEmpty()) {
            log.info("Security posture STRICT: no permissive governance setting is active.");
        } else {
            log.warn("Security posture {}: {} permissive setting(s) active: {}. Activate the 'strict' Spring "
                + "profile to close them.", posture, found.size(), String.join("; ", found));
        }
    }

    private static String show(String v) {
        return v == null || v.isBlank() ? "<empty>" : v;
    }

    public Posture posture() {
        return posture;
    }

    /** Each permissive setting as {@code key=value (strict: value)}; empty when {@link #posture()} is STRICT. */
    public List<String> permissiveSettings() {
        return permissive;
    }
}

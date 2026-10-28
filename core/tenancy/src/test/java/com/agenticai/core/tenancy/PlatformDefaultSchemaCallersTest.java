package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Regression pin for the intentional users of the legacy {@code public} default schema.
 *
 * <p>{@code TenantSchemaResolver} refuses any {@code TenantContext} naming {@code public} unless it
 * was built through {@code TenantContext.ofPlatformDefaultSchema} / {@code ofWebhookSchema}. This
 * test walks every module's {@code src/main/java} and fails when that explicit API gains (or loses)
 * a caller, or when anyone hand-builds {@code TenantContext.of(DEFAULT_SCHEMA | "public", ...)}
 * (which the resolver would refuse at runtime). Adding a caller is a security decision: add it to
 * {@link #INTENTIONAL_CALLERS} with a reason, in review.
 */
class PlatformDefaultSchemaCallersTest {

    /** Source files (relative to {@code core/}) that deliberately run unmapped senders on {@code public}. */
    private static final Set<String> INTENTIONAL_CALLERS = Set.of(
        // Inbound Twilio WhatsApp: an AccountSid with no tenant link falls back to the default
        // schema (single-tenant installs activated before the link table existed).
        "omnichannel/src/main/java/com/agenticai/core/omnichannel/twilio/TwilioWebhookController.java",
        // Inbound Twilio SMS: same fallback.
        "omnichannel/src/main/java/com/agenticai/core/omnichannel/twilio/TwilioSmsWebhookController.java",
        // Twilio delivery-status callbacks: same fallback.
        "omnichannel/src/main/java/com/agenticai/core/omnichannel/twilio/TwilioStatusCallbackController.java",
        // Twilio voice webhooks (inbound call, call-trigger, status): same fallback.
        "omnichannel/src/main/java/com/agenticai/core/omnichannel/twilio/TwilioVoiceWebhookController.java");

    private static final Pattern EXPLICIT_API =
        Pattern.compile("TenantContext\\s*\\.\\s*(ofPlatformDefaultSchema|ofWebhookSchema)\\s*\\(");

    private static final Pattern HAND_BUILT_PUBLIC = Pattern.compile(
        "TenantContext\\s*\\.\\s*of\\s*\\(\\s*(\"public\"|[A-Za-z_.]*DEFAULT_SCHEMA)\\b");

    @Test
    void onlyTheListedWebhookControllersUseTheExplicitPlatformDefaultApi() throws IOException {
        Path core = coreDir();
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = mainJavaFiles(core)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().equals("TenantContext.java")) {
                    continue;
                }
                if (EXPLICIT_API.matcher(Files.readString(file)).find()) {
                    found.add(core.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(new TreeSet<>(INTENTIONAL_CALLERS), found,
            "the set of code that may run on the legacy public schema changed - update INTENTIONAL_CALLERS "
                + "only after a security review");
    }

    @Test
    void nobodyHandBuildsAPlainContextForThePublicSchema() throws IOException {
        Path core = coreDir();
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = mainJavaFiles(core)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (HAND_BUILT_PUBLIC.matcher(Files.readString(file)).find()) {
                    offenders.add(core.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "TenantContext.of(public/DEFAULT_SCHEMA, ...) is refused by TenantSchemaResolver; use "
                + "TenantContext.ofWebhookSchema in a reviewed webhook path instead: " + offenders);
    }

    private static Stream<Path> mainJavaFiles(Path core) throws IOException {
        return Files.walk(core)
            .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
            .filter(p -> p.toString().endsWith(".java"));
    }

    /** Surefire runs with the module directory ({@code core/tenancy}) as the working directory. */
    private static Path coreDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !(Files.isDirectory(dir.resolve("tenancy")) && Files.isDirectory(dir.resolve("security")))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "could not locate the core/ reactor root from " + Paths.get("").toAbsolutePath());
        return dir;
    }
}

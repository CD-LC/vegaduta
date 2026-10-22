package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.audit.AuditOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins core's {@link AuditOutcome} to the audit vocabulary of the two Agent SDKs and to the
 * published outbound schema. The SDKs make no call to core, but their audit records reuse core's
 * field names and outcomes so one reader can consume both; a value added on one side only would
 * produce SDK records that fail {@code contracts/outbound/audit-event.schema.json} (or core records
 * an SDK-side tool cannot parse).
 *
 * <p>The SDK enums are read from source (they live in separate builds): {@code sdk/java/agent-api}
 * {@code io.vegaduta.agent.api.audit.AuditOutcome} and {@code sdk/python-agent} {@code
 * vegaduta_agent/audit.py}. Adding an outcome means adding it to all four places.
 */
class AuditOutcomeAlignmentTest {

    private static final Pattern JAVA_CONSTANT =
            Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*[,;]?\\s*$");
    private static final Pattern PY_MEMBER =
            Pattern.compile("^\\s{4}([A-Z][A-Z0-9_]*)\\s*=\\s*\"([A-Z0-9_]+)\"\\s*$");

    private static Path root;

    @BeforeAll
    static void findRoot() {
        root = ApiContractRepoSupport.findRepoRoot();
        assertThat(root).as("repo root (docs/ and core/pom.xml) above the working dir").isNotNull();
    }

    private static Set<String> core() {
        return Arrays.stream(AuditOutcome.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void publishedSchemaListsExactlyCoreOutcomes() throws IOException {
        JsonNode schema =
                ApiContractRepoSupport.MAPPER.readTree(
                        root.resolve("contracts/outbound/audit-event.schema.json").toFile());
        Set<String> published = new TreeSet<>();
        schema.path("properties")
                .path("outcome")
                .path("enum")
                .forEach(n -> published.add(n.asText()));
        assertThat(published).isEqualTo(core());
    }

    @Test
    void javaAgentSdkHasExactlyCoreOutcomes() throws IOException {
        String src =
                Files.readString(
                        root.resolve(
                                "sdk/java/agent-api/src/main/java/io/vegaduta/agent/api/audit/AuditOutcome.java"),
                        StandardCharsets.UTF_8);
        String body = src.substring(src.indexOf('{') + 1, src.lastIndexOf('}'));
        Set<String> sdk = new TreeSet<>();
        for (String line : body.split("\n")) {
            Matcher m = JAVA_CONSTANT.matcher(line);
            if (m.matches()) {
                sdk.add(m.group(1));
            }
        }
        assertThat(sdk).as("sdk/java AuditOutcome constants").isNotEmpty().isEqualTo(core());
    }

    @Test
    void pythonAgentSdkHasExactlyCoreOutcomes() throws IOException {
        String src =
                Files.readString(
                        root.resolve("sdk/python-agent/vegaduta_agent/audit.py"),
                        StandardCharsets.UTF_8);
        int start = src.indexOf("class AuditOutcome(");
        assertThat(start).as("class AuditOutcome in audit.py").isGreaterThanOrEqualTo(0);
        Set<String> sdk = new TreeSet<>();
        boolean inBody = false;
        for (String line : src.substring(start).split("\n")) {
            if (!inBody) {
                inBody = true; // the class line itself
                continue;
            }
            if (!line.isBlank() && !line.startsWith(" ")) {
                break; // next top-level statement ends the class body
            }
            Matcher m = PY_MEMBER.matcher(line);
            if (m.matches()) {
                assertThat(m.group(2)).as("value of %s", m.group(1)).isEqualTo(m.group(1));
                sdk.add(m.group(2));
            }
        }
        assertThat(sdk).as("sdk/python-agent AuditOutcome members").isNotEmpty().isEqualTo(core());
    }
}

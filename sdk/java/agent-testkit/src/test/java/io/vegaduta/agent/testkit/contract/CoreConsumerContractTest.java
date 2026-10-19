package io.vegaduta.agent.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.testkit.trace.TraceJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Consumer side of the core contract for the Java SDK ({@code contracts/consumers/sdk-java}).
 *
 * <p>The SDK makes no REST call to core. Its two HTTP clients post to URLs the host configures
 * (policy webhooks, Slack and Teams incoming webhooks). What it shares with core is the audit
 * record shape, declared as a shape-alignment interaction on {@code GET /api/audit-log}. This test
 * keeps three things true:
 *
 * <ol>
 *   <li>the SDK's {@link AuditEvent} carries every field the contract asks core to keep, and can
 *       represent every outcome core emits;
 *   <li>the consumer contract agrees with the published {@code
 *       contracts/outbound/audit-event.schema.json};
 *   <li>no SDK main source calls a core endpoint that no contract declares, and no new class gains
 *       an HTTP client without being reviewed here (a new core client must add an interaction to
 *       the contract and its class to {@link #HTTP_CLIENT_CLASSES}).
 * </ol>
 *
 * The provider side (core actually serving the declared fields) is verified in {@code core/app}.
 */
class CoreConsumerContractTest {

  private static final String CONSUMER = "sdk-java";

  /**
   * SDK main classes allowed to open HTTP connections. Both send to a URL supplied by the host
   * application, never to a core endpoint.
   */
  private static final Set<String> HTTP_CLIENT_CLASSES =
      Set.of(
          "agent-core/src/main/java/io/vegaduta/agent/core/hook/WebhookHook.java",
          "agent-core/src/main/java/io/vegaduta/agent/core/approval/JdkHttpTransport.java");

  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  private static final Pattern FIELD =
      Pattern.compile("^\\$(\\[\\*\\])?(\\.items\\[\\*\\])?\\.(\\w+)$");

  private static final Pattern API_LITERAL = Pattern.compile("\"(/api/[^\"]*)\"");

  private static final List<String> HTTP_CLIENT_IMPORTS =
      List.of(
          "import java.net.http.",
          "import java.net.HttpURLConnection",
          "import java.net.URLConnection",
          "import org.springframework.web.client.",
          "import org.springframework.web.reactive.function.client.",
          "import org.springframework.http.client.",
          "import okhttp3.",
          "import org.apache.hc.",
          "import org.apache.http.");

  private static Path repoRoot;
  private static List<Map<String, Object>> contracts;

  @BeforeAll
  static void load() throws IOException {
    repoRoot = findRepoRoot();
    Path dir = repoRoot.resolve("contracts/consumers").resolve(CONSUMER);
    try (Stream<Path> files = Files.list(dir)) {
      contracts =
          files
              .filter(p -> p.getFileName().toString().endsWith(".contract.json"))
              .sorted()
              .map(CoreConsumerContractTest::readObject)
              .collect(Collectors.toList());
    }
    assertThat(contracts).as("contract files under %s", dir).isNotEmpty();
  }

  @Test
  void contractFilesFollowTheSharedFormat() {
    Set<String> ids = new LinkedHashSet<>();
    for (Map<String, Object> contract : contracts) {
      assertThat(contract.get("consumer")).isEqualTo(CONSUMER);
      assertThat(contract.get("provider")).isEqualTo("core");
      for (Map<String, Object> interaction : interactions(contract)) {
        String id = (String) interaction.get("id");
        assertThat(id).as("interaction id").isNotBlank();
        assertThat(ids.add(id)).as("duplicate interaction id %s", id).isTrue();
        assertThat((String) interaction.get("method")).isIn(METHODS);
        assertThat((String) interaction.get("path")).startsWith("/api/");
        assertThat(((Number) interaction.get("status")).intValue()).isBetween(100, 599);
        for (String side : List.of("request", "response")) {
          for (String field : requiredFields(interaction, side)) {
            assertThat(field).as("%s %s field", id, side).matches(FIELD);
          }
        }
      }
    }
  }

  @Test
  void auditEventCarriesEveryFieldTheContractAsksCoreToKeep() {
    Set<String> components =
        Arrays.stream(AuditEvent.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());
    AuditEvent event =
        AuditEvent.builder("tool.call", "tool", "refund_order")
            .context(RunContext.anonymous())
            .detail("d")
            .build();
    Map<String, String> flat = event.toFlatMap();

    Set<String> fields = auditFields();
    assertThat(fields).isNotEmpty();
    assertThat(components).containsAll(fields);
    assertThat(flat.keySet()).containsAll(fields);
  }

  @Test
  void sdkCanRepresentEveryOutcomeCoreEmits() {
    Set<String> sdk = enumNames(AuditOutcome.values());
    assertThat(sdk).containsAll(coreOutcomes());
  }

  @Test
  void sdkOutcomesAreExactlyCoreOutcomes() {
    Set<String> sdkOnly = new TreeSet<>(enumNames(AuditOutcome.values()));
    sdkOnly.removeAll(coreOutcomes());
    assertThat(sdkOnly)
        .as(
            "SDK AuditOutcome values core does not know. Add a new one to core's AuditOutcome, "
                + "contracts/outbound/audit-event.schema.json and the sdk-java contract first.")
        .isEmpty();
  }

  @Test
  void contractAgreesWithThePublishedAuditSchema() {
    Map<String, Object> schema =
        readObject(repoRoot.resolve("contracts/outbound/audit-event.schema.json"));
    Set<String> required = new TreeSet<>(stringList(schema.get("required")));
    assertThat(new TreeSet<>(auditFields())).isEqualTo(required);

    Map<String, Object> outcome = map(map(schema.get("properties")).get("outcome"));
    assertThat(new TreeSet<>(coreOutcomes()))
        .isEqualTo(new TreeSet<>(stringList(outcome.get("enum"))));
  }

  @Test
  void sdkMakesNoUndeclaredCallToCore() throws IOException {
    List<Pattern> declared = new ArrayList<>();
    for (Map<String, Object> contract : contracts) {
      for (Map<String, Object> interaction : interactions(contract)) {
        declared.add(templateToRegex((String) interaction.get("path")));
      }
    }
    Path sdk = repoRoot.resolve("sdk/java");
    Set<String> httpClients = new TreeSet<>();
    List<String> undeclared = new ArrayList<>();
    for (Path source : mainSources(sdk)) {
      String rel = sdk.relativize(source).toString().replace('\\', '/');
      String text = Files.readString(source, StandardCharsets.UTF_8);
      if (HTTP_CLIENT_IMPORTS.stream().anyMatch(text::contains)) {
        httpClients.add(rel);
      }
      Matcher m = API_LITERAL.matcher(text);
      while (m.find()) {
        String path = m.group(1).replaceAll("\\?.*$", "");
        if (declared.stream().noneMatch(p -> p.matcher(path).matches())) {
          undeclared.add(rel + ": " + m.group(1));
        }
      }
    }
    assertThat(undeclared)
        .as("core paths used by the SDK but not declared in contracts/consumers/%s", CONSUMER)
        .isEmpty();
    assertThat(httpClients)
        .as(
            "SDK classes that open HTTP connections. A new one that talks to core needs an "
                + "interaction in the sdk-java contract; then add it to HTTP_CLIENT_CLASSES.")
        .isEqualTo(new TreeSet<>(HTTP_CLIENT_CLASSES));
  }

  @Test
  void templatedPathsMatchOneSegmentPerVariable() {
    Pattern p = templateToRegex("/api/approvals/{id}/approve");
    assertThat(p.matcher("/api/approvals/123/approve").matches()).isTrue();
    assertThat(p.matcher("/api/approvals/1/2/approve").matches()).isFalse();
    assertThat(p.matcher("/api/approvals/x.y/reject").matches()).isFalse();
    assertThat(templateToRegex("/api/audit-log").matcher("/api/audit-logs").matches()).isFalse();
  }

  // ---------------------------------------------------------------------------------------------

  private static Set<String> auditFields() {
    Set<String> fields = new LinkedHashSet<>();
    for (Map<String, Object> interaction : auditInteractions()) {
      for (String field : requiredFields(interaction, "response")) {
        Matcher m = FIELD.matcher(field);
        assertThat(m.matches()).as("field %s", field).isTrue();
        fields.add(m.group(3));
      }
    }
    return fields;
  }

  private static Set<String> coreOutcomes() {
    Set<String> outcomes = new LinkedHashSet<>();
    for (Map<String, Object> interaction : auditInteractions()) {
      Map<String, Object> enums = map(map(interaction.get("response")).get("x-enums"));
      outcomes.addAll(stringList(enums.get("$[*].outcome")));
    }
    assertThat(outcomes).as("core outcomes declared in the contract").isNotEmpty();
    return outcomes;
  }

  private static List<Map<String, Object>> auditInteractions() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> contract : contracts) {
      for (Map<String, Object> interaction : interactions(contract)) {
        if ("/api/audit-log".equals(interaction.get("path"))) {
          result.add(interaction);
        }
      }
    }
    assertThat(result).as("the audit-log interaction").isNotEmpty();
    return result;
  }

  private static List<Path> mainSources(Path sdk) throws IOException {
    try (Stream<Path> walk = Files.walk(sdk)) {
      return walk.filter(p -> p.toString().endsWith(".java"))
          .filter(
              p -> {
                String s = sdk.relativize(p).toString().replace('\\', '/');
                return s.contains("/src/main/java/") && !s.contains("/target/");
              })
          .sorted()
          .collect(Collectors.toList());
    }
  }

  static Pattern templateToRegex(String template) {
    StringBuilder regex = new StringBuilder();
    Matcher m = Pattern.compile("\\{[^}/]+}").matcher(template);
    int last = 0;
    while (m.find()) {
      regex.append(Pattern.quote(template.substring(last, m.start()))).append("[^/]+");
      last = m.end();
    }
    regex.append(Pattern.quote(template.substring(last)));
    return Pattern.compile(regex.toString());
  }

  private static Set<String> enumNames(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
  }

  private static List<Map<String, Object>> interactions(Map<String, Object> contract) {
    Object value = contract.get("interactions");
    assertThat(value).as("interactions").isInstanceOf(List.class);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object o : (List<?>) value) {
      result.add(map(o));
    }
    return result;
  }

  private static List<String> requiredFields(Map<String, Object> interaction, String side) {
    Object sideValue = interaction.get(side);
    if (sideValue == null) {
      return List.of();
    }
    return stringList(map(sideValue).get("requiredFields"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  private static List<String> stringList(Object value) {
    if (value == null) {
      return List.of();
    }
    assertThat(value).isInstanceOf(List.class);
    List<String> result = new ArrayList<>();
    for (Object o : (List<?>) value) {
      assertThat(o).isInstanceOf(String.class);
      result.add((String) o);
    }
    return result;
  }

  private static Map<String, Object> readObject(Path file) {
    try {
      return TraceJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.isDirectory(dir.resolve("contracts/consumers"))
          && Files.isDirectory(dir.resolve("sdk/java"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "repository root (with contracts/consumers and sdk/java) not found above "
            + Path.of("").toAbsolutePath());
  }
}

package io.vegaduta.agent.core.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolManifestTest {

  private static final ToolDescriptor SEARCH =
      new ToolDescriptor(
          "search",
          "Search  the web\nfor a query.",
          "{\"type\": \"object\", \"properties\": {\"q\": {\"type\": \"string\"}}}",
          Map.of("server", "web", "version", "1.2.0"));

  private static final ToolDescriptor READ_FILE =
      ToolDescriptor.of("read_file", "Read a file", "{\"type\":\"object\"}");

  @Test
  void canonicalFormIsStableAcrossOrderingAndWhitespace() {
    ToolManifest a = ToolManifest.of(List.of(SEARCH, READ_FILE));
    ToolDescriptor searchReformatted =
        new ToolDescriptor(
            "search",
            "  Search the web for a query. ",
            "{ \"type\" : \"object\" ,\n  \"properties\" : { \"q\" : { \"type\" : \"string\" } } }",
            Map.of("version", "1.2.0", "server", "web"));
    ToolManifest b = ToolManifest.of(List.of(READ_FILE, searchReformatted));

    assertThat(b.digest()).isEqualTo(a.digest());
    assertThat(b).isEqualTo(a);
    assertThat(b.entries())
        .extracting(ToolManifest.Entry::name)
        .containsExactly("read_file", "search");
    assertThat(a.digest()).startsWith("sha256:").hasSize(7 + 64);
  }

  @Test
  void whitespaceInsideSchemaStringsStillMatters() {
    ToolDescriptor a = ToolDescriptor.of("t", "d", "{\"description\":\"a b\"}");
    ToolDescriptor b = ToolDescriptor.of("t", "d", "{\"description\":\"a  b\"}");
    assertThat(ToolManifest.of(List.of(a)).digest())
        .isNotEqualTo(ToolManifest.of(List.of(b)).digest());
  }

  @Test
  void anyPartOfAToolChangesItsDigestAndTheManifestDigest() {
    ToolManifest base = ToolManifest.of(List.of(SEARCH));
    ToolManifest desc =
        ToolManifest.of(
            List.of(
                new ToolDescriptor(
                    "search", "Search the web", SEARCH.inputSchemaJson(), SEARCH.metadata())));
    ToolManifest schema =
        ToolManifest.of(
            List.of(
                new ToolDescriptor(
                    "search", SEARCH.description(), "{\"type\":\"object\"}", SEARCH.metadata())));
    ToolManifest meta = ToolManifest.of(List.of(SEARCH.withMetadata("version", "1.3.0")));

    assertThat(List.of(base.digest(), desc.digest(), schema.digest(), meta.digest()))
        .doesNotHaveDuplicates();
    ToolManifest.Entry e0 = base.entry("search").orElseThrow();
    ToolManifest.Entry e1 = desc.entry("search").orElseThrow();
    assertThat(e1.schemaDigest()).isEqualTo(e0.schemaDigest());
    assertThat(e1.metadataDigest()).isEqualTo(e0.metadataDigest());
    assertThat(e1.descriptionDigest()).isNotEqualTo(e0.descriptionDigest());
  }

  @Test
  void lockTextRoundTrips() {
    ToolManifest original = ToolManifest.of(List.of(SEARCH, READ_FILE));
    String text = original.toText();

    assertThat(text).startsWith(ToolManifest.HEADER + "\n");
    assertThat(text).contains("manifest\t" + original.digest());
    assertThat(text).contains("tool\tread_file\tdesc=sha256:");

    ToolManifest parsed = ToolManifest.parse(text);
    assertThat(parsed).isEqualTo(original);
    assertThat(parsed.digest()).isEqualTo(original.digest());
    assertThat(parsed.entries()).isEqualTo(original.entries());
    assertThat(parsed.toText()).isEqualTo(text);
    assertThat(parsed.description("search")).as("a lockfile carries digests only").isEmpty();
    assertThat(original.description("search")).contains("Search the web for a query.");
  }

  @Test
  void parseRejectsTamperedLockText() {
    String text = ToolManifest.of(List.of(SEARCH, READ_FILE)).toText();
    String dropped = text.replaceAll("(?m)^tool\tread_file.*\n", "");
    assertThatThrownBy(() -> ToolManifest.parse(dropped))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest mismatch");

    assertThatThrownBy(() -> ToolManifest.parse("not a lockfile"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("header");
  }

  @Test
  void duplicateToolNamesAreRejected() {
    assertThatThrownBy(() -> ToolManifest.of(List.of(READ_FILE, READ_FILE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void emptyManifestRoundTrips() {
    ToolManifest empty = ToolManifest.empty();
    assertThat(ToolManifest.parse(empty.toText())).isEqualTo(empty);
    assertThat(empty.size()).isZero();
  }

  @Test
  void schemaKeyOrderDoesNotChangeTheDigestButMetadataBoundariesDo() {
    ToolDescriptor a =
        ToolDescriptor.of(
            "t", "d", "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
    ToolDescriptor b =
        ToolDescriptor.of(
            "t", "d", "{\"properties\":{\"q\":{\"type\":\"string\"}},\"type\":\"object\"}");
    assertThat(ToolManifest.of(List.of(a)).digest())
        .isEqualTo(ToolManifest.of(List.of(b)).digest());
    // Values inside strings and array order still matter.
    ToolDescriptor c = ToolDescriptor.of("t", "d", "{\"enum\":[\"a\",\"b\"]}");
    ToolDescriptor d = ToolDescriptor.of("t", "d", "{\"enum\":[\"b\",\"a\"]}");
    assertThat(ToolManifest.of(List.of(c)).digest())
        .isNotEqualTo(ToolManifest.of(List.of(d)).digest());

    ToolDescriptor m1 = new ToolDescriptor("t", "d", "{}", Map.of("origin", "a\nserver=x"));
    ToolDescriptor m2 = new ToolDescriptor("t", "d", "{}", Map.of("origin", "a", "server", "x"));
    assertThat(ToolManifest.of(List.of(m1)).digest())
        .isNotEqualTo(ToolManifest.of(List.of(m2)).digest());
  }

  @Test
  void aLockfileWhoseComponentDigestsDoNotMatchItsToolDigestIsRejected() {
    ToolManifest manifest = ToolManifest.of(List.of(SEARCH, READ_FILE));
    String text = manifest.toText();
    ToolManifest.Entry read = manifest.entry("read_file").orElseThrow();
    // Swap the schema component digest for another valid-looking one; keep the tool digest.
    String tampered =
        text.replace(
            "schema=" + read.schemaDigest(),
            "schema="
                + ToolManifest.of(List.of(SEARCH)).entry("search").orElseThrow().schemaDigest());
    assertThat(tampered).isNotEqualTo(text);

    assertThatThrownBy(() -> ToolManifest.parse(tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool digest does not match");
  }
}

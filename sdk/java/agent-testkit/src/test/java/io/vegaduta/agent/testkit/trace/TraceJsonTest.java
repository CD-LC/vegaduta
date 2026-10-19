package io.vegaduta.agent.testkit.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TraceJsonTest {

  @Test
  void canonicalFormSortsKeysStripsWhitespaceAndNormalisesNumbers() {
    String a = "{ \"b\": [1, 2.0, {\"z\": null, \"y\": true}], \"a\": \"x\" }";
    String b = "{\"a\":\"x\",\"b\":[1.0,2,{\"y\":true,\"z\":null}]}";
    assertThat(TraceJson.canonicalize(a)).isEqualTo(TraceJson.canonicalize(b));
    assertThat(TraceJson.canonicalize(a))
        .isEqualTo("{\"a\":\"x\",\"b\":[1,2,{\"y\":true,\"z\":null}]}");
  }

  @Test
  void escapesAndUnescapesQuotesBackslashesControlCharactersAndUnicode() {
    String original = "quote \" backslash \\ tab \t newline \n émoji 😀 ctrl \u0001 日本語";
    String json = TraceJson.quote(original);
    assertThat(json).startsWith("\"").endsWith("\"").doesNotContain("\n").contains("\\u0001");
    assertThat(TraceJson.parse(json)).isEqualTo(original);
    assertThat(TraceJson.parse("\"\\u00e9\\/\"")).isEqualTo("é/");
  }

  @Test
  void invalidJsonIsRejectedByParseButToleratedByCanonicalize() {
    assertThatThrownBy(() -> TraceJson.parse("{\"a\":}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset");
    assertThatThrownBy(() -> TraceJson.parse("{} x")).hasMessageContaining("trailing");
    assertThat(TraceJson.canonicalize("  not json  ")).isEqualTo("not json");
    assertThat(TraceJson.canonicalize(null)).isEmpty();
  }
}

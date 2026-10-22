package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Hostile and ordinary YAML: only plain data may come out, and nothing may be constructed or
 * expanded.
 */
class SafeYamlLoaderTest {

    @Test
    void readsOrdinaryYamlIntoATree() {
        JsonNode tree =
                SafeYamlLoader.load(
                        """
            name: demo
            count: 3
            ratio: 1.5
            flag: true
            nothing: null
            when: 2024-01-02
            list:
              - a
              - b
            nested:
              k: v
            """,
                        100_000);
        assertThat(tree.get("name").asString()).isEqualTo("demo");
        assertThat(tree.get("count").asInt()).isEqualTo(3);
        assertThat(tree.get("ratio").asDouble()).isEqualTo(1.5);
        assertThat(tree.get("flag").asBoolean()).isTrue();
        assertThat(tree.get("nothing").isNull()).isTrue();
        assertThat(tree.get("when").isString()).isTrue();
        assertThat(tree.get("list")).hasSize(2);
        assertThat(tree.get("nested").get("k").asString()).isEqualTo("v");
    }

    @Test
    void refusesJavaTypeTagsInsteadOfInstantiatingThem() {
        String hostile =
                "x: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL "
                        + "[\"http://localhost:1/\"]]]]\n";
        assertThatThrownBy(() -> SafeYamlLoader.load(hostile, 100_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom type tag");
    }

    @Test
    void refusesPythonStyleTags() {
        assertThatThrownBy(
                        () ->
                                SafeYamlLoader.load(
                                        "x: !!python/object/apply:os.system ['id']\n", 100_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnAliasBomb() {
        String lol = "[\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\"]";
        String bomb =
                "a: &a "
                        + lol
                        + "\n"
                        + "b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]\n"
                        + "c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]\n"
                        + "d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]\n"
                        + "e: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d]\n";
        assertThatThrownBy(() -> SafeYamlLoader.load(bomb, 100_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anchors");
    }

    @Test
    void refusesASelfReferencingAnchor() {
        assertThatThrownBy(() -> SafeYamlLoader.load("a: &a [*a]\n", 100_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesDeepNesting() {
        String deep = "[".repeat(120) + "]".repeat(120);
        assertThatThrownBy(() -> SafeYamlLoader.load(deep, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesInputOverTheCodePointLimit() {
        String big = "k: " + "x".repeat(5000) + "\n";
        assertThatThrownBy(() -> SafeYamlLoader.load(big, 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesDuplicateKeys() {
        assertThatThrownBy(() -> SafeYamlLoader.load("a: 1\na: 2\n", 100_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorsNeverQuoteThePayload() {
        assertThatThrownBy(
                        () ->
                                SafeYamlLoader.load(
                                        "secret: [unterminated SENTINEL_VALUE_123\n", 100_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SENTINEL_VALUE_123");
    }
}

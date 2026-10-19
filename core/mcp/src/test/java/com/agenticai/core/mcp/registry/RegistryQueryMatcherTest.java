package com.agenticai.core.mcp.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.mcp.McpServerType;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The matching rules that replaced forwarding the query to the registry's own
 * name-only {@code ?search=}. These are the cases that were actually broken in
 * the UI - "youtube" returning nothing, multi-word queries matching nothing -
 * plus the ones that must NOT regress (a query full of punctuation must not
 * blow up as a regex).
 */
class RegistryQueryMatcherTest {

    private static RegistrySearchResult server(String name, String displayName, String description) {
        return new RegistrySearchResult(
            name, displayName, description, "1.0.0", "https://github.com/acme/thing",
            McpServerType.STDIO, "npx", true, null, "npx", List.of("-y", name), null, List.of(), "active", true
        );
    }

    private static boolean matches(RegistrySearchResult s, String query) {
        return RegistryQueryMatcher.matches(s, RegistryQueryMatcher.compile(query));
    }

    @Test
    void matchesOnName() {
        assertThat(matches(server("ai.smithery/kirbah-mcp-youtube", "mcp-youtube", "Structured video data"), "youtube"))
            .isTrue();
    }

    @Test
    void matchesOnDescriptionNotJustName() {
        // The whole point: upstream's name-only search misses this entirely.
        assertThat(matches(server("com.example/media-tools", "media-tools", "Fetch YouTube transcripts"), "transcript"))
            .isTrue();
    }

    @Test
    void isCaseInsensitive() {
        assertThat(matches(server("io.github.acme/GitHub", "GitHub", "Repos"), "github")).isTrue();
        assertThat(matches(server("io.github.acme/github", "github", "Repos"), "GITHUB")).isTrue();
    }

    @Test
    void everyTermMustMatchButTheyCanComeFromDifferentFields() {
        RegistrySearchResult s = server("com.example/media-tools", "media-tools", "Fetch YouTube transcripts");
        assertThat(matches(s, "youtube transcript")).isTrue();
        assertThat(matches(s, "youtube spreadsheet")).isFalse();
    }

    @Test
    void supportsStarAndQuestionWildcards() {
        RegistrySearchResult s = server("ai.trendsmcp/youtube", "youtube", "Trends");
        assertThat(matches(s, "you*be")).isTrue();
        assertThat(matches(s, "youtub?")).isTrue();
        assertThat(matches(s, "you*zz")).isFalse();
    }

    @Test
    void blankQueryMatchesEverything() {
        RegistrySearchResult s = server("a/b", "b", "c");
        assertThat(matches(s, "")).isTrue();
        assertThat(matches(s, null)).isTrue();
        assertThat(matches(s, "   ")).isTrue();
    }

    @Test
    void treatsRegexMetacharactersAsLiteralText() {
        // A query like "node.js" must not become the regex "node.js" (where
        // '.' matches anything) and must never throw on unbalanced brackets.
        assertThat(matches(server("a/b", "b", "runs on node.js"), "node.js")).isTrue();
        assertThat(matches(server("a/b", "b", "runs on nodexjs"), "node.js")).isFalse();
        assertThat(matches(server("a/b", "b", "c++ parser"), "c++")).isTrue();
        assertThat(matches(server("a/b", "b", "plain"), "[unclosed")).isFalse();
    }

    @Test
    void matchesOnRepositoryUrl() {
        assertThat(matches(server("x/y", "y", "no keywords here"), "acme")).isTrue();
    }

    @Test
    void ranksNameHitsAboveDescriptionOnlyHits() {
        List<Pattern> terms = RegistryQueryMatcher.compile("github");
        RegistrySearchResult byName = server("io.github.acme/github", "github", "unrelated");
        RegistrySearchResult byDescription = server("com.other/thing", "thing", "talks to github");
        assertThat(RegistryQueryMatcher.rank(byName, terms))
            .isLessThan(RegistryQueryMatcher.rank(byDescription, terms));
    }

    @Test
    void sortKeyIsStableAndCaseInsensitive() {
        assertThat(RegistryQueryMatcher.sortKey(server("a/Zebra", "Zebra", "")))
            .isEqualTo(RegistryQueryMatcher.sortKey(server("a/zebra", "zebra", "")));
    }
}

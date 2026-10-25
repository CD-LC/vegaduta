package com.agenticai.core.agents.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.search.ToolSearchService.Hit;
import com.agenticai.core.agents.tools.search.ToolSearchService.Index;
import com.agenticai.core.agents.tools.search.ToolSearchService.ToolEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The BM25 ranker: ordering, exact-name lookup, limits, determinism. No Spring, no I/O. */
class ToolSearchServiceTest {

    private final ToolSearchService service = new ToolSearchService();

    private static ToolEntry tool(String name, String description) {
        return new ToolEntry(name, description, null);
    }

    private static ToolEntry tool(String name, String description, String... params) {
        StringBuilder props = new StringBuilder();
        for (String param : params) {
            if (props.length() > 0) {
                props.append(',');
            }
            props.append('"').append(param).append("\":{\"type\":\"string\"}");
        }
        return new ToolEntry(
                name, description, "{\"type\":\"object\",\"properties\":{" + props + "}}");
    }

    private static List<String> names(List<Hit> hits) {
        return hits.stream().map(Hit::name).toList();
    }

    private Index catalog() {
        return service.index(
                List.of(
                        tool("google_gmail", "Reads, searches and sends Gmail email messages"),
                        tool(
                                "google_calendar",
                                "Creates and lists calendar events and sends invitations"),
                        tool(
                                "mcp__github__create_issue",
                                "Creates a new issue in a GitHub repository"),
                        tool(
                                "mcp__github__list_issues",
                                "Lists the open issues of a GitHub repository"),
                        tool("mcp__slack__post_message", "Posts a message to a Slack channel"),
                        tool("generate_image", "Generates an image from a text prompt"),
                        tool("fetch_web_page", "Fetches a web page and returns its text")));
    }

    @Test
    void ranksTheBestMatchFirstAndNeverPadsWithZeroScoreTools() {
        Index index = catalog();

        List<Hit> email = service.search(index, "send email", 5);
        assertEquals("google_gmail", email.get(0).name());

        // "slack" is only in one tool: exactly one hit, not a padded list of unrelated tools.
        assertEquals(List.of("mcp__slack__post_message"), names(service.search(index, "slack", 8)));
        // Nothing shares a term with this query.
        assertTrue(service.search(index, "zzzqqq", 8).isEmpty());
    }

    @Test
    void aNameMatchOutweighsTheSameTermInADescription() {
        Index index =
                service.index(
                        List.of(
                                tool("other_helper", "invoice invoice"),
                                tool("invoice_create", "Makes a record")));

        List<String> ranked = names(service.search(index, "invoice", 5));

        assertEquals(List.of("invoice_create", "other_helper"), ranked);
    }

    @Test
    void parameterNamesAreSearchable() {
        Index index =
                service.index(
                        List.of(
                                tool("lookup_record", "Finds one record", "customer_email"),
                                tool("find_thing", "Finds one thing", "identifier")));

        List<Hit> hits = service.search(index, "customer email", 5);

        assertEquals(List.of("lookup_record"), names(hits));
    }

    @Test
    void exactNameQueryPutsThatToolFirstEvenWhenOthersMentionItMoreOften() {
        Index index =
                service.index(
                        List.of(
                                tool(
                                        "alpha_helper",
                                        "wraps google_gmail google gmail gmail gmail gmail gmail"),
                                tool("google_gmail", "Mail")));

        List<Hit> hits = service.search(index, "Google_Gmail", 5);

        assertEquals("google_gmail", hits.get(0).name());
        assertTrue(hits.get(0).exact());
        assertFalse(hits.get(1).exact());
    }

    @Test
    void selectPrefixReturnsExactlyTheRequestedToolsInOrderCaseInsensitively() {
        Index index = catalog();

        List<Hit> hits = service.search(index, "select:fetch_web_page, GOOGLE_GMAIL ,nope", 8);

        assertEquals(List.of("fetch_web_page", "google_gmail"), names(hits));
        assertTrue(hits.stream().allMatch(Hit::exact));
    }

    @Test
    void limitIsClampedToTheDocumentedRange() {
        List<ToolEntry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(tool("report_tool_" + i, "builds a report"));
        }
        Index index = service.index(entries);

        assertEquals(ToolSearchService.MAX_LIMIT, service.search(index, "report", 100).size());
        assertEquals(ToolSearchService.DEFAULT_LIMIT, service.search(index, "report", 0).size());
        assertEquals(ToolSearchService.DEFAULT_LIMIT, service.search(index, "report", -3).size());
        assertEquals(3, service.search(index, "report", 3).size());
        assertEquals(8, ToolSearchService.MAX_LIMIT);
    }

    @Test
    void equalScoresBreakTiesOnTheToolNameSoOrderIsDeterministic() {
        Index index =
                service.index(
                        List.of(
                                tool("beta_report", "builds a quarterly summary"),
                                tool("alpha_report", "builds a quarterly summary")));

        List<String> first = names(service.search(index, "quarterly summary", 5));
        List<String> second = names(service.search(index, "quarterly summary", 5));

        assertEquals(List.of("alpha_report", "beta_report"), first);
        assertEquals(first, second);
    }

    @Test
    void searchIsIndependentOfCatalogInsertionOrder() {
        List<ToolEntry> entries =
                new ArrayList<>(
                        List.of(
                                tool("alpha_report", "builds a quarterly summary"),
                                tool("beta_report", "builds a quarterly summary"),
                                tool("gamma_export", "exports a quarterly summary to a file"),
                                tool("delta_mail", "sends mail")));
        List<String> forward =
                names(service.search(service.index(entries), "quarterly summary", 8));
        java.util.Collections.reverse(entries);
        List<String> backward =
                names(service.search(service.index(entries), "quarterly summary", 8));

        assertEquals(forward, backward);
    }

    @Test
    void pluralsAndCamelCaseAreFolded() {
        Index index =
                service.index(
                        List.of(
                                tool("createInvoice", "Bills a customer"),
                                tool("google_gmail", "Sends an email")));

        assertEquals(List.of("createInvoice"), names(service.search(index, "invoices", 5)));
        assertEquals(List.of("google_gmail"), names(service.search(index, "emails", 5)));
    }

    @Test
    void brandNamesInDescriptionsAreNotCutAtCapitalsButIdentifiersAre() {
        Index index =
                service.index(
                        List.of(
                                tool("repo_helper", "Works with GitHub repositories"),
                                tool("other_helper", "Does other work"),
                                tool("listOpenOrders", "Lists orders")));

        assertEquals(List.of("repo_helper"), names(service.search(index, "GitHub", 5)));
        // an identifier typed by the model matches the split name terms
        assertEquals("listOpenOrders", service.search(index, "list open orders", 5).get(0).name());
        assertEquals("listOpenOrders", service.search(index, "listOpenOrders", 5).get(0).name());
    }

    @Test
    void blankNullAndEmptyInputsReturnNothing() {
        Index index = catalog();

        assertTrue(service.search(index, "   ", 5).isEmpty());
        assertTrue(service.search(index, null, 5).isEmpty());
        assertTrue(service.search(null, "email", 5).isEmpty());
        assertTrue(service.search(service.index(List.of()), "email", 5).isEmpty());
        // a query made only of stop words has no scoreable term
        assertTrue(service.search(index, "the of and", 5).isEmpty());
    }

    @Test
    void indexKeepsTheFirstOfADuplicateNameAndSkipsBlankNames() {
        Index index =
                service.index(
                        List.of(
                                tool("dup_tool", "first description about apples"),
                                tool("DUP_TOOL", "second description about oranges"),
                                tool(" ", "blank name"),
                                tool("other_tool", "something else")));

        assertEquals(2, index.size());
        assertEquals(List.of("dup_tool"), names(service.search(index, "apples", 5)));
        assertTrue(service.search(index, "oranges", 5).isEmpty());
    }

    @Test
    void lookupAndContainsAreCaseInsensitiveExactMatches() {
        Index index = catalog();

        assertTrue(index.contains("Google_Gmail"));
        assertFalse(index.contains("google"));
        assertEquals("google_gmail", service.lookup(index, " GOOGLE_GMAIL ").orElseThrow().name());
        assertTrue(service.lookup(index, "gmail").isEmpty());
    }

    @Test
    void sourcesGroupMcpToolsByServerAndEverythingElseAsBuiltIn() {
        Map<String, Integer> sources = catalog().sources();

        assertEquals(2, sources.get("github").intValue());
        assertEquals(1, sources.get("slack").intValue());
        assertEquals(4, sources.get("built-in").intValue());
    }

    @Test
    void namesListsEveryIndexedToolInSortedOrderForTheNoMatchHint() {
        Index index = catalog();

        assertEquals(
                List.of(
                        "fetch_web_page",
                        "generate_image",
                        "google_calendar",
                        "google_gmail",
                        "mcp__github__create_issue",
                        "mcp__github__list_issues",
                        "mcp__slack__post_message"),
                index.names());
        assertTrue(service.index(List.of()).names().isEmpty());
    }

    @Test
    void parameterNamesComeFromTheSchemaIncludingNestedProperties() {
        Index index =
                service.index(
                        List.of(
                                new ToolEntry(
                                        "nested_tool",
                                        "x",
                                        "{\"type\":\"object\",\"properties\":{\"outer\":{\"type\":\"object\",\"properties\":{\"inner\":{\"type\":\"string\"}}}}}")));

        assertEquals(List.of("outer", "inner"), index.parameterNames("nested_tool"));
        assertTrue(index.parameterNames("missing").isEmpty());
    }

    @Test
    void anUnparsableSchemaOnlyCostsTheParameterNames() {
        Index index =
                service.index(
                        List.of(new ToolEntry("broken_schema_tool", "does things", "{not json")));

        assertEquals(
                List.of("broken_schema_tool"), names(service.search(index, "broken schema", 5)));
        assertTrue(index.parameterNames("broken_schema_tool").isEmpty());
    }

    @Test
    void oneLineCollapsesWhitespaceStopsAtTheFirstSentenceAndCapsLength() {
        assertEquals(
                "Posts a message.",
                ToolSearchService.oneLine("Posts a message.  Also does\nother things."));
        assertEquals("", ToolSearchService.oneLine(null));
        String longText = "x".repeat(400);
        String shortened = ToolSearchService.oneLine(longText);
        assertEquals(160, shortened.length());
        assertTrue(shortened.endsWith("..."));
    }
}

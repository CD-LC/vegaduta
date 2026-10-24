package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tool selection scored a message by the overlap of its words with each tool's name and description, and
 * a word was a run of {@code [a-z0-9]}. A message in Russian, Hindi, Chinese or Arabic therefore had no
 * words at all: every tool scored 0, the turn counted as "nothing matches" and was cut to the few
 * always-on tools, whatever the person asked for.
 */
class ToolRelevanceUnicodeTest extends ToolRelevanceSelectorTestSupport {

    @Test
    void wordsOfAnyScriptAreTokens() {
        assertThat(ToolRelevanceScoring.tokenize("Отправь счёт клиенту")).contains("отправь", "счёт", "клиенту");
        assertThat(ToolRelevanceScoring.tokenize("Café déjà-vu")).contains("café", "déjà");
        assertThat(ToolRelevanceScoring.tokenize("मुझे इनवॉइस भेजना है")).contains("इनवॉइस", "भेजना");
        assertThat(ToolRelevanceScoring.tokenize("أرسل الفاتورة")).contains("الفاتورة");
    }

    @Test
    void textWrittenWithoutSpacesIsSplitIntoOverlappingPairsOfCharacters() {
        assertThat(ToolRelevanceScoring.tokenize("发送发票给客户")).contains("发送", "送发", "发票", "客户");
        assertThat(ToolRelevanceScoring.tokenize("請求書を送る")).contains("請求", "求書", "送る");
    }

    @Test
    void latinTokenisationIsUnchanged() {
        assertThat(ToolRelevanceScoring.tokenize("Send the invoice, then email #42"))
                .containsExactlyInAnyOrder("send", "invoice", "email");
        assertThat(ToolRelevanceScoring.tokenize("  ")).isEmpty();
        assertThat(ToolRelevanceScoring.tokenize(null)).isEmpty();
    }

    @Test
    void aRussianRequestFindsTheRussianToolDespiteTheCatalogOrder() {
        List<ToolCallback> catalog = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            catalog.add(tool("tool_" + i, "Инструмент номер " + i + " для погоды"));
        }
        catalog.add(tool("send_invoice", "Отправить счёт клиенту по электронной почте"));
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 3, 8000);

        List<String> names =
                selector.select("Отправь счёт клиенту", catalog, Set.of(), Set.of()).stream()
                        .map(t -> t.getToolDefinition().name())
                        .toList();

        assertThat(names).contains("send_invoice");
    }

    @Test
    void aMessageInAScriptTheCatalogDoesNotShareIsNotTreatedAsMatchingNothing() {
        List<ToolCallback> catalog = englishCatalog(10);
        // 6 tools when relevance is known, 2 when "nothing matches"
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 6, 100_000, 2);

        int hindi = selector.select("मुझे इनवॉइस भेजना है", catalog, Set.of(), Set.of()).size();
        int english = selector.select("hello there", catalog, Set.of(), Set.of()).size();

        assertThat(english).as("a greeting that matches nothing keeps the small no-match budget").isEqualTo(2);
        assertThat(hindi)
                .as("a request in another script says nothing about relevance, so it gets the full budget")
                .isEqualTo(6);
    }

    @Test
    void theFullBudgetForAnUnmatchableScriptCanBeTurnedOff() {
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(true, 6, 100_000, 2, null, false, false);

        assertThat(selector.select("मुझे इनवॉइस भेजना है", englishCatalog(10), Set.of(), Set.of())).hasSize(2);
    }

    private static List<ToolCallback> englishCatalog(int size) {
        List<ToolCallback> catalog = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            catalog.add(tool("tool_" + i, "Does thing number " + i + " in the warehouse"));
        }
        return catalog;
    }
}

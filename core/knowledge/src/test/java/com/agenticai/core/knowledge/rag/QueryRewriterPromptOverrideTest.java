package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.knowledge.rag.RetrievalOptions.RewriteMode;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The retrieval query-expansion prompts come from the registry on the real {@code rewrite} path. */
class QueryRewriterPromptOverrideTest {

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final List<String> systems = new CopyOnWriteArrayList<>();
    private final QueryRewriter rewriter =
            new QueryRewriter(
                    (system, user, maxTokens) -> {
                        systems.add(system);
                        return Optional.of("alternative one\nalternative two");
                    },
                    2000);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theShippedPromptIsSentUntilATenantOverridesIt() {
        rewriter.setPromptResolver(resolver);
        rewriter.rewrite("what is the refund window?", RewriteMode.MULTI, 2);
        rewriter.rewrite("what is the refund window?", RewriteMode.HYDE, 2);
        assertEquals(PromptResolver.defaultsOnly().catalog().defaultText("rag.multi_query"), systems.get(0));
        assertEquals(PromptResolver.defaultsOnly().catalog().defaultText("rag.hyde"), systems.get(1));

        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", "rag.multi_query", "en", "Rewrite for German documents.", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.TENANT, "", "rag.hyde", "en", "Write a German passage.", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        rewriter.rewrite("what is the refund window?", RewriteMode.MULTI, 2);
        rewriter.rewrite("what is the refund window?", RewriteMode.HYDE, 2);
        assertEquals("Rewrite for German documents.", systems.get(2));
        assertEquals("Write a German passage.", systems.get(3));
    }
}

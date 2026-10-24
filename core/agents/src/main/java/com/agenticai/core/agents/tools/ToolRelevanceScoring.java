package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

/**
 * The cheap, deterministic scoring primitives of {@link ToolRelevanceSelector}: lexical overlap
 * with a bounded typo tolerance, tokenisation, cosine similarity for the semantic layer, and the
 * schema-token budget arithmetic. Pure and static; split out of the selector only to keep each
 * file reviewable - the selection policy itself stays in the selector.
 */
final class ToolRelevanceScoring {

    private static final Set<String> STOPWORDS =
            Set.of(
                    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "to", "of",
                    "in", "on", "for", "with", "and", "or", "but", "if", "then", "so", "as", "at",
                    "by", "from", "this", "that", "these", "those", "it", "its", "my", "your",
                    "our", "their", "i", "you", "we", "they", "he", "she", "do", "does", "did",
                    "can", "could", "will", "would", "should", "please", "me", "us", "them", "him",
                    "her", "what", "when", "where", "why", "how", "who", "which");

    static int score(Set<String> queryTokens, ToolCallback tool) {
        Set<String> toolTokens =
                tokenize(
                        tool.getToolDefinition().name()
                                + " "
                                + tool.getToolDefinition().description());
        int overlap = 0;
        for (String token : toolTokens) {
            if (queryTokens.contains(token) || hasNearMatch(token, queryTokens)) {
                overlap++;
            }
        }
        return overlap;
    }

    // #635 follow-up to #607/#616's sibling-stickiness fix: that mechanism
    // rescues a tool's siblings once ANY family member scores, but does
    // nothing when the message never lexically touches the family at all -
    // exactly what happened with "using pupeeter" (a real user typo for
    // "puppeteer"): zero tokens shared with "puppeteer_navigate" etc., so
    // the whole family scored 0 and never entered the cut, meaning the
    // rescue never even got a chance to fire. This is a second, narrow
    // scoring path - not a replacement for the exact-overlap check above,
    // which stays first and cheapest - that additionally counts a tool
    // token as "present" when it's within a small edit distance of some
    // query token, so a typo one or two keystrokes off from a real tool
    // name/description word still contributes to that tool's score.
    //
    // Deliberately still NOT an embeddings/LLM approach (see class javadoc):
    // Levenshtein distance over a short candidate list is the same "cheap,
    // deterministic" tradeoff as the exact-overlap scoring it augments, just
    // tolerant of single-keystroke noise. Bounded on both sides to stay
    // cheap and to avoid turning this into a fuzzy free-for-all that starts
    // matching unrelated short words to each other:
    //  - MIN_TOKEN_LENGTH_FOR_FUZZY excludes short tokens (3-4 letters)
    //    from fuzzy consideration entirely - a 1-character edit on a short
    //    word changes its meaning too easily ("api" vs "app") for near-
    //    matching to be safe there.
    //  - The allowed edit distance scales with token length (1 for
    //    shorter-but-still-eligible tokens, 2 once a token is long enough
    //    that a couple of stray keystrokes - e.g. "puppeteer" -> "pupeeter",
    //    edit distance 2 - are proportionally minor rather than a different
    //    word entirely.
    private static final int MIN_TOKEN_LENGTH_FOR_FUZZY = 5;
    private static final int MAX_FUZZY_EDIT_DISTANCE = 2;

    static boolean hasNearMatch(String toolToken, Set<String> queryTokens) {
        if (toolToken.length() < MIN_TOKEN_LENGTH_FOR_FUZZY) {
            return false;
        }
        int maxDistance = toolToken.length() >= 7 ? MAX_FUZZY_EDIT_DISTANCE : 1;
        for (String queryToken : queryTokens) {
            if (queryToken.length() < MIN_TOKEN_LENGTH_FOR_FUZZY) {
                continue;
            }
            // Cheap pre-filter before the O(n*m) DP call below - two strings
            // whose length already differs by more than the allowed edit
            // distance can never be within it.
            if (Math.abs(queryToken.length() - toolToken.length()) > maxDistance) {
                continue;
            }
            if (levenshtein(toolToken, queryToken) <= maxDistance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Standard iterative two-row Levenshtein edit distance - no external dependency needed for
     * this.
     */
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    static Set<String> tokenize(String text) {
        Set<String> tokens = new java.util.HashSet<>(WordTokens.words(text));
        tokens.removeAll(STOPWORDS);
        return tokens;
    }

    /**
     * Standard cosine similarity - both embedding vectors come from the same sidecar model, so no
     * dimensionality mismatch is expected; a defensive length check still avoids an AIOOBE if the
     * model configuration ever changes mid-cache-lifetime.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    static int totalSchemaTokens(List<ToolCallback> tools) {
        int sum = 0;
        for (ToolCallback t : tools) {
            sum += estimateSchemaTokens(t);
        }
        return sum;
    }

    /**
     * Admits extras while under {@code maxCount} and the token budget. The first extra of each
     * non-null {@code guaranteeKey} group is guaranteed (admitted even when over the token budget,
     * still subject to {@code maxCount}); guaranteed ones are taken first so budget-fitting extras
     * cannot crowd them out of the count cap. Output keeps the input order.
     */
    static List<ToolCallback> admitExtras(
            List<ToolCallback> extras,
            int maxCount,
            int tokenBudget,
            int[] tokensUsed,
            java.util.function.Function<ToolCallback, String> guaranteeKey) {
        Set<ToolCallback> chosen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<String> guaranteedGroups = new java.util.HashSet<>();
        for (ToolCallback t : extras) {
            if (chosen.size() >= maxCount) {
                break;
            }
            String key = guaranteeKey.apply(t);
            if (key != null && guaranteedGroups.add(key)) {
                chosen.add(t);
                tokensUsed[0] += estimateSchemaTokens(t);
            }
        }
        for (ToolCallback t : extras) {
            if (chosen.size() >= maxCount) {
                break;
            }
            if (chosen.contains(t)) {
                continue;
            }
            int cost = estimateSchemaTokens(t);
            if (tokensUsed[0] + cost <= tokenBudget) {
                chosen.add(t);
                tokensUsed[0] += cost;
            }
        }
        return extras.stream().filter(chosen::contains).toList();
    }

    /**
     * Cheap token-count proxy for a tool's schema cost - JSON string length / 4, the same rough
     * heuristic used elsewhere for estimating LLM token counts without pulling in a real tokenizer
     * dependency for what's ultimately a budget-trimming decision, not a billing one.
     */
    static int estimateSchemaTokens(ToolCallback tool) {
        String schema = tool.getToolDefinition().inputSchema();
        return schema == null || schema.isEmpty() ? 0 : schema.length() / 4;
    }

    private ToolRelevanceScoring() {}
}

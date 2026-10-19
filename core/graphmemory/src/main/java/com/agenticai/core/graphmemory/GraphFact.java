package com.agenticai.core.graphmemory;

import java.util.Locale;

/** One relationship recalled from the graph - see {@link GraphMemoryService#recallRelated}. */
public record GraphFact(String subject, String relation, String object) {

    /** A plain-English rendering, e.g. {@code "Priya manager of by Raj"} - good enough as LLM context, not meant as polished prose. */
    public String asSentence() {
        return subject + " " + relation.toLowerCase(Locale.ROOT).replace('_', ' ') + " " + object;
    }
}

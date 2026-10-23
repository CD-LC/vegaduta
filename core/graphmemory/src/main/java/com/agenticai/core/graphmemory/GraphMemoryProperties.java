package com.agenticai.core.graphmemory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.graph-memory.*} (the limits; the existing {@code arbitration.enabled}, {@code user-forget-sync.enabled}
 * and the other switches keep their own keys): how many facts one recall hands the model, how many graph paths it
 * reads to find them, how many known facts the write arbitration is shown, and how an erase-by-fact scan pages.
 * They were constants in {@link GraphMemoryService} and {@link MemoryWriteArbitrationService}; the defaults are
 * those values, mirrored in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.graph-memory")
public class GraphMemoryProperties {

    public static final int DEFAULT_MAX_RECALL_FACTS = 20;
    public static final int DEFAULT_MAX_RECALL_PATHS = 200;
    public static final int DEFAULT_MAX_CONTEXT_FACTS = 30;
    public static final int DEFAULT_FORGET_SCAN_PAGE = 500;
    public static final int DEFAULT_FORGET_SCAN_MAX_PAGES = 20;

    private final Recall recall = new Recall();
    private final Arbitration arbitration = new Arbitration();
    private final Forget forget = new Forget();

    public Recall getRecall() {
        return recall;
    }

    public Arbitration getArbitration() {
        return arbitration;
    }

    public Forget getForget() {
        return forget;
    }

    public static class Recall {
        /** Facts one recall returns (the model sees at most this many). */
        private int maxFacts = DEFAULT_MAX_RECALL_FACTS;
        /**
         * Graph paths read to fill {@code max-facts} (several paths can yield one fact, expired edges are dropped
         * in Java). Raise it for a densely connected graph; a recall then costs more.
         */
        private int maxPaths = DEFAULT_MAX_RECALL_PATHS;

        public int getMaxFacts() {
            return maxFacts;
        }

        public void setMaxFacts(int maxFacts) {
            this.maxFacts = Math.max(1, maxFacts);
        }

        public int getMaxPaths() {
            return maxPaths;
        }

        public void setMaxPaths(int maxPaths) {
            this.maxPaths = Math.max(1, maxPaths);
        }
    }

    public static class Arbitration {
        /** Already-known facts shown to the arbitration prompt across one candidate batch. */
        private int maxContextFacts = DEFAULT_MAX_CONTEXT_FACTS;

        public int getMaxContextFacts() {
            return maxContextFacts;
        }

        public void setMaxContextFacts(int maxContextFacts) {
            this.maxContextFacts = Math.max(1, maxContextFacts);
        }
    }

    public static class Forget {
        /** Edges read per page when a forgotten fact is looked up in the graph. */
        private int scanPage = DEFAULT_FORGET_SCAN_PAGE;
        /** Pages read at most: {@code scan-page * scan-max-pages} edges of one person are searched. */
        private int scanMaxPages = DEFAULT_FORGET_SCAN_MAX_PAGES;

        public int getScanPage() {
            return scanPage;
        }

        public void setScanPage(int scanPage) {
            this.scanPage = Math.max(1, scanPage);
        }

        public int getScanMaxPages() {
            return scanMaxPages;
        }

        public void setScanMaxPages(int scanMaxPages) {
            this.scanMaxPages = Math.max(1, scanMaxPages);
        }
    }
}

package com.agenticai.core.codeintel;

/**
 * The read-only analysis surface of the code-intel service (codebase_rag/analysis.py): repo map,
 * find_symbols, symbol_detail, outline, references, implementations, call path, dead code, test
 * linkage, api surface, hotspots, import cycles. All GETs, all tenant-scoped, zero risk. Extracted
 * out of {@link CodeIntelClient} because the file-size ratchet caps every file at 700 lines - the
 * transport ({@link CodeIntelClient#get} / {@link CodeIntelClient#enc}, tenant headers, error
 * shape) is shared, so this class is a thin per-endpoint layer over the same client. Obtain via
 * {@link CodeIntelClient#analysis()}.
 */
public class CodeIntelAnalysis {

    private final CodeIntelClient client;

    CodeIntelAnalysis(CodeIntelClient client) {
        this.client = client;
    }

    /**
     * PageRank-ranked repo skeleton (signatures, not bodies) under a char budget - the agent's
     * "learn the shape before fetching" call.
     */
    public String repoMap(String repoId, int budget, int topN) {
        return repoMapResult(repoId, budget, topN).body();
    }

    CodeIntelClient.Result repoMapResult(String repoId, int budget, int topN) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/map?budget="
                        + budget
                        + "&top_n="
                        + topN);
    }

    /** Name-path symbol search (kind/path_prefix filters). */
    public String findSymbols(
            String repoId, String query, String kind, String pathPrefix, int limit) {
        return findSymbolsResult(repoId, query, kind, pathPrefix, limit).body();
    }

    CodeIntelClient.Result findSymbolsResult(
            String repoId, String query, String kind, String pathPrefix, int limit) {
        StringBuilder q = new StringBuilder("?limit=").append(limit);
        if (query != null && !query.isBlank()) {
            q.append("&q=").append(CodeIntelClient.enc(query));
        }
        if (kind != null && !kind.isBlank()) {
            q.append("&kind=").append(CodeIntelClient.enc(kind));
        }
        if (pathPrefix != null && !pathPrefix.isBlank()) {
            q.append("&path_prefix=").append(CodeIntelClient.enc(pathPrefix));
        }
        return client.get("/repos/" + CodeIntelClient.enc(repoId) + "/symbols" + q);
    }

    /**
     * One symbol's metadata + exact source body (or span-only when {@code includeBody} is false).
     */
    public String symbolDetail(String repoId, String qualifiedName, boolean includeBody) {
        return symbolDetailResult(repoId, qualifiedName, includeBody).body();
    }

    CodeIntelClient.Result symbolDetailResult(
            String repoId, String qualifiedName, boolean includeBody) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/symbols/"
                        + CodeIntelClient.enc(qualifiedName)
                        + "?include_body="
                        + includeBody);
    }

    /** One file's symbol tree. */
    public String fileOutline(String repoId, String path) {
        return fileOutlineResult(repoId, path).body();
    }

    CodeIntelClient.Result fileOutlineResult(String repoId, String path) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/outline?path="
                        + CodeIntelClient.enc(path));
    }

    /**
     * Every inbound mention of a symbol: calls + inherits + value/type references (the "find all
     * references" query callers_of can't answer).
     */
    public String references(String repoId, String symbol) {
        return referencesResult(repoId, symbol).body();
    }

    CodeIntelClient.Result referencesResult(String repoId, String symbol) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/references/"
                        + CodeIntelClient.enc(symbol));
    }

    /** Recursive subclass/implementer tree over inherits edges. */
    public String implementations(String repoId, String symbol, int depth) {
        return implementationsResult(repoId, symbol, depth).body();
    }

    CodeIntelClient.Result implementationsResult(String repoId, String symbol, int depth) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/graph/implementations/"
                        + CodeIntelClient.enc(symbol)
                        + "?depth="
                        + depth);
    }

    /** BFS shortest call chain between two symbols. */
    public String callPath(String repoId, String src, String dst, int maxDepth) {
        return callPathResult(repoId, src, dst, maxDepth).body();
    }

    CodeIntelClient.Result callPathResult(String repoId, String src, String dst, int maxDepth) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/graph/path?src="
                        + CodeIntelClient.enc(src)
                        + "&dst="
                        + CodeIntelClient.enc(dst)
                        + "&max_depth="
                        + maxDepth);
    }

    /** Top-level symbols with zero inbound edges (review candidates). */
    public String deadCode(String repoId, int limit) {
        return deadCodeResult(repoId, limit).body();
    }

    CodeIntelClient.Result deadCodeResult(String repoId, int limit) {
        return client.get("/repos/" + CodeIntelClient.enc(repoId) + "/dead-code?limit=" + limit);
    }

    /** Test-side linkage: test-file symbols that reach the symbol. */
    public String testsFor(String repoId, String symbol) {
        return testsForResult(repoId, symbol).body();
    }

    CodeIntelClient.Result testsForResult(String repoId, String symbol) {
        return client.get(
                "/repos/" + CodeIntelClient.enc(repoId) + "/tests/" + CodeIntelClient.enc(symbol));
    }

    /** Extracted HTTP routes (FastAPI/Flask/Express/Spring/Go/Rust). */
    public String apiSurface(String repoId) {
        return apiSurfaceResult(repoId).body();
    }

    CodeIntelClient.Result apiSurfaceResult(String repoId) {
        return client.get("/repos/" + CodeIntelClient.enc(repoId) + "/api-surface");
    }

    /** git churn x size ranking - where bugs concentrate. */
    public String hotspots(String repoId, int days, int limit) {
        return hotspotsResult(repoId, days, limit).body();
    }

    CodeIntelClient.Result hotspotsResult(String repoId, int days, int limit) {
        return client.get(
                "/repos/"
                        + CodeIntelClient.enc(repoId)
                        + "/hotspots?days="
                        + days
                        + "&limit="
                        + limit);
    }

    /** File-level import-graph cycles (SCCs of size > 1). */
    public String importCycles(String repoId) {
        return importCyclesResult(repoId).body();
    }

    CodeIntelClient.Result importCyclesResult(String repoId) {
        return client.get("/repos/" + CodeIntelClient.enc(repoId) + "/import-cycles");
    }
}

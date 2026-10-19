package com.agenticai.core.agents.appbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The prompt's file section: code-intel chunks for one path are merged under
 * one header, only files app-builder sent in full are writable, and the
 * budget marks the overflow PARTIAL instead of silently clipping a file the
 * model would then rewrite in full.
 */
class AppBuilderPromptFilesTest {

    @Test
    void chunksOfOnePathAreMergedUnderOneHeader() {
        String blob = AppBuilderCompletionService.filesBlob(List.of(
            new AppBuilderFileEdit("src/App.jsx", "part one"),
            new AppBuilderFileEdit("src/App.jsx", "part two")), List.of());
        assertEquals(1, blob.split("# src/App.jsx", -1).length - 1);
        assertTrue(blob.contains("part one\n...\npart two"));
    }

    @Test
    void onlyFullFilesAreWritable() {
        String blob = AppBuilderCompletionService.filesBlob(List.of(
            new AppBuilderFileEdit("a.js", "whole"),
            new AppBuilderFileEdit("b.js", "excerpt")), List.of("a.js"));
        assertTrue(blob.contains("# a.js\nwhole"));
        assertTrue(blob.contains("# b.js  (PARTIAL"));
        assertFalse(blob.contains("# a.js  (PARTIAL"));
    }

    @Test
    void overflowPastTheBudgetIsMarkedPartial() {
        String big = "x".repeat(AppBuilderCompletionService.FILES_CHAR_BUDGET + 500);
        String blob = AppBuilderCompletionService.filesBlob(List.of(
            new AppBuilderFileEdit("big.js", big)), List.of("big.js"));
        assertTrue(blob.contains("# big.js  (PARTIAL"));
        assertTrue(blob.length() < big.length());
    }

    @Test
    void formatRetryKeepsInputsAndIsNotABuildFailure() {
        AppBuilderGenerateFilesRequest req = new AppBuilderGenerateFilesRequest(
            "do it", List.of(), "node", null, "ctx", List.of(), List.of("a.js"), List.of("a.js"), false);
        AppBuilderGenerateFilesRequest retry = req.asFormatRetry();
        assertTrue(retry.formatRetry());
        assertEquals(null, retry.previousFailure());
        assertEquals("ctx", retry.context());
        assertEquals(List.of("a.js"), retry.fullFilePaths());
    }
}

package com.agenticai.core.prompts;

import java.util.ArrayList;
import java.util.List;

/**
 * A line diff of two prompt texts for the audit log and the history view: lines only in the old text
 * are prefixed {@code "- "}, lines only in the new one {@code "+ "}, unchanged lines {@code "  "}.
 * Longest-common-subsequence; the texts are at most a few thousand characters.
 */
public final class PromptDiff {

    private PromptDiff() {}

    public static String unified(String oldText, String newText) {
        List<String> a = lines(oldText);
        List<String> b = lines(newText);
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] =
                        a.get(i).equals(b.get(j))
                                ? lcs[i + 1][j + 1] + 1
                                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                out.add("  " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + a.get(i++));
            } else {
                out.add("+ " + b.get(j++));
            }
        }
        while (i < a.size()) {
            out.add("- " + a.get(i++));
        }
        while (j < b.size()) {
            out.add("+ " + b.get(j++));
        }
        return String.join("\n", out);
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\n", -1));
    }
}

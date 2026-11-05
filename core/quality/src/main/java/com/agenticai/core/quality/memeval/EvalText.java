package com.agenticai.core.quality.memeval;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Text matching shared by the scorers: NFC, case-folded, whitespace-collapsed substring match. */
public final class EvalText {

    /** Phrases (English, Hindi, Hinglish) by which an answer admits it does not know. */
    static final List<String> ABSTENTION_MARKERS =
            List.of(
                    "i don't know",
                    "i do not know",
                    "don't have that",
                    "do not have that",
                    "not sure",
                    "no record",
                    "you haven't told me",
                    "you have not told me",
                    "pata nahi",
                    "pata nahin",
                    "mujhe nahi pata",
                    "नहीं पता",
                    "पता नहीं",
                    "नहीं जानता",
                    "नहीं जानती",
                    "जानकारी नहीं");

    private EvalText() {}

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        return nfc.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** True when {@code needle} (non-blank) occurs in {@code haystack} after normalisation. */
    public static boolean contains(String haystack, String needle) {
        String n = normalize(needle);
        return !n.isEmpty() && normalize(haystack).contains(n);
    }

    public static boolean abstains(String answer) {
        String a = normalize(answer == null ? "" : answer.replace('’', '\''));
        for (String marker : ABSTENTION_MARKERS) {
            if (a.contains(normalize(marker))) {
                return true;
            }
        }
        return false;
    }
}

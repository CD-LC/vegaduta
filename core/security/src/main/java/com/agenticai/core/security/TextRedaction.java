package com.agenticai.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linear-time redaction of e-mail addresses and PEM private-key blocks in text that may be
 * attacker-influenced (inbound channel text, tool output, model replies).
 *
 * <p>The patterns these replace ({@code \b[A-Za-z0-9._%+-]+@...} and {@code -----BEGIN [A-Z
 * ]*PRIVATE KEY-----[\s\S]*?-----END ...}) are quadratic on adversarial input: the first rescans a
 * long run of address characters from every start position, the second rescans to the end of the
 * text from every {@code BEGIN} line. Neither can be bounded with a cap alone without letting an
 * over-long address through unredacted, so:
 *
 * <ul>
 *   <li><b>E-mail.</b> A regular expression finds the domain and at most 64 characters before the
 *       {@code @} (64 is the RFC 5321 local-part limit, so the match can start at no more than 64
 *       positions per {@code @}); the span is then widened to the left, in code, over the rest of
 *       the address characters. An address with a 70- or 300-character local part is therefore
 *       redacted whole, never partly and never skipped. There is deliberately no leading {@code
 *       \b}: it would stop the 64-character window from matching inside a longer run.
 *   <li><b>PEM private keys.</b> No regular expression: {@link String#indexOf} finds {@code
 *       -----BEGIN }, the label is checked in code, and the nearest matching {@code -----END }
 *       closes the span. When no closing line exists after a header, no later header can have one
 *       either, so the scan stops: each character is read a bounded number of times.
 * </ul>
 *
 * <p>Both keep the semantics of the patterns they replace: an unterminated key block is left alone
 * (as before), and a block runs from its first {@code BEGIN} to the first closing {@code END} (as
 * the lazy pattern did).
 */
public final class TextRedaction {

    /** The e-mail finder: at most 64 local-part characters before the {@code @}, then a domain. */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private static final String BEGIN = "-----BEGIN ";
    private static final String END = "-----END ";
    private static final String KEY_LABEL = "PRIVATE KEY";
    private static final String DASHES = "-----";

    private TextRedaction() {}

    /**
     * Half-open character ranges {@code [start, end)} of every e-mail address in {@code text}, in
     * order, never overlapping. Takes a {@link CharSequence} so a caller can pass one that checks a
     * deadline as it is read.
     */
    public static List<int[]> emailSpans(CharSequence text) {
        List<int[]> spans = new ArrayList<>();
        if (text == null || text.length() == 0) {
            return spans;
        }
        Matcher m = EMAIL.matcher(text);
        int floor = 0;
        while (m.find()) {
            int start = m.start();
            // The regex saw only the last 64 characters of the local part; take the rest. The floor
            // keeps each character in one span, so the widening is linear over the whole text.
            while (start > floor && isLocalPartChar(text.charAt(start - 1))) {
                start--;
            }
            spans.add(new int[] {start, m.end()});
            floor = m.end();
        }
        return spans;
    }

    /** True when {@code text} contains an e-mail address. */
    public static boolean containsEmail(CharSequence text) {
        return text != null && text.length() > 0 && EMAIL.matcher(text).find();
    }

    /** {@code text} with every e-mail address replaced by {@code replacement} (used literally). */
    public static String redactEmails(String text, String replacement) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return replaceSpans(text, emailSpans(text), replacement);
    }

    /**
     * Half-open ranges {@code [start, end)} of every complete PEM private-key block in {@code text}
     * ({@code -----BEGIN [A-Z ]*PRIVATE KEY-----} up to the first {@code -----END [A-Z ]*PRIVATE
     * KEY-----}), in order.
     */
    public static List<int[]> privateKeySpans(String text) {
        List<int[]> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }
        int from = 0;
        while (true) {
            int begin = text.indexOf(BEGIN, from);
            if (begin < 0) {
                break;
            }
            int headerEnd = keyLabelEnd(text, begin + BEGIN.length());
            if (headerEnd < 0) {
                from = begin + 1;
                continue;
            }
            int footerEnd = -1;
            int scan = headerEnd;
            while (footerEnd < 0) {
                int end = text.indexOf(END, scan);
                if (end < 0) {
                    break;
                }
                footerEnd = keyLabelEnd(text, end + END.length());
                scan = end + 1;
            }
            if (footerEnd < 0) {
                // No closing line anywhere after this header, so none after a later one either.
                break;
            }
            spans.add(new int[] {begin, footerEnd});
            from = footerEnd;
        }
        return spans;
    }

    /** {@code text} with every complete PEM private-key block replaced by {@code replacement}. */
    public static String redactPrivateKeyBlocks(String text, String replacement) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return replaceSpans(text, privateKeySpans(text), replacement);
    }

    private static String replaceSpans(String text, List<int[]> spans, String replacement) {
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (int[] span : spans) {
            out.append(text, copied, span[0]).append(replacement);
            copied = span[1];
        }
        return out.append(text, copied, text.length()).toString();
    }

    /**
     * When {@code text} continues at {@code from} with {@code [A-Z ]*PRIVATE KEY-----}, the index
     * just past the dashes; otherwise -1. The label run cannot contain a dash, so it ends at the
     * first one and {@code PRIVATE KEY} must be the last eleven characters of the run.
     */
    private static int keyLabelEnd(String text, int from) {
        int i = from;
        int n = text.length();
        while (i < n && isLabelChar(text.charAt(i))) {
            i++;
        }
        if (i - from < KEY_LABEL.length()
                || !text.startsWith(KEY_LABEL, i - KEY_LABEL.length())
                || !text.startsWith(DASHES, i)) {
            return -1;
        }
        return i + DASHES.length();
    }

    private static boolean isLabelChar(char c) {
        return (c >= 'A' && c <= 'Z') || c == ' ';
    }

    private static boolean isLocalPartChar(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.'
                || c == '_'
                || c == '%'
                || c == '+'
                || c == '-';
    }
}

package com.agenticai.core.graphmemory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure text matching behind {@link GraphMemoryService#forgetFactText}: decides which currently-valid
 * edges a removed fact's text names both ends of, so the user-initiated forget sync can invalidate
 * them. No database access and no LLM call.
 */
final class GraphFactTextMatcher {

    private GraphFactTextMatcher() {}

    /** Entity names an extraction typically gives the speaker of a first-person fact ("I work at Acme"). */
    private static final Set<String> SELF_NAMES = Set.of("user", "the user", "i", "me", "myself", "self");

    private static final Pattern FIRST_PERSON = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(i|i'm|i've|i'd|me|my|mine|myself|we|our)(?![\\p{L}\\p{N}])");

    /** One currently-valid edge, names already stripped of agtype quotes. */
    record ValidEdge(String subject, String relation, String object) {
    }

    /** The edges {@code factText} names both ends of and none of {@code otherFactTexts} does. Pure. */
    static List<ValidEdge> edgesOnlyThisFactSupports(List<ValidEdge> edges, String factText, Collection<String> otherFactTexts) {
        List<ValidEdge> out = new ArrayList<>();
        if (edges == null || factText == null) {
            return out;
        }
        for (ValidEdge edge : edges) {
            if (edge == null || !factSupportsEdge(factText, edge.subject(), edge.object())) {
                continue;
            }
            boolean keptByAnother = false;
            if (otherFactTexts != null) {
                for (String other : otherFactTexts) {
                    if (other != null && factSupportsEdge(other, edge.subject(), edge.object())) {
                        keptByAnother = true;
                        break;
                    }
                }
            }
            if (!keptByAnother) {
                out.add(edge);
            }
        }
        return out;
    }

    /**
     * True when the text names both entities as whole words/phrases (case-insensitive). A first-person
     * text ("I work at Acme") also counts for a self entity ("User"), as long as the other end is named.
     */
    static boolean factSupportsEdge(String factText, String subjectName, String objectName) {
        if (factText == null || factText.isBlank() || unusableName(subjectName) || unusableName(objectName)) {
            return false;
        }
        String text = factText.toLowerCase(Locale.ROOT);
        String subject = subjectName.strip().toLowerCase(Locale.ROOT);
        String object = objectName.strip().toLowerCase(Locale.ROOT);
        boolean subjectNamed = containsPhrase(text, subject);
        boolean objectNamed = containsPhrase(text, object);
        if (subjectNamed && objectNamed) {
            return true;
        }
        boolean firstPerson = FIRST_PERSON.matcher(text).find();
        return firstPerson
            && ((objectNamed && SELF_NAMES.contains(subject)) || (subjectNamed && SELF_NAMES.contains(object)));
    }

    private static boolean unusableName(String name) {
        return name == null || name.isBlank() || name.indexOf('\\') >= 0;
    }

    private static boolean containsPhrase(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int end = at + needle.length();
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            boolean endOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }
}

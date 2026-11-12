package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every place that starts a chat turn must say who authored the turn's input. A turn is started by
 * building a {@code TurnContext} ({@code forEntry}, {@code atEntry}, {@code fromCurrentThread} or
 * the constructor); the taint layer only protects a turn whose entry marked {@code
 * TurnContext#taint()} when the sender is an outsider. An entry that forgets to decide is treated
 * as a bug here, not as "trusted by default", because the default is the unsafe direction.
 *
 * <p>A creation site counts as declared when, in the same source file, there is one declaration per
 * creation site, each being one of
 *
 * <ul>
 *   <li>{@code turnContext.taint().mark("inbound:...")} or {@code SenderTrust.X.seed(...)}: the
 *       entry seeds the taint;
 *   <li>a {@code // taint-entry: <trusted|internal|by job origin> - <reason>} comment next to the
 *       site: the entry is deliberately not tainted (an owner or tenant member acting in the
 *       console, an internal schedule, a sandboxed replay) and says why. A bare marker with no
 *       reason fails.
 * </ul>
 *
 * <p>Every file that creates a turn must also be named in {@code
 * docs/security/taint-entry-points.md} so the table of entries cannot drift from the code. Source
 * scan, no Spring context.
 */
class TurnEntryTrustDeclaredTest {

    private static final Pattern CREATION =
            Pattern.compile(
                    "\\bTurnContext\\.(?:forEntry|atEntry|fromCurrentThread)\\(|\\bnew\\s+TurnContext\\(");
    private static final Pattern SEEDS =
            Pattern.compile("\\.taint\\(\\)\\s*\\.mark\\(|\\bSenderTrust\\.[A-Z_]+\\.seed\\(");
    private static final Pattern DECLARED_COMMENT = Pattern.compile("taint-entry:([^\\n]*)");
    private static final String TURN_CONTEXT_FILE = "TurnContext.java";
    private static final String DOC = "docs/security/taint-entry-points.md";

    @Test
    void everyTurnCreationSiteDeclaresWhoAuthoredItsInput() throws IOException {
        Path modules = findModulesRoot();
        assertThat(modules)
                .as("core/ source root not reachable from " + System.getProperty("user.dir"))
                .isNotNull();
        String doc = Files.readString(modules.getParent().resolve(DOC), StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        int filesWithEntries = 0;
        for (Path file : mainSources(modules)) {
            if (file.getFileName().toString().equals(TURN_CONTEXT_FILE)) {
                continue;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String code = stripComments(raw);
            int sites = count(CREATION, code);
            if (sites == 0) {
                continue;
            }
            filesWithEntries++;
            String name = modules.relativize(file).toString();
            int seeded = count(SEEDS, code);
            int declared = 0;
            Matcher m = DECLARED_COMMENT.matcher(raw);
            while (m.find()) {
                if (m.group(1).trim().length() >= 15) {
                    declared++;
                } else {
                    offenders.add(name + ": a 'taint-entry:' marker without a reason");
                }
            }
            if (seeded + declared < sites) {
                offenders.add(
                        name
                                + ": "
                                + sites
                                + " turn creation site(s), "
                                + seeded
                                + " taint seed(s), "
                                + declared
                                + " 'taint-entry:' declaration(s)");
            }
            String simple = file.getFileName().toString().replace(".java", "");
            if (!doc.contains(simple)) {
                offenders.add(name + ": not listed in " + DOC);
            }
        }

        assertThat(filesWithEntries)
                .as(
                        "the scan found the entry points (a path change must not make this test vacuous)")
                .isGreaterThan(30);
        assertThat(offenders)
                .as(
                        "Declare who authored the input of each new turn entry: seed the taint with"
                                + " SenderTrust.UNTRUSTED.seed(turnContext.taint(), \"inbound:<x>\") for anything an outsider"
                                + " can author, or add a '// taint-entry: trusted - <reason>' comment, and list the entry in "
                                + DOC)
                .isEmpty();
    }

    @Test
    void theCommentStripperDoesNotHideOrInventCreationSites() {
        String src =
                "// TurnContext.forEntry(a, b)\n/* new TurnContext(x) */\nString s = \"x\"; TurnContext.forEntry(t, r);\n";
        assertThat(count(CREATION, stripComments(src))).isEqualTo(1);
    }

    private static List<Path> mainSources(Path modules) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> moduleDirs = Files.list(modules)) {
            for (Path module : (Iterable<Path>) moduleDirs::iterator) {
                Path main = module.resolve("src").resolve("main").resolve("java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
                }
            }
        }
        return out;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * Removes block and line comments (string literals containing comment markers are rare enough
     * here).
     */
    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    private static Path findModulesRoot() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (isModulesRoot(p)) {
                return p;
            }
            if (isModulesRoot(p.resolve("core"))) {
                return p.resolve("core");
            }
        }
        return null;
    }

    private static boolean isModulesRoot(Path dir) {
        return Files.isDirectory(dir.resolve("app").resolve("src"))
                && Files.isDirectory(dir.resolve("tenancy").resolve("src"));
    }
}

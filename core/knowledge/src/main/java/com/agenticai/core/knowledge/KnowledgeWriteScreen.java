package com.agenticai.core.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Write-time screen for text an AGENT is about to persist into a knowledge collection
 * ({@code add_to_knowledge_base}).
 *
 * <p>Whatever lands in a collection is retrieved into a later model context, possibly for a
 * different user, so a note that carries instructions is a stored prompt injection (OWASP ASI06
 * Memory &amp; Context Poisoning, LLM04), and a note that carries a credential is a leak waiting
 * for a search hit (LLM02). The read path already screens tool output; this closes the write side
 * with the same phrase families {@code PatternBasedGuardrailService} hard-blocks plus the
 * credential shapes it redacts. It is a regex tripwire, not a classifier: a paraphrased
 * injection passes. This module cannot depend on {@code guardrails}, so the lists are repeated
 * here on purpose; the human-upload {@code /ingest} path is unscreened by design (an
 * authenticated person chose that content).
 */
final class KnowledgeWriteScreen {

    private static final List<Pattern> INJECTION = List.of(
        Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?(above|previous|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
        Pattern.compile("disregard\\s+(your|the)\\s+(system\\s+prompt|instructions)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("reveal\\s+(your|the)\\s+system\\s+prompt", Pattern.CASE_INSENSITIVE),
        Pattern.compile("you\\s+are\\s+now\\s+in\\s+(developer|dan|jailbreak)\\s+mode", Pattern.CASE_INSENSITIVE),
        Pattern.compile("###\\s*(system|admin|override)\\s*###", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?m)^\\s*(system|assistant)\\s*:\\s*(you|ignore|from now)", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> SECRETS = List.of(
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"),
        Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
        Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
        Pattern.compile("sk-[A-Za-z0-9]{20,}"),
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
    );

    private KnowledgeWriteScreen() {
    }

    /** A caller-safe reason the text must not be stored, or empty when it passes. Never echoes the match. */
    static Optional<String> refusal(String content) {
        for (Pattern p : INJECTION) {
            if (p.matcher(content).find()) {
                return Optional.of("content looks like instructions aimed at a future model (prompt injection) "
                    + "and was not saved - store facts, not directives");
            }
        }
        for (Pattern p : SECRETS) {
            if (p.matcher(content).find()) {
                return Optional.of("content appears to contain a credential or private key and was not saved");
            }
        }
        return Optional.empty();
    }
}

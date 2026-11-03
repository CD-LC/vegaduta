package com.agenticai.core.knowledge;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Write-time screen for text an AGENT is about to persist as long-term memory ({@code
 * remember_fact}, {@code remember_shared_fact}, graph triples derived from them).
 *
 * <p>Memory is recalled into a later prompt, possibly months on and for other agents, so a stored
 * directive is a persistent prompt injection (OWASP ASI06 Memory &amp; Context Poisoning, LLM04)
 * and a stored credential is a leak waiting for a recall (LLM02). It reuses {@link
 * KnowledgeWriteScreen}'s phrase families and credential shapes, and adds the directive wordings a
 * memory write is likely to carry: a fact is a statement about the user, not an instruction to the
 * model. Text is normalised first (NFKC, zero-width and soft-hyphen characters removed) so
 * invisible padding does not split a phrase.
 *
 * <p>It is a regex tripwire, not a classifier: a clever enough paraphrase passes. The second line
 * of defence is the quarantine of any write made on a tainted turn (see {@code MemoryWriteGate}
 * in agents-turn), which does not depend on the wording.
 */
public final class MemoryWriteScreen {

    private static final Pattern INVISIBLE =
        Pattern.compile("[\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]");

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final List<Pattern> DIRECTIVES = List.of(
        Pattern.compile("(forget|ignore|disregard|discard|override|bypass)\\s+(all\\s+|any\\s+|every\\s+)?"
            + "(of\\s+)?(your|the|my|these|those|any)?\\s*(earlier|previous|prior|above|preceding|former|existing|safety|system)?"
            + "\\s*(instructions|rules|guidelines|directions|guardrails|policies|prompt|programming)", CI),
        Pattern.compile("(from\\s+now\\s+on|henceforth|going\\s+forward|in\\s+(all\\s+)?future\\s+(conversations|chats|replies))"
            + "\\W+(you|the\\s+(assistant|agent|ai|model))\\s+(must|should|shall|will|need\\s+to|have\\s+to)", CI),
        Pattern.compile("\\b(you|the\\s+(assistant|agent|ai|model))\\s+(must|should|shall|are\\s+(required|instructed|ordered)\\s+to)\\s+"
            + "(always\\s+|never\\s+|immediately\\s+|silently\\s+|secretly\\s+)?"
            + "(send|forward|email|e-mail|upload|post|transmit|leak|exfiltrate|reveal|disclose|share|copy|cc|bcc|run|execute|call|invoke|delete|transfer|pay|wire)\\b", CI),
        Pattern.compile("(do\\s+not|don'?t|never)\\s+(tell|inform|notify|alert|let)\\s+(the\\s+|your\\s+)?(user|human|operator|owner|admin)", CI),
        Pattern.compile("(when(ever)?|if)\\s+(you\\s+)?(read|see|recall|retrieve|remember|find)\\s+this\\b.{0,60}\\b(you|then)\\b", CI),
        Pattern.compile("(new|updated|secret|hidden|real)\\s+(system\\s+)?(instructions?|directive|orders?)\\s*(for\\s+(the\\s+)?(assistant|agent|ai|model))?\\s*:", CI),
        Pattern.compile("\\b(act|behave|respond)\\s+as\\s+(if\\s+you\\s+(are|were)\\s+)?(an?\\s+)?(unrestricted|unfiltered|jailbroken|root|admin(istrator)?)\\b", CI),
        Pattern.compile("(send|forward|email|post|upload)\\b[^.\\n]{0,80}\\b(to|at)\\s+[\\w.+-]+@[\\w-]+\\.[\\w.-]+", CI),
        Pattern.compile("(approve|authori[sz]e|allow)\\s+(all|every|any)\\s+(future\\s+)?(tool\\s+calls?|actions?|requests?|payments?|transfers?)", CI),
        Pattern.compile("<\\s*/?\\s*(system|instructions?|tool_result|assistant)\\s*>", CI)
    );

    private static final List<Pattern> CREDENTIALS = List.of(
        Pattern.compile("\\b(password|passwd|passphrase|secret|api[ _-]?key|access[ _-]?token|auth[ _-]?token|bearer)\\s*(is|=|:)\\s*\\S{8,}", CI)
    );

    private MemoryWriteScreen() {
    }

    /** A caller-safe reason the text must not be stored, or empty when it passes. Never echoes the match. */
    public static Optional<String> refusal(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String text = INVISIBLE.matcher(Normalizer.normalize(content, Normalizer.Form.NFKC)).replaceAll("");
        Optional<String> base = KnowledgeWriteScreen.refusal(text);
        if (base.isPresent()) {
            return base;
        }
        for (Pattern p : DIRECTIVES) {
            if (p.matcher(text).find()) {
                return Optional.of("content looks like instructions aimed at a future model (prompt injection) "
                    + "and was not saved - remember facts about the user, not directives");
            }
        }
        for (Pattern p : CREDENTIALS) {
            if (p.matcher(text).find()) {
                return Optional.of("content appears to contain a credential and was not saved");
            }
        }
        return Optional.empty();
    }
}

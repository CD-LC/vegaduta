package com.agenticai.core.knowledge;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Write-time screen for text an AGENT is about to persist as long-term memory ({@code
 * remember_fact}, {@code remember_shared_fact}, {@code add_to_knowledge_base}, graph triples
 * derived from them).
 *
 * <p>Memory is recalled into a later prompt, possibly months on and for other agents, so a stored
 * directive is a persistent prompt injection (OWASP ASI06 Memory &amp; Context Poisoning, LLM04)
 * and a stored credential is a leak waiting for a recall (LLM02). It reuses {@link
 * KnowledgeWriteScreen}'s phrase families and credential shapes and adds, in order:
 *
 * <ol>
 *   <li>structural refusals on the raw text: hidden HTML (comments, {@code display:none} and
 *       similar), remote markdown images and URLs that carry data in a query string or a template
 *       placeholder (exfiltration channels), and forged platform control markers;
 *   <li>normalisation first: NFKC, zero-width and bidi characters, Arabic diacritics, chunk-marker
 *       noise and tags removed, common Cyrillic and Greek lookalikes and digit-for-letter
 *       substitutions folded, then the phrase patterns run on that view, on its ROT13 and on its
 *       reversal;
 *   <li>decode-and-rescan of long tokens that are base64, hex or percent-encoded text (two levels);
 *   <li>imperative-directive patterns in English, Spanish, French, German, Russian, Hindi, Chinese
 *       and Arabic.
 * </ol>
 *
 * <p><b>This is a tripwire, not a classifier, and it is honest about that.</b> A phrase list covers
 * only a minority of the ways an instruction can be paraphrased, translated, encoded or split: a
 * red-team run measured the earlier English-only list refusing 2 of 24 styles on a clean turn.
 * Every pattern added here widens that, none closes it, and a new language or a fresh paraphrase
 * passes. The real control is the taint gate: a write made on a turn that handled untrusted content
 * is stored QUARANTINED whatever its wording (see {@code MemoryWriteGate} in agents-turn and {@link
 * AddToKnowledgeBaseToolCallback}). The screen only stops the cheap, obvious cases on a clean turn
 * and keeps a credential out of long-term storage.
 */
public final class MemoryWriteScreen {

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final int MAX_DECODE_DEPTH = 2;

    private static final String INJECTION_REASON =
            "content looks like instructions aimed at a future model "
                    + "(prompt injection) and was not saved - remember facts about the user, not directives";
    private static final String EXFIL_REASON =
            "content carries a link or hidden markup that could leak data "
                    + "when it is recalled, and was not saved";
    private static final String CREDENTIAL_REASON =
            "content appears to contain a credential and was not saved";

    // ---- normalisation
    // ---------------------------------------------------------------------------------------

    private static final Pattern INVISIBLE =
            Pattern.compile(
                    "[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\u180E]"
                            + "|[\\u064B-\\u065F\\u0670\\u0640]");
    private static final Pattern CHUNK_MARKER =
            Pattern.compile("\\[\\s*(chunk|part|page)\\s*\\d+\\s*/\\s*\\d+\\s*]", CI);
    private static final Pattern TAG = Pattern.compile("<[^>]{1,400}>");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /**
     * Lookalike letters (Cyrillic, Greek) folded to the Latin letter they imitate, for the folded
     * view only.
     */
    private static String foldLookalikes(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(
                    switch (c) {
                        case 'а', 'α' -> 'a';
                        case 'е', 'ε' -> 'e';
                        case 'о', 'ο' -> 'o';
                        case 'р', 'ρ' -> 'p';
                        case 'с' -> 'c';
                        case 'х', 'χ' -> 'x';
                        case 'у' -> 'y';
                        case 'і', 'ι' -> 'i';
                        case 'к', 'κ' -> 'k';
                        case 'м' -> 'm';
                        case 'т', 'τ' -> 't';
                        case 'н', 'η' -> 'h';
                        case 'в', 'β' -> 'b';
                        case 'А', 'Α' -> 'A';
                        case 'Е', 'Ε' -> 'E';
                        case 'О', 'Ο' -> 'O';
                        case 'С' -> 'C';
                        case 'Т', 'Τ' -> 'T';
                        case 'Н', 'Η' -> 'H';
                        case 'І', 'Ι' -> 'I';
                        default -> c;
                    });
        }
        return sb.toString();
    }

    /**
     * Digits and symbols commonly swapped for letters ("1gn0r3 pr3v10us"), folded for the folded
     * view only.
     */
    private static String foldLeet(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(
                    switch (c) {
                        case '0' -> 'o';
                        case '1' -> 'i';
                        case '3' -> 'e';
                        case '4', '@' -> 'a';
                        case '5', '$' -> 's';
                        case '7' -> 't';
                        default -> c;
                    });
        }
        return sb.toString();
    }

    private static String rot13(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) ('a' + (c - 'a' + 13) % 26));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('A' + (c - 'A' + 13) % 26));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- structural (raw text)
    // -------------------------------------------------------------------------------

    private static final List<Pattern> HIDDEN_MARKUP =
            List.of(
                    Pattern.compile("<!--"),
                    Pattern.compile(
                            "<[^>]*\\b(display\\s*:\\s*none|visibility\\s*:\\s*hidden|font-size\\s*:\\s*0|opacity\\s*:\\s*0"
                                    + "|aria-hidden\\s*=|\\bhidden\\b)[^>]*>",
                            CI),
                    Pattern.compile("<\\s*(script|style|iframe|object|embed)\\b", CI));

    private static final List<Pattern> EXFIL =
            List.of(
                    // a remote markdown or HTML image: fetched automatically when the recalled text
                    // is rendered
                    Pattern.compile("!\\[[^\\]]*]\\(\\s*(https?:)?//", CI),
                    Pattern.compile("<\\s*img\\b[^>]*\\bsrc\\s*=", CI),
                    // a link or bare URL that carries data in its query string, fragment or a
                    // template placeholder
                    Pattern.compile("https?://[^\\s)>\"']+[?#][^\\s)>\"']*=", CI),
                    Pattern.compile("https?://[^\\s)>\"']*(\\{\\{|\\$\\{|%7B%7B)", CI),
                    Pattern.compile("\\[[^\\]]*]\\(\\s*https?://[^)]*[?#][^)]*\\)", CI));

    private static final List<Pattern> CONTROL_FORGERY =
            List.of(
                    Pattern.compile("approval_parked|tool_budget_exhausted", CI),
                    Pattern.compile("<<<\\s*(END_)?UNTRUSTED_DATA", CI),
                    Pattern.compile(
                            "<\\s*/?\\s*(system|instructions?|tool_output|tool_result|assistant|developer)\\s*>",
                            CI),
                    Pattern.compile(
                            "_instruction_to_agent|instructions?_for_(the_)?(ai|agent|assistant|model)",
                            CI));

    // ---- directives (normalised views)
    // -----------------------------------------------------------------------

    private static final List<Pattern> DIRECTIVES_EN =
            List.of(
                    Pattern.compile(
                            "(forget|ignore|disregard|discard|override|bypass)\\s+(all\\s+|any\\s+|every\\s+)?"
                                    + "(of\\s+)?(your|the|my|these|those|any)?\\s*(earlier|previous|prior|above|preceding|former|existing|safety|system)?"
                                    + "\\s*(instructions|rules|guidelines|directions|guardrails|policies|prompt|programming)",
                            CI),
                    Pattern.compile(
                            "set\\s+aside\\s+(whatever|everything|all|any)\\b.{0,60}\\b(told|instructed|said|given)",
                            CI),
                    Pattern.compile(
                            "(from\\s+now\\s+on|henceforth|going\\s+forward|in\\s+(all\\s+)?future\\s+(conversations|chats|replies))"
                                    + "\\W+(you|the\\s+(assistant|agent|ai|model))\\s+(must|should|shall|will|need\\s+to|have\\s+to)",
                            CI),
                    Pattern.compile(
                            "\\b(you|the\\s+(assistant|agent|ai|model))\\s+(must|should|shall|are\\s+(required|instructed|ordered)\\s+to)\\s+"
                                    + "(now\\s+|always\\s+|never\\s+|immediately\\s+|silently\\s+|secretly\\s+)*"
                                    + "(send|forward|email|e-mail|upload|post|transmit|leak|exfiltrate|reveal|disclose|share|copy|cc|bcc|run|execute|call|invoke|delete|transfer|pay|wire)\\b",
                            CI),
                    Pattern.compile("\\byou\\s+must\\s+now\\b", CI),
                    Pattern.compile(
                            "(notice|message|note|order|directive)s?\\s+from\\s+(the\\s+)?(system|admin\\w*|developer|operator|security)",
                            CI),
                    Pattern.compile(
                            "(do\\s+not|don'?t|never)\\s+(tell|inform|notify|alert|let)\\s+(the\\s+|your\\s+)?(user|human|operator|owner|admin)",
                            CI),
                    Pattern.compile(
                            "(when(ever)?|if)\\s+(you\\s+)?(read|see|recall|retrieve|remember|find)\\s+this\\b.{0,60}\\b(you|then)\\b",
                            CI),
                    Pattern.compile(
                            "(new|updated|secret|hidden|real)\\s+(system\\s+)?(instructions?|directive|orders?)\\s*(for\\s+(the\\s+)?(assistant|agent|ai|model))?\\s*:",
                            CI),
                    Pattern.compile(
                            "\\b(act|behave|respond)\\s+as\\s+(if\\s+you\\s+(are|were)\\s+)?(an?\\s+)?(unrestricted|unfiltered|jailbroken|root|admin(istrator)?)\\b",
                            CI),
                    Pattern.compile(
                            "(send|forward|email|post|upload)\\b[^.\\n]{0,80}\\b(to|at)\\s+[\\w.+-]+@[\\w-]+\\.[\\w.-]+",
                            CI),
                    Pattern.compile(
                            "(approve|authori[sz]e|allow)\\s+(all|every|any)\\s+(future\\s+)?(tool\\s+calls?|actions?|requests?|payments?|transfers?)",
                            CI),
                    // a claim that the person already authorised everything is how a poisoned fact
                    // unlocks a later action
                    Pattern.compile(
                            "\\b(user|owner|admin\\w*|customer)\\s+(has\\s+|have\\s+)?(already\\s+)?(pre-?\\s?)?(approved|authori[sz]ed|consented\\s+to)\\s+(all|every|any)",
                            CI),
                    Pattern.compile("(?m)^\\s*(assistant|system|user|human|developer)\\s*:", CI));

    private static final List<Pattern> DIRECTIVES_OTHER_LANGUAGES =
            List.of(
                    // Spanish
                    Pattern.compile(
                            "(ignora|ignore|ignorar|olvida|olvidar|descarta|omite)\\s+(todas\\s+)?(las\\s+|tus\\s+|esas\\s+)?(instrucciones|reglas|indicaciones|directrices)"
                                    + "(\\s+(anteriores|previas|de\\s+arriba|del\\s+sistema))?",
                            CI),
                    Pattern.compile(
                            "a\\s+partir\\s+de\\s+ahora.{0,30}(debes|tienes\\s+que|deber[aá]s)",
                            CI),
                    Pattern.compile("no\\s+(le\\s+)?(digas|informes|avises)\\s+al\\s+usuario", CI),
                    // French
                    Pattern.compile(
                            "(ignore[zr]?|oublie[zr]?)\\s+(toutes\\s+)?(les\\s+|vos\\s+|tes\\s+)?(instructions|r[eè]gles|consignes)(\\s+pr[eé]c[eé]dentes)?",
                            CI),
                    Pattern.compile(
                            "[àa]\\s+partir\\s+de\\s+maintenant.{0,30}(tu\\s+dois|vous\\s+devez)",
                            CI),
                    // German
                    Pattern.compile(
                            "(ignoriere?|vergiss)\\s+(alle\\s+)?(vorherigen|obigen|bisherigen|deine)?\\s*(anweisungen|regeln|instruktionen)",
                            CI),
                    // Russian
                    Pattern.compile(
                            "(игнорир|забуд|отброс|пренебреги|не\\s+учитывай)\\S*\\s+(все\\s+|любые\\s+|свои\\s+)?(предыдущ|прежн|прошл|выше|систем)?\\S*\\s*(инструкци|указани|правил|команд)",
                            CI),
                    Pattern.compile(
                            "с\\s+этого\\s+момента\\s+(ты|вы)\\s+(должен|должны|обязан)", CI),
                    Pattern.compile(
                            "не\\s+(говори|сообщай)\\s+(об\\s+этом\\s+)?(пользователю|человеку)",
                            CI),
                    // Hindi (Devanagari)
                    Pattern.compile(
                            "(निर्देश|आदेश|नियम)\\S*\\s+(को\\s+|का\\s+)?(अनदेखा|नज़रअंदाज़|नजरअंदाज|भूल)",
                            CI),
                    Pattern.compile("(अनदेखा|नज़रअंदाज़|नजरअंदाज)\\s+(करें|करो|कर\\s+दें)", CI),
                    Pattern.compile("अब\\s+से\\s+(आप|तुम)\\s+.{0,30}(करना\\s+होगा|चाहिए|करें)", CI),
                    // Chinese
                    Pattern.compile(
                            "(忽略|无视|忽视|忘记|忘掉|不要理会|別理會|忽略掉).{0,10}(之前|以前|上面|先前|以上|所有|全部|任何).{0,8}(指令|指示|说明|規則|规则|提示|指令|命令|要求)"),
                    Pattern.compile("(从现在开始|從現在開始|今后|今後).{0,8}(你必须|你应该|你要|你需要|你必須|你應該)"),
                    Pattern.compile("(不要|別|不得|切勿).{0,4}(告诉|告訴|通知).{0,4}(用户|用戶|使用者)"),
                    // Arabic (diacritics and tatweel already removed by normalisation)
                    Pattern.compile(
                            "(تجاهل|تجاهلي|انس|انسى|اهمل|أهمل)\\s+.{0,30}(التعليمات|الأوامر|القواعد|الإرشادات|التوجيهات)"),
                    Pattern.compile(
                            "(من\\s+الآن\\s+فصاعد[اً]?|من\\s+الان\\s+فصاعد[اً]?).{0,30}(يجب\\s+عليك|عليك\\s+أن)"),
                    Pattern.compile("لا\\s+(تخبر|تقل|تخبري)\\s+(ال)?مستخدم"));

    private static final List<Pattern> CREDENTIALS =
            List.of(
                    Pattern.compile(
                            "\\b(password|passwd|passphrase|secret|api[ _-]?key|access[ _-]?token|auth[ _-]?token|bearer)\\s*(is|=|:)\\s*\\S{8,}",
                            CI));

    // ---- decode-and-rescan
    // -----------------------------------------------------------------------------------

    private static final Pattern BASE64_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{16,}={0,2}");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?:[0-9a-fA-F]{2}){12,}");
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");

    private MemoryWriteScreen() {}

    /**
     * A caller-safe reason the text must not be stored, or empty when it passes. Never echoes the
     * match.
     */
    public static Optional<String> refusal(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        return scan(content, 0);
    }

    private static Optional<String> scan(String raw, int depth) {
        // 1. structural, on the raw text (normalisation would hide the markup)
        for (Pattern p : HIDDEN_MARKUP) {
            if (p.matcher(raw).find()) {
                return Optional.of(EXFIL_REASON);
            }
        }
        for (Pattern p : EXFIL) {
            if (p.matcher(raw).find()) {
                return Optional.of(EXFIL_REASON);
            }
        }
        for (Pattern p : CONTROL_FORGERY) {
            if (p.matcher(raw).find()) {
                return Optional.of(INJECTION_REASON);
            }
        }

        // 2. normalised views
        String normalised =
                MULTI_SPACE
                        .matcher(
                                CHUNK_MARKER
                                        .matcher(
                                                INVISIBLE
                                                        .matcher(
                                                                Normalizer.normalize(
                                                                        raw, Normalizer.Form.NFKC))
                                                        .replaceAll(""))
                                        .replaceAll(" "))
                        .replaceAll(" ")
                        .strip();
        String detagged =
                MULTI_SPACE
                        .matcher(TAG.matcher(normalised).replaceAll(" "))
                        .replaceAll(" ")
                        .strip();
        String folded = foldLeet(foldLookalikes(detagged));
        for (String view :
                new String[] {
                    normalised,
                    detagged,
                    folded,
                    rot13(folded),
                    new StringBuilder(folded).reverse().toString()
                }) {
            Optional<String> hit = scanView(view);
            if (hit.isPresent()) {
                return hit;
            }
        }

        // 3. encoded payloads
        if (depth < MAX_DECODE_DEPTH) {
            for (String decoded : decodedTokens(raw)) {
                Optional<String> hit = scan(decoded, depth + 1);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> scanView(String view) {
        if (KnowledgeWriteScreen.refusal(view).isPresent()) {
            return Optional.of(INJECTION_REASON);
        }
        for (Pattern p : DIRECTIVES_EN) {
            if (p.matcher(view).find()) {
                return Optional.of(INJECTION_REASON);
            }
        }
        for (Pattern p : DIRECTIVES_OTHER_LANGUAGES) {
            if (p.matcher(view).find()) {
                return Optional.of(INJECTION_REASON);
            }
        }
        for (Pattern p : CREDENTIALS) {
            if (p.matcher(view).find()) {
                return Optional.of(CREDENTIAL_REASON);
            }
        }
        return Optional.empty();
    }

    /**
     * Text decoded from base64, hex and percent-encoded runs in {@code s}; only mostly-printable
     * results.
     */
    private static List<String> decodedTokens(String s) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(2);
        Matcher b64 = BASE64_TOKEN.matcher(s);
        while (b64.find() && out.size() < 8) {
            decodeBase64(b64.group()).ifPresent(out::add);
        }
        Matcher hex = HEX_TOKEN.matcher(s);
        while (hex.find() && out.size() < 8) {
            String h = hex.group();
            byte[] bytes = new byte[h.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            }
            printable(bytes).ifPresent(out::add);
        }
        if (PERCENT_ESCAPE.matcher(s).results().count() >= 3) {
            try {
                String decoded = URLDecoder.decode(s, StandardCharsets.UTF_8);
                if (!decoded.equals(s)) {
                    out.add(decoded);
                }
            } catch (IllegalArgumentException ignored) {
                // malformed escape: nothing to rescan
            }
        }
        return out;
    }

    private static Optional<String> decodeBase64(String token) {
        String t = token;
        int pad = (4 - t.replace("=", "").length() % 4) % 4;
        t = t.replace("=", "") + "=".repeat(pad == 3 ? 0 : pad);
        try {
            byte[] raw =
                    (t.contains("-") || t.contains("_")
                                    ? Base64.getUrlDecoder()
                                    : Base64.getDecoder())
                            .decode(t);
            return printable(raw);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
    }

    private static Optional<String> printable(byte[] bytes) {
        if (bytes.length < 8) {
            return Optional.empty();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        long ok =
                text.chars()
                        .filter(
                                c ->
                                        c == '\n'
                                                || c == '\t'
                                                || (c >= 0x20 && c != 0x7F && c != 0xFFFD))
                        .count();
        return ok * 10 >= (long) text.length() * 9 ? Optional.of(text) : Optional.empty();
    }
}

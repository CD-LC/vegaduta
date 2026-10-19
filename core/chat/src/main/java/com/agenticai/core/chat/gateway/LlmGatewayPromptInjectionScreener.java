package com.agenticai.core.chat.gateway;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Lightweight heuristic prompt-injection/jailbreak screen for inbound user
 * content, run by {@link LlmGatewayContentPolicyAdvisor} before that content
 * is sent to the model (i.e. before it can influence a system-privileged
 * prompt already in the same request).
 *
 * <p>Deliberately pattern-based, not an LLM-judge call: an LLM-judge screen
 * would itself be an outbound LLM call needing to funnel through this exact
 * gateway, and would add a full model round-trip of latency to every single
 * gateway call - a heuristic pre-filter (this class) catching the common,
 * cheap-to-detect cases, with an LLM-judge escalation left as a real,
 * flagged follow-up (see class javadoc on {@link LlmGatewayContentPolicyAdvisor}),
 * is the same "cheap check first" shape {@code LlmGatewayRateLimiterService}/
 * {@code LlmGatewayBudgetService} already use ahead of the provider call.
 *
 * <p>Independent reimplementation of {@code core/guardrails}'
 * {@code JailbreakDetectorGuardrail} pattern set and its ReDoS remediation
 * (core/chat cannot depend on core/guardrails - see {@link
 * LlmGatewayPiiRedactor}'s javadoc for the exact cycle) rather than a smaller
 * ad hoc set, specifically so the same known-safe technique (bounded,
 * lazy gaps instead of unbounded {@code .*}, a hard scanned-character cap)
 * is not re-litigated: that class's javadoc documents a measured O(n^4)
 * blow-up from unbounded gaps that pinned a CPU core for 41 seconds on a
 * 2.4 KB input before the fix - this class starts from the fixed shape.
 *
 * <p><b>Multilingual phrase families (2026-09-28).</b> Screening is on by
 * default (FLAG mode) since that date, and this platform's users write in
 * Hindi, Hinglish and other languages as often as English, so an
 * English-only screen was trivially bypassed by translation. Four phrase
 * families are covered in Hindi (Devanagari), Hinglish (romanized Hindi),
 * Spanish, French, German, Portuguese, Chinese (simplified + traditional)
 * and Japanese: <i>ignore/forget previous instructions</i>, <i>you are now
 * [unrestricted/DAN]</i>, <i>system prompt</i> and <i>reveal your
 * instructions</i>. To keep false positives low every pattern is a PHRASE
 * family - a verb AND its object (and usually a "previous/your/system"
 * qualifier) inside a short bounded gap - never a single keyword: "ignore",
 * "instrucciones" or "系统提示" alone never matches, and "you are now" only
 * matches with a restriction-lifting complement ("... without rules",
 * "... DAN"), not "you are now my travel planner". A few negation guards
 * ("don't forget the instructions" in Hindi/Hinglish) cover the common
 * benign inversions.
 *
 * <p>Text is matched in three forms: as-is, whitespace-collapsed (defeats
 * payloads split across lines), and a <i>folded</i> form for the Latin
 * patterns - NFKC (full-width letters to ASCII) then diacritics stripped, so
 * "instrucciones/ignorez les précédentes/vorherigen Anweisungen" match with
 * or without accents. Devanagari is matched after NFKC with the nukta
 * (U+093C) dropped so "नज़रअंदाज़" and "नजरअंदाज" are the same word; CJK is
 * matched after NFKC. Latin gaps stay at {@link #MAX_GAP}; CJK gaps are much
 * shorter ({@link #CJK_MAX_GAP}) because a CJK character carries roughly a
 * word's worth of meaning.
 */
final class LlmGatewayPromptInjectionScreener {

    private LlmGatewayPromptInjectionScreener() {
    }

    /** See {@code JailbreakDetectorGuardrail#MAX_GAP}'s javadoc for why 40 characters is generous but bounded. */
    private static final int MAX_GAP = 40;

    /** Devanagari words are several code points each (consonant + vowel signs) - 20 is ~4 short words. */
    private static final int DEVANAGARI_MAX_GAP = 20;

    /** CJK: one character is roughly one word, so 12 characters is already a generous clause. */
    private static final int CJK_MAX_GAP = 12;

    /** See {@code JailbreakDetectorGuardrail#MAX_SCANNED_CHARS}'s javadoc - text beyond this is ignored, never rejected. */
    static final int MAX_SCANNED_CHARS = 16_000;

    private static final String GAP = "[^\\n]{0," + MAX_GAP + "}?";
    private static final String DGAP = "[^\\n]{0," + DEVANAGARI_MAX_GAP + "}?";
    private static final String CGAP = "[^\\n]{0," + CJK_MAX_GAP + "}?";

    /** Whitespace-run collapse so a payload split across lines cannot dodge the line-scoped gaps above. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u0085\\u2028\\u2029]+");

    /** Combining diacritical marks (U+0300-U+036F) only - Devanagari/CJK marks live in their own blocks and are untouched. */
    private static final Pattern LATIN_DIACRITICS = Pattern.compile("[\\u0300-\\u036F]+");

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** The "Do Anything Now" persona - case-sensitive so the given name "Dan" never matches. */
    private static final String DAN = "(?-i:\\bDAN\\b)";

    // English - matched against the raw, whitespace-collapsed and folded forms.
    private static final Pattern[] INJECTION_PATTERNS = {
        Pattern.compile("\\bignore" + GAP + "(?:previous|prior|all)" + GAP + "instruction", CI),
        Pattern.compile("\\bdisregard" + GAP + "(?:previous|prior)?" + GAP + "prompt", CI),
        Pattern.compile("\\bforget" + GAP + "\\byour" + GAP + "instruction", CI),
        Pattern.compile(
            "\\bshow" + GAP + "\\b(?:me|us)\\b" + GAP + "\\b(?:your|the)\\b" + GAP + "(?:system" + GAP + ")?prompt",
            CI),
        Pattern.compile("\\b(?:system|original)" + GAP + "prompt" + GAP + "\\b(?:was|is)\\b", CI),
        Pattern.compile(
            "\\bwhat" + GAP + "\\b(?:are|is)\\b" + GAP + "\\byour" + GAP + "(?:system" + GAP + ")?instruction",
            CI),
        // Authority spoofing. "the/your" required: "I'm a developer, how do I ..." is an
        // everyday sentence, "I am the developer of this bot" is the spoof.
        Pattern.compile(
            "\\bi(?:\\s+am|\\s*['\\u2019]m)\\s+(?:the|your)\\s+(?:admin|administrator|developer|owner|creator)\\b", CI),
        Pattern.compile("\\byou" + GAP + "\\bmust" + GAP + "(?:obey|listen" + GAP + "to)" + GAP + "\\bme\\b", CI),
        Pattern.compile(
            "\\brespond" + GAP + "\\bonly" + GAP + "\\b(?:in|as|like)\\b" + GAP + "(?:json|xml|html|markdown|code)",
            CI),
        // "you are now" family - only with a restriction-lifting complement. DAN is matched
        // case-sensitively everywhere ((?-i:...)) so "you are now talking to Dan" stays clean.
        Pattern.compile(
            "(?:\\byou\\s+are\\s+now|\\byou['\\u2019]re\\s+now"
                + "|\\bfrom\\s+now\\s+on,?\\s+you(?:\\s+are|['\\u2019]re|\\s+will)?)"
                + GAP
                + "(?:" + DAN + "|jailbroken|unrestricted|unfiltered|uncensored"
                + "|free\\s+(?:from|of)\\s+(?:all\\s+|any\\s+)?(?:rules|restrictions|guidelines|limits|filters)"
                + "|without\\s+(?:any\\s+)?(?:rules|restrictions|guidelines|filters))",
            CI),
        Pattern.compile(
            "\\byou(?:\\s+are|['\\u2019]re)\\s+no\\s+longer\\s+"
                + "(?:bound\\s+by|restricted|an?\\s+(?:ai|assistant|language\\s+model)\\b|chatgpt|claude)",
            CI),
        // "reveal your instructions" family.
        Pattern.compile(
            "\\b(?:reveal|print|repeat|output|display|leak|dump|tell\\s+me)\\b" + GAP
                + "(?:\\byour\\s+(?:system\\s+|initial\\s+|original\\s+|hidden\\s+|secret\\s+|internal\\s+)?"
                + "(?:prompt|instructions)|\\bthe\\s+system\\s+(?:prompt|message))",
            CI),
        Pattern.compile("\\[\\[JAILBREAK\\]\\]", CI),
        Pattern.compile("<<<[^\\n]{0,200}?>>>", CI),
        Pattern.compile("^(DAN|UCAR|ALPACA|GPT-4|REAL-ME):", CI | Pattern.MULTILINE)
    };

    // Latin-script non-English (Hinglish, es, fr, de, pt) - matched against the FOLDED form
    // (NFKC, diacritics stripped), so every literal below is written without accents.
    /** "ignore mat karo" / "bhoolna mat" - the verb (plus a short inflection) followed by a negation word. */
    private static final String HINGLISH_NEG = "(?![a-z]{0,6}\\s+(?:mat|na|nahi|nahin)\\b)";
    private static final String ES_NOUN = "(?:instrucciones|indicaciones|ordenes|reglas|directrices)";
    private static final String FR_NOUN = "(?:instructions|consignes|directives|regles)";
    private static final String DE_NOUN = "(?:anweisungen|instruktionen|befehle|regeln|vorgaben)";
    private static final String PT_NOUN = "(?:instrucoes|instrucao|orientacoes|ordens|regras|diretrizes)";

    private static final Pattern[] FOLDED_LATIN_PATTERNS = {
        // --- Hinglish ---
        Pattern.compile(
            "\\b(?:pichh?le|pehle|pahle|purane|saare|sare|sabhi|upar\\s+(?:ke|wale|diye))\\b" + GAP
                + "\\b(?:instructions?|nirdesh|niyam|rules?|commands?)\\b" + GAP
                + "(?<!\\bmat\\s)(?<!\\bna\\s)\\b(?:ignore|bhool|bhul|nazar\\s*andaz|najar\\s*andaz)" + HINGLISH_NEG,
            CI),
        Pattern.compile(
            "(?:\\bab\\s+(?:se\\s+)?(?:tum|aap|tu)\\b|\\b(?:tum|aap|tu)\\s+ab\\b(?:\\s+se)?)" + GAP
                + "(?:bina\\s+(?:kisi\\s+)?(?:rules?|niyam|restrictions?|pabandi|filters?|limits?)"
                + "|koi\\s+(?:rules?|niyam|restrictions?|pabandi|filters?|limits?)\\s+nahi"
                + "|" + DAN + "|unrestricted|unfiltered|jailbroken)",
            CI),
        Pattern.compile(
            "\\b(?:system\\s+prompt|(?:original|hidden|secret|asli)\\s+instructions?|apn[aei]\\s+(?:instructions?|prompt))"
                + GAP
                + "\\b(?:batao|bata\\s+do|bataiye|dikhao|dikha\\s+do|dikhaiye|likho|likh\\s+do|share\\s+karo"
                + "|reveal\\s+karo)\\b",
            CI),

        // --- Spanish ---
        Pattern.compile(
            "\\b(?:ignora|ignore|ignorar|ignorad|olvida|olvide|olvidar|omite|descarta|haz\\s+caso\\s+omiso\\s+de)\\b" + GAP
                + "(?:\\b(?:anteriores|previas|precedentes)\\b" + GAP + ES_NOUN
                + "|" + ES_NOUN + GAP + "\\b(?:anteriores|previas|precedentes|de\\s+antes)\\b"
                + "|\\btus\\s+" + ES_NOUN + ")",
            CI),
        Pattern.compile(
            "(?:\\bahora\\s+eres|\\bya\\s+no\\s+eres|\\ba\\s+partir\\s+de\\s+ahora,?\\s+(?:eres|seras|actuaras))" + GAP
                + "(?:" + DAN + "|sin\\s+(?:ninguna\\s+|ningun\\s+)?(?:restricciones|restriccion|reglas|limites|filtros|censura)"
                + "|libre\\s+de\\s+(?:restricciones|reglas))",
            CI),
        Pattern.compile(
            "\\b(?:muestra|muestrame|revela|revelame|dime|ensename|escribe|repite|comparte|imprime)\\b" + GAP
                + "(?:\\btus\\s+instrucciones\\b"
                + "|instrucciones\\s+(?:del\\s+sistema|originales|iniciales|ocultas|secretas)"
                + "|prompt\\s+(?:del?\\s+sistema|inicial|original)|mensaje\\s+del?\\s+sistema)",
            CI),

        // --- French ---
        Pattern.compile(
            "\\b(?:ignore|ignores|ignorez|ignorer|oublie|oublies|oubliez|oublier|ne\\s+tiens?\\s+pas\\s+compte|fais\\s+abstraction)\\b"
                + GAP
                + "(?:\\b(?:precedentes|anterieures)\\b" + GAP + FR_NOUN
                + "|" + FR_NOUN + GAP + "\\b(?:precedentes|anterieures|d['\\u2019]avant|ci-dessus)"
                + "|\\b(?:tes|vos)\\s+" + FR_NOUN + ")",
            CI),
        Pattern.compile(
            "(?:\\btu\\s+es\\s+maintenant|\\bvous\\s+etes\\s+maintenant|\\bdesormais,?\\s+(?:tu\\s+es|vous\\s+etes)"
                + "|\\ba\\s+partir\\s+de\\s+maintenant,?\\s+(?:tu\\s+es|vous\\s+etes))" + GAP
                + "(?:" + DAN + "|sans\\s+(?:aucune\\s+)?(?:restrictions?|regles|limites|filtres|censure)|libere\\s+de|debride)",
            CI),
        Pattern.compile(
            "\\b(?:montre|montrez|affiche|affichez|revele|revelez|donne|donnez|dis|dites|repete|repetez|ecris|ecrivez"
                + "|partage|partagez)\\b" + GAP
                + "(?:\\b(?:tes|vos)\\s+(?:instructions|consignes)\\b"
                + "|(?:prompt|invite|message)\\s+systeme"
                + "|(?:instructions|consignes)\\s+(?:systeme|initiales|d['\\u2019]origine|originales|cachees|secretes))",
            CI),

        // --- German (umlauts folded: u/o/a; "ue/oe/ae" spellings allowed where it matters) ---
        Pattern.compile(
            "\\b(?:ignorier|ignoriere|ignorieren|ignoriert|vergiss|vergesst|vergessen\\s+sie|missachte|missachten)\\b" + GAP
                + "(?:\\b(?:vorherigen|vorigen|bisherigen|fru(?:e)?heren|obigen|alle)\\b" + GAP + DE_NOUN
                + "|\\b(?:deine|ihre)\\b" + GAP + DE_NOUN + ")",
            CI),
        Pattern.compile(
            "\\b(?:vorherigen|vorigen|bisherigen|fru(?:e)?heren|obigen)\\b" + GAP + DE_NOUN + GAP
                + "\\b(?:ignorieren|vergessen|missachten)\\b",
            CI),
        Pattern.compile(
            "(?:\\bdu\\s+bist\\s+(?:jetzt|nun|ab\\s+sofort|ab\\s+jetzt)|\\bab\\s+(?:jetzt|sofort)\\s+bist\\s+du"
                + "|\\bsie\\s+sind\\s+(?:jetzt|nun|ab\\s+sofort))" + GAP
                + "(?:" + DAN + "|ohne\\s+(?:jegliche\\s+|alle\\s+)?(?:einschr(?:a|ae)nkungen|regeln|grenzen|filter|zensur)"
                + "|uneingeschr(?:a|ae)nkt|ungefiltert|frei\\s+von)",
            CI),
        Pattern.compile(
            "\\b(?:zeig|zeige|zeigen|nenn|nenne|verrat|verrate|gib|wiederhole|schreib|schreibe|teile)\\b" + GAP
                + "(?:system-?\\s?prompt|systemanweisung|systemnachricht"
                + "|\\b(?:deine|ihre)\\s+(?:anweisungen|instruktionen)\\b"
                + "|(?:ursprunglichen|urspruenglichen|geheimen|versteckten|internen)\\s+(?:anweisungen|instruktionen))",
            CI),

        // --- Portuguese ---
        Pattern.compile(
            "\\b(?:ignore|ignora|ignorar|esqueca|esquece|esquecer|desconsidere|desconsidera|descarte)\\b" + GAP
                + "(?:\\b(?:anteriores|previas)\\b" + GAP + PT_NOUN
                + "|" + PT_NOUN + GAP + "\\b(?:anteriores|previas|de\\s+antes|acima)\\b"
                + "|\\b(?:suas|tuas)\\s+" + PT_NOUN + ")",
            CI),
        Pattern.compile(
            "(?:\\bagora\\s+(?:voce\\s+e|tu\\s+es)\\b|\\bvoce\\s+agora\\s+e\\b|\\bvoce\\s+nao\\s+e\\s+mais\\b"
                + "|\\ba\\s+partir\\s+de\\s+agora,?\\s+voce\\s+(?:e|sera|vai\\s+ser)\\b)" + GAP
                + "(?:" + DAN + "|sem\\s+(?:nenhuma\\s+)?(?:restricoes|restricao|regras|limites|filtros|censura)|livre\\s+de)",
            CI),
        Pattern.compile(
            "\\b(?:mostre|mostra|revele|revela|diga|diz|repita|escreva|compartilhe|imprima)\\b" + GAP
                + "(?:prompt\\s+(?:do|de)\\s+sistema|\\b(?:seu|teu)\\s+prompt\\s+(?:inicial|original)"
                + "|\\b(?:suas|tuas)\\s+instrucoes\\b"
                + "|instrucoes\\s+(?:do\\s+sistema|originais|iniciais|ocultas|secretas))",
            CI),
    };

    // Native scripts (Devanagari, CJK) - matched against NFKC text with the Devanagari nukta dropped.
    /** "मत भूलो" / "न भूलें" - a negation word right before the verb. */
    private static final String HI_NEG_BEFORE = "(?<!मत )(?<!न )(?<!ना )(?<!नहीं )";
    /** "भूलना मत" / "अनदेखा न करें" - the verb (plus a short inflection) followed by a standalone negation word. */
    private static final String HI_NEG_AFTER = "(?![\\u0900-\\u097F]{0,6}\\s+(?:मत|नहीं|न|ना)(?![\\u0900-\\u097F]))";

    private static final Pattern[] NATIVE_SCRIPT_PATTERNS = {
        // --- Hindi (Devanagari; nukta already removed, so नज़रअंदाज़ -> नजरअंदाज) ---
        Pattern.compile(
            "(?:पिछल|पहले|ऊपर|सभी|सारे|पुरान|अब\\s*तक\\s*के)" + DGAP
                + "(?:निर्देश|इंस्ट्रक्शन|आदेश|हिदायत|नियम)" + DGAP
                + HI_NEG_BEFORE + "(?:अनदेखा|नजरअंदाज|नजर\\s*अंदाज|इग्नोर|अवहेलना|भूल)" + HI_NEG_AFTER),
        Pattern.compile(
            "(?:अब(?:\\s*से)?" + DGAP + "(?:तुम|आप|तू)|(?:तुम|आप|तू)\\s*अब)" + DGAP
                + "(?:बिना\\s*(?:किसी\\s*)?(?:नियम|प्रतिबंध|पाबंदी|रोक|सीमा|फिल्टर|रोक-टोक)"
                + "|(?:कोई|किसी)\\s*(?:भी\\s*)?(?:नियम|प्रतिबंध|पाबंदी|रोक|सीमा|फिल्टर)\\s*(?:नहीं|के\\s*बिना)"
                + "|DAN|अनफिल्टर्ड|प्रतिबंध\\s*मुक्त|नियम\\s*मुक्त)",
            CI),
        Pattern.compile(
            "(?:सिस्टम\\s*(?:प्र[ॉो]म्प्ट|निर्देश|मैसेज|संदेश)|(?:मूल|गुप्त|छिपे\\s*हुए|छुपे\\s*हुए|आंतरिक|असली)\\s*निर्देश"
                + "|अपन[ाेी]\\s*(?:सिस्टम\\s*)?(?:इंस्ट्रक्शन|प्र[ॉो]म्प्ट))" + DGAP
                + "(?:दिखा|बता|लिख|प्रकट|शेयर|रिवील|साझा|दोहरा)"),

        // --- Chinese (simplified + traditional) ---
        Pattern.compile(
            // (?<!...): "不要忽略所有规则" / "别忘记之前的指令" is the benign inversion.
            "(?<!不要)(?<!别)(?<!別)(?<!不)"
                + "(?:忽略|无视|無視|忽视|忽視|忘记|忘記|忘掉|不要理会|不要理會|抛弃|拋棄|放弃|放棄)" + CGAP
                + "(?:之前|以前|先前|上面|上述|前面|所有|全部|你的)" + CGAP
                + "(?:指令|指示|规则|規則|设定|設定|提示词|提示詞|命令)"),
        Pattern.compile(
            // "从现在开始" must be followed by 你 - "从现在开始没有限制地访问" is product copy, not a persona swap.
            "(?:你现在是|你現在是|现在你是|現在你是|你不再是|从现在(?:开始|起)[,，]?\\s*你|從現在(?:開始|起)[,，]?\\s*你)" + CGAP
                + "(?:DAN|不受(?:任何)?(?:限制|约束|約束|审查|審查)|(?:没有|沒有)(?:任何)?(?:限制|规则|規則|约束|約束|过滤|過濾)"
                + "|无限制|無限制|越狱|越獄)",
            CI),
        Pattern.compile(
            "(?:显示|顯示|告诉我|告訴我|输出|輸出|透露|泄露|洩露|重复|重複|打印|展示|给我看|給我看|说出|說出)" + CGAP
                + "(?:系统提示|系統提示|系统指令|系統指令|系统消息|系統訊息|初始指令|原始指令|隐藏指令|隱藏指令|你的指令|你的提示词|你的提示詞)"),
        Pattern.compile("(?:系统提示|系統提示)(?:词|詞)?" + CGAP + "(?:是什么|是什麼)"),

        // --- Japanese ---
        Pattern.compile(
            "(?:以前|直前|上記|これまで|今まで|先ほど|すべて|全て)の" + CGAP
                + "(?:指示|命令|指令|ルール|設定|プロンプト|インストラクション)" + CGAP
                // 無視しないで / 忘れないで ("don't ignore/forget") are the benign inversions.
                + "(?:無視(?!しないで|しない|せず)|忘れ(?!ないで|ない|ず)|従わな)"),
        Pattern.compile(
            "(?:あなたは今|今からあなたは|これからあなたは|あなたはもう|今後あなたは|今のあなたは)" + CGAP
                + "(?:DAN|制限(?:の|が)?(?:ない|なし|を受けない)|制約(?:の|が)?(?:ない|なし)|ルール(?:の|が)?(?:ない|なし)"
                + "|無制限|検閲(?:の|が)?(?:ない|なし)|脱獄)",
            CI),
        Pattern.compile(
            "(?:システムプロンプト|システム・プロンプト|システムメッセージ|初期指示|元の指示|隠された指示|あなたへの指示|あなたの指示)"
                + CGAP
                + "(?:教えて|見せて|表示|出力|開示|公開|繰り返|書いて|明かして)"),
    };

    record Verdict(boolean suspect, String reason) {
        static final Verdict CLEAN = new Verdict(false, null);
    }

    static Verdict screen(String content) {
        if (content == null || content.isBlank()) {
            return Verdict.CLEAN;
        }
        String scanned = content.length() > MAX_SCANNED_CHARS ? content.substring(0, MAX_SCANNED_CHARS) : content;
        String normalized = WHITESPACE_RUN.matcher(scanned).replaceAll(" ");
        String nfkc = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        String folded = LATIN_DIACRITICS.matcher(Normalizer.normalize(nfkc, Normalizer.Form.NFD)).replaceAll("");
        // NFKC leaves the Devanagari nukta decomposed (U+0958-U+095F are composition
        // exclusions), so dropping U+093C unifies both spellings of nukta letters.
        String nativeScript = nfkc.replace("़", "");

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(normalized).find() || pattern.matcher(scanned).find() || pattern.matcher(folded).find()) {
                return new Verdict(true, "prompt injection / jailbreak pattern match");
            }
        }
        for (Pattern pattern : FOLDED_LATIN_PATTERNS) {
            if (pattern.matcher(folded).find()) {
                return new Verdict(true, "prompt injection / jailbreak pattern match (multilingual)");
            }
        }
        for (Pattern pattern : NATIVE_SCRIPT_PATTERNS) {
            if (pattern.matcher(nativeScript).find()) {
                return new Verdict(true, "prompt injection / jailbreak pattern match (multilingual)");
            }
        }
        return Verdict.CLEAN;
    }
}

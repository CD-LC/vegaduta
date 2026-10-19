package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.skills.SkillScanner.Severity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rule tables behind {@link SkillScanner}: compiled patterns, host lists, the Cyrillic/Greek
 * look-alike map. Kept apart from the scan logic so a rule change is a one-file diff and the
 * scanner stays small. Package-private; nothing here has behaviour of its own.
 *
 * <p>Every pattern is bounded (no unbounded nested quantifiers) so a hostile skill cannot stall the
 * scan, and runs against text that {@link SkillScanner#normalize} has already folded (invisible
 * characters dropped, look-alikes mapped, lower-cased).
 */
final class SkillScanRules {

    private SkillScanRules() {}

    // ------------------------------------------------------------------ rule table

    static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * @param bumpInMetadata a match in the name/description is always HIGH (it reaches every
     *     prompt)
     * @param defensive wording like "never ..." in the same clause/sentence downgrades the match
     * @param skipInCode not applied to bundled source files (a script legitimately calls
     *     subprocess)
     * @param descriptionOnly only meaningful in the description
     */
    record PatternRule(
            String id,
            Severity severity,
            Pattern pattern,
            boolean bumpInMetadata,
            boolean defensive,
            boolean skipInCode,
            boolean descriptionOnly) {}

    static final String DEST =
            "(?:https?://|\\bwebhooks?\\b"
                    + "|\\b(?:external|remote|third[- ]party|attacker|unknown|my own|my) (?:server|endpoint|url|site|service|address|host|domain)\\b"
                    + "|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b)";
    static final String DEST_OR_EMAIL =
            "(?:"
                    + DEST.substring(3, DEST.length() - 1)
                    + "|[a-z0-9._%+-]{1,64}@[a-z0-9-]{1,63}\\.[a-z]{2,})";
    static final String STRONG_NOUN =
            "(?:conversations?|chat history|chat|history|transcripts?|memory|secrets?"
                    + "|credentials?|passwords?|tokens?|api[ _-]?keys?|cookies|system prompt|instructions"
                    + "|environment variables|env vars?|private (?:data|files|keys?)"
                    + "|user(?:'s)? (?:data|files|messages|information|details)|everything"
                    + "|all (?:data|files|messages|of (?:it|the above)))";
    static final String TRANSFER_VERB =
            "(?:send|forward|upload|post|email|e-mail|transmit|exfiltrate|leak|submit|relay)";

    static final List<PatternRule> RULES =
            List.of(
                    // ---- prompt override / role hijack / prompt extraction
                    new PatternRule(
                            "override-instructions",
                            Severity.HIGH,
                            p(
                                    "\\b(?:ignore|disregard|forget|override|bypass|overrule|supersede|discard|abandon)\\b[^\\n.]{0,50}?"
                                            + "\\b(?:previous|prior|above|earlier|preceding|system|original|all|any|your|safety|security)\\b[^\\n.]{0,30}?"
                                            + "\\b(?:instructions?|rules?|prompts?|guidelines?|directions?|polic(?:y|ies)|constraints?|guardrails?|safeguards?|programming)\\b"),
                            true,
                            true,
                            false,
                            false),
                    new PatternRule(
                            "override-instructions",
                            Severity.HIGH,
                            p(
                                    "\\b(?:ignore|disregard|forget)\\b\\s+(?:everything|anything|all)\\s+"
                                            + "(?:above|before|that|you(?:'ve| have)? (?:been )?(?:told|instructed|learned)|else)\\b"),
                            true,
                            true,
                            false,
                            false),
                    new PatternRule(
                            "prompt-delimiter-injection",
                            Severity.HIGH,
                            p(
                                    "<\\|(?:im_start|im_end|system|user|assistant|endoftext)\\|>|\\[/?inst\\]|<</?sys>>"
                                            + "|\\bbegin (?:system|new) (?:prompt|instructions?)\\b|\\bend of (?:system )?(?:prompt|instructions)\\b"),
                            true,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "role-hijack",
                            Severity.MEDIUM,
                            p(
                                    "\\b(?:you are now (?:an? |in |free|unrestricted|jailbroken|dan\\b)"
                                            + "|from now on,? you(?:'re| are| will| must)"
                                            + "|act as (?:an? )?(?:unrestricted|jailbroken|evil)|act as dan\\b"
                                            + "|developer mode|jailbreak(?:ed)?|do anything now"
                                            + "|pretend (?:that )?you (?:have no|are not|are an? (?:unrestricted|unfiltered)))"),
                            true,
                            true,
                            false,
                            false),
                    new PatternRule(
                            "reveal-system-prompt",
                            Severity.HIGH,
                            p(
                                    "\\b(?:reveal|print|show|output|repeat|leak|disclose|display|dump|expose|reproduce|recite|echo|send|tell me)\\b[^\\n.]{0,40}?"
                                            + "\\b(?:system (?:prompt|message|instructions?)|hidden (?:prompt|instructions?)|initial (?:prompt|instructions?)"
                                            + "|developer (?:message|prompt|instructions?)|your (?:instructions|prompt|rules|configuration|guidelines))\\b"),
                            true,
                            true,
                            false,
                            false),
                    // ---- covert action / exfiltration
                    new PatternRule(
                            "covert-action",
                            Severity.HIGH,
                            p(
                                    "\\b(?:without (?:telling|informing|notifying|alerting|asking) (?:the )?(?:user|human|owner|admin(?:istrator)?)"
                                            + "|(?:do not|don't|never) (?:tell|inform|notify|alert) (?:the )?(?:user|human|owner|admin(?:istrator)?)\\s+"
                                            + "(?:about|of|that you (?:sent|uploaded|forwarded|copied|read|accessed|ran|did)|what you|anything|any of|when|how)"
                                            + "|secretly|covertly|stealthily|surreptitiously"
                                            + "|hide (?:this|it|the (?:action|request|call)) from (?:the )?(?:user|human|owner|admin)"
                                            + "|keep (?:this|it) (?:a )?secret from (?:the )?user)\\b"),
                            true,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "exfiltrate-data",
                            Severity.HIGH,
                            p(
                                    "\\b(?:send|forward|upload|post|email|e-mail|transmit|exfiltrate|leak|submit|relay|pipe|copy|share)\\b[^\\n]{0,80}?\\b"
                                            + STRONG_NOUN
                                            + "\\b[^\\n]{0,80}?"
                                            + DEST_OR_EMAIL),
                            true,
                            true,
                            false,
                            false),
                    new PatternRule(
                            "exfiltrate-data",
                            Severity.HIGH,
                            p(
                                    "\\b(?:send|post|upload|forward|submit|transmit|relay|email)\\b[^\\n]{0,60}?"
                                            + DEST
                                            + "[^\\n]{0,80}?\\b"
                                            + STRONG_NOUN
                                            + "\\b"),
                            true,
                            true,
                            false,
                            false),
                    new PatternRule(
                            "outbound-transfer",
                            Severity.MEDIUM,
                            p("\\b" + TRANSFER_VERB + "\\b[^\\n]{0,100}?" + DEST),
                            true,
                            true,
                            false,
                            false),
                    // ---- code execution
                    new PatternRule(
                            "remote-script-pipe",
                            Severity.HIGH,
                            p(
                                    "\\b(?:curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^\\n|]{0,200}\\|\\s*(?:sudo\\s+)?"
                                            + "(?:(?:ba|z|da|k|c|fi)?sh|python[0-9.]*|node|perl|ruby|iex|invoke-expression|powershell|pwsh)\\b"),
                            false,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "remote-script-pipe",
                            Severity.HIGH,
                            p(
                                    "\\bbase64\\s+(?:-d|--decode)\\b[^\\n|]{0,50}\\|\\s*(?:(?:ba|z|da|k)?sh|python[0-9.]*)\\b"
                                            + "|\\b(?:iex|invoke-expression)\\b[^\\n]{0,60}(?:downloadstring|net\\.webclient)"),
                            false,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "destructive-command",
                            Severity.HIGH,
                            p(
                                    "\\brm\\s+-[a-z]*r[a-z]*\\s+(?:--no-preserve-root\\s+)?(?:/\\*?|~/?\\*?|\\$home/?\\*?|\\*)(?=\\s|$)"
                                            + "|\\bmkfs\\.[a-z0-9]+|\\bdd\\s+if=[^\\n]{0,60}\\bof=/dev/(?:sd|nvme|disk|hd)"
                                            + "|:\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:|\\bformat\\s+c:"),
                            false,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "reverse-shell",
                            Severity.HIGH,
                            p(
                                    "/dev/tcp/|\\bnc(?:at)?\\b[^\\n]{0,30}\\s-[a-z]*e\\b|\\bbash\\s+-i\\s+>&|\\bsocat\\b[^\\n]{0,60}exec:"),
                            false,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "encoded-powershell",
                            Severity.HIGH,
                            p(
                                    "\\bpowershell(?:\\.exe)?\\b[^\\n]{0,60}\\s-(?:e|enc|encodedcommand)\\b"),
                            false,
                            false,
                            false,
                            false),
                    new PatternRule(
                            "shell-exec",
                            Severity.MEDIUM,
                            p(
                                    "\\b(?:eval|exec)\\s*\\(|\\bos\\.system\\s*\\(|\\bos\\.popen\\b|\\bsubprocess\\.(?:run|call|popen|check_output|check_call)\\b"
                                            + "|\\bchild_process\\b|runtime\\.getruntime\\(\\)\\.exec|\\bsudo\\s+\\S|\\bchmod\\s+(?:\\+x|[0-7]{3,4})\\b"
                                            + "|\\bpickle\\.loads?\\b|\\b__import__\\s*\\("),
                            true,
                            false,
                            true,
                            false),
                    // ---- credentials / sensitive material
                    new PatternRule(
                            "credential-request",
                            Severity.MEDIUM,
                            p(
                                    "\\b(?:ask(?:s|ing)?(?: the user)? for|request(?:s|ing)?|prompt(?:s|ing)? (?:the user )?for|collect(?:s|ing)?|obtain|retrieve"
                                            + "|extract|read|find|grab|include|provide|enter|paste|supply|send|share|reveal|print|output|cat|type)\\b[^\\n.]{0,50}?"
                                            + "\\b(?:passwords?|passphrases?|api[ _-]?keys?|secret[ _-]?keys?|access[ _-]?tokens?"
                                            + "|(?:bearer|auth(?:entication)?|session|refresh) tokens?|private keys?|ssh keys?|credentials?|cookies"
                                            + "|seed phrases?|recovery phrases?|one[- ]time (?:codes?|passwords?)|2fa codes?|otp)\\b"),
                            true,
                            true,
                            true,
                            false),
                    new PatternRule(
                            "sensitive-path",
                            Severity.MEDIUM,
                            p(
                                    "(?:\\.ssh/|\\.aws/(?:credentials|config)|\\.gnupg\\b|\\.kube/config|id_rsa\\b|id_ed25519\\b|/etc/passwd\\b|/etc/shadow\\b"
                                            + "|\\.npmrc\\b|\\.git-credentials\\b|\\.netrc\\b|\\bprintenv\\b|\\bwallet\\.dat\\b"
                                            + "|browser (?:cookies|passwords|credentials|login data)|(?<![\\w.])\\.env\\b)"),
                            true,
                            false,
                            true,
                            false),
                    // ---- guardrail / approval / logging evasion
                    new PatternRule(
                            "disable-safeguards",
                            Severity.HIGH,
                            p(
                                    "\\b(?:disable|turn off|switch off|bypass|circumvent|suppress|deactivate|evade)\\b[^\\n.]{0,40}?"
                                            + "\\b(?:approvals?|human[- ](?:in[- ]the[- ]loop|review|approval|oversight)|confirmations?|guardrails?"
                                            + "|safety (?:checks?|filters?|rules?|settings?)|content (?:filters?|moderation)|moderation"
                                            + "|audit (?:logs?|logging|trail)|logging|monitoring|oversight|permission checks?|policy checks?"
                                            + "|security (?:checks?|controls?|policy|policies)|sandbox(?:ing)?|rate limits?)\\b"),
                            true,
                            true,
                            false,
                            false),
                    new PatternRule(
                            "disable-safeguards",
                            Severity.HIGH,
                            p(
                                    "\\b(?:skip|remove|stop|ignore)\\b[^\\n.]{0,40}?"
                                            + "\\b(?:approvals?|human[- ](?:review|approval|oversight)|confirmations?|guardrails?|safety checks?|content filters?"
                                            + "|audit (?:logs?|logging|trail)|oversight)\\b"
                                            + "|\\b(?:auto[- ]?approve|always approve|approve (?:everything|all|automatically)"
                                            + "|never ask (?:for |the user for )?(?:approval|confirmation|permission)"
                                            + "|without (?:asking for )?(?:approval|confirmation|permission|logging)"
                                            + "|(?:do not|don't) (?:log|record|audit) (?:this|that|these|any|your|the) "
                                            + "(?:action|actions|call|calls|request|requests|activity|tool use|steps))\\b"),
                            true,
                            true,
                            false,
                            false),
                    // ---- description-only: trigger hijacking and tool steering
                    new PatternRule(
                            "description-directive",
                            Severity.LOW,
                            p(
                                    "\\b(?:ignore|instead of|regardless|at all times|highest priority|top priority"
                                            + "|before (?:answering|responding|replying)|(?:for|on|in) every (?:message|request|response|turn|task)"
                                            + "|every (?:message|request|response|turn)|call this (?:skill )?(?:first|always|before)|mandatory)\\b"),
                            false,
                            false,
                            false,
                            true),
                    new PatternRule(
                            "description-tool-directive",
                            Severity.MEDIUM,
                            p(
                                    "\\b(?:send_email|run_skill_script|read_skill_file|load_skill|propose_skill|improve_skill"
                                            + "|run (?:the )?(?:following|this) command|(?:call|use|invoke) (?:the )?[a-z_]{3,40} tool)\\b"),
                            false,
                            false,
                            false,
                            true));

    /**
     * Rule ids whose match on decoded base64/hex content means the encoding was carrying an
     * instruction.
     */
    static final Set<String> INSTRUCTION_RULE_IDS =
            Set.of(
                    "override-instructions",
                    "reveal-system-prompt",
                    "exfiltrate-data",
                    "covert-action",
                    "remote-script-pipe",
                    "disable-safeguards",
                    "role-hijack",
                    "prompt-delimiter-injection");

    /** Rule ids that mean the body can DO something outward-facing or destructive. */
    static final List<String> ACTION_RULE_IDS =
            List.of(
                    "exfiltrate-data",
                    "outbound-transfer",
                    "remote-script-pipe",
                    "destructive-command",
                    "reverse-shell",
                    "encoded-powershell",
                    "shell-exec",
                    "covert-action");

    static final Pattern TIGHT_NEGATION =
            p(
                    "(?:\\bdo not|\\bdon't|\\bnever|\\bmust not|\\bshould not|\\bcannot|\\bcan't|\\bwill not|\\bwon't|\\bnot)"
                            + "\\s+(?:ever\\s+|also\\s+|simply\\s+|just\\s+)?$");
    static final Pattern DEFENSIVE_CUE =
            p(
                    "\\b(?:do not|don't|never|must not|mustn't|should not|shouldn't|cannot|can't|won't|will not|refuse|decline|reject|avoid"
                            + "|not follow|untrusted|malicious|suspicious|attack|injection|point it out|red flag|treat (?:it|them|this) as)\\b");

    static final Pattern READONLY_CLAIM =
            p(
                    "\\b(?:read[- ]only|only (?:reads?|views?|inspects?|reviews?|summari[sz]es?|analy[sz]es?)"
                            + "|never (?:writes?|sends?|uploads?|modif(?:y|ies)|changes?|deletes?|posts?)"
                            + "|(?:does not|doesn't|will not|won't) (?:send|write|modify|upload|transmit|delete|change|post|access (?:the )?(?:network|internet))"
                            + "|without (?:sending|writing|modifying|uploading|changing|deleting)"
                            + "|no network|no external (?:calls|requests|access)|local[- ]only|no side[- ]effects?|harmless"
                            + "|purely (?:informational|advisory))\\b");
    static final Pattern NO_NETWORK_CLAIM =
            p(
                    "\\b(?:no network|no external (?:calls|requests|access)|local[- ]only"
                            + "|(?:does not|doesn't|will not|won't) access (?:the )?(?:network|internet))\\b");
    static final Pattern WRITE_ACTION =
            p(
                    "\\b(?:delete|remove|overwrite|truncate|drop|modify|rewrite|write (?:to|over)|install|deploy|push|commit|publish)\\s+"
                            + "(?:the |all |any |every |each |your |these |those )?"
                            + "(?:files?|records?|rows?|tables?|databases?|repos?|repositor(?:y|ies)|branch(?:es)?|data|emails?|messages?"
                            + "|documents?|servers?|packages?|production)\\b");

    static final Pattern URL =
            Pattern.compile("https?://[^\\s<>\"'`)\\]}]+", Pattern.CASE_INSENSITIVE);
    static final Pattern IPV4 =
            Pattern.compile(
                    "(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\w]|\\.\\d)");
    static final Pattern MD_IMAGE =
            Pattern.compile("!\\[[^\\]\\n]{0,200}\\]\\(\\s*(https?://[^)\\s]{1,500})");
    static final Pattern BASE64_RUN =
            Pattern.compile("(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/=_-])");
    static final Pattern HEX_RUN =
            Pattern.compile("(?<![0-9a-fA-F])(?:[0-9a-fA-F]{2}){64,}(?![0-9a-fA-F])");
    static final Pattern PURE_HEX = Pattern.compile("^[0-9a-fA-F]+$");
    static final Pattern ESCAPED_BYTES = Pattern.compile("(?:\\\\x[0-9a-fA-F]{2}){12,}");
    static final Pattern ESCAPED_UNICODE = Pattern.compile("(?:\\\\u[0-9a-fA-F]{4}){8,}");
    static final Pattern PERCENT_RUN = Pattern.compile("(?:%[0-9a-fA-F]{2}){12,}");

    static final List<String> SUSPICIOUS_HOSTS =
            List.of(
                    "hooks.slack.com",
                    "webhook.site",
                    "requestbin.com",
                    "requestbin.net",
                    "pipedream.net",
                    "pipedream.com",
                    "ngrok.io",
                    "ngrok-free.app",
                    "ngrok.app",
                    "ngrok.dev",
                    "trycloudflare.com",
                    "burpcollaborator.net",
                    "oast.fun",
                    "oast.pro",
                    "oast.live",
                    "oast.site",
                    "oast.online",
                    "oast.me",
                    "interact.sh",
                    "canarytokens.com",
                    "pastebin.com",
                    "paste.ee",
                    "transfer.sh",
                    "file.io",
                    "0x0.st",
                    "requestcatcher.com",
                    "beeceptor.com",
                    "hookbin.com",
                    "webhook.cool",
                    "mockbin.io",
                    "loca.lt",
                    "serveo.net",
                    "localhost.run");
    static final List<String> SUSPICIOUS_PREFIXES =
            List.of(
                    "discord.com/api/webhooks",
                    "discordapp.com/api/webhooks",
                    "api.telegram.org/bot");
    static final List<String> SHORTENERS =
            List.of(
                    "bit.ly",
                    "tinyurl.com",
                    "t.co",
                    "goo.gl",
                    "is.gd",
                    "cutt.ly",
                    "rb.gy",
                    "ow.ly",
                    "buff.ly",
                    "shorturl.at");

    static final Pattern EXECUTABLE_NAME =
            p(
                    "\\.(?:exe|dll|so|dylib|bat|cmd|ps1|vbs|jar|scr|msi|apk|com|bin|class|pyc|wasm|sh|bash|zsh)$");
    static final Pattern SCRIPT_NAME = p("\\.(?:py|js|mjs|ts|rb|pl|php|java|kt|go|rs)$");
    static final Pattern DISGUISED_NAME =
            p(
                    "\\.(?:pdf|docx?|xlsx?|pptx?|txt|png|jpe?g|gif|md|csv)\\.(?:exe|bat|cmd|ps1|sh|js|vbs|scr|jar|com)$");
    static final Pattern SENSITIVE_NAME =
            p(
                    "(?:^|/)(?:\\.env(?:\\..*)?|id_rsa|id_ed25519|\\.npmrc|\\.netrc|\\.git-credentials|credentials(?:\\.json)?"
                            + "|secrets?(?:\\.(?:json|ya?ml|txt))?|\\.aws|\\.ssh)(?:/|$)");
    static final Pattern CODE_EXTENSION =
            p("\\.(?:py|js|mjs|ts|rb|pl|php|java|kt|go|rs|sh|bash|zsh|ps1|bat|cmd)$");

    static final Map<Character, Character> CONFUSABLES = buildConfusables();

    static Map<Character, Character> buildConfusables() {
        Map<Character, Character> m = new HashMap<>();
        // Cyrillic lower case
        m.put('а', 'a');
        m.put('е', 'e');
        m.put('о', 'o');
        m.put('р', 'p');
        m.put('с', 'c');
        m.put('у', 'y');
        m.put('х', 'x');
        m.put('і', 'i');
        m.put('ј', 'j');
        m.put('ѕ', 's');
        m.put('һ', 'h');
        m.put('ԁ', 'd');
        m.put('ԛ', 'q');
        m.put('ԝ', 'w');
        m.put('ɡ', 'g');
        m.put('ӏ', 'i');
        m.put('ı', 'i');
        m.put('ſ', 's');
        // Cyrillic upper case
        m.put('А', 'a');
        m.put('В', 'b');
        m.put('Е', 'e');
        m.put('К', 'k');
        m.put('М', 'm');
        m.put('Н', 'h');
        m.put('О', 'o');
        m.put('Р', 'p');
        m.put('С', 'c');
        m.put('Т', 't');
        m.put('Х', 'x');
        m.put('І', 'i');
        m.put('Ј', 'j');
        m.put('Ѕ', 's');
        // Greek lower case
        m.put('α', 'a');
        m.put('ο', 'o');
        m.put('ν', 'v');
        m.put('ρ', 'p');
        m.put('τ', 't');
        m.put('ι', 'i');
        m.put('κ', 'k');
        m.put('υ', 'u');
        m.put('χ', 'x');
        m.put('ε', 'e');
        // Greek upper case
        m.put('Α', 'a');
        m.put('Β', 'b');
        m.put('Ε', 'e');
        m.put('Η', 'h');
        m.put('Ι', 'i');
        m.put('Κ', 'k');
        m.put('Μ', 'm');
        m.put('Ν', 'n');
        m.put('Ο', 'o');
        m.put('Ρ', 'p');
        m.put('Τ', 't');
        m.put('Υ', 'y');
        m.put('Χ', 'x');
        m.put('Ζ', 'z');
        // typographic apostrophes so "don't" rules match
        m.put('’', '\'');
        m.put('‘', '\'');
        m.put('ʼ', '\'');
        return Map.copyOf(m);
    }
}

package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.model.Brief;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.agenticai.core.brief.BriefValidator.blank;
import static com.agenticai.core.brief.BriefValidator.isHttpUrl;
import static com.agenticai.core.brief.BriefValidator.requireRef;

/**
 * The per-section rules of {@link BriefValidator} for the parts of a brief that need no platform lookup: tools,
 * MCP servers, knowledge, secrets, channels, the eval set and the rehearsal personas. Stateless; split out of the
 * validator, which keeps the header, refs, agents, workflows (the orchestrator gate), trust paths, narrative and caps.
 */
final class BriefSectionRules {

    private static final Set<String> TOOL_KINDS = Set.of("OPENAPI", "CODE");
    private static final Set<String> TOOL_AUTH = Set.of("NONE", "API_KEY_HEADER", "BEARER", "OAUTH2_CLIENT_CREDENTIALS");
    private static final Set<String> KNOWLEDGE_KINDS = Set.of("UPLOAD_LATER", "URL", "CONNECTOR");
    private static final Set<String> SECRET_SCOPES = Set.of("TENANT", "USER");
    private static final Set<String> CHANNEL_KINDS = Set.of(
        "WHATSAPP", "TELEGRAM", "SLACK", "DISCORD", "TEAMS", "EMAIL", "SMS", "VOICE", "WEB");
    /** Vault-token shapes that must never appear in a Brief (which stores names, never values). */
    private static final Pattern SECRET_VALUE_SHAPE = Pattern.compile(
        "^(sk|pk|rk|ghp|gho|xox[abps]|AKIA|AIza)[-_A-Za-z0-9]{12,}$|^[A-Za-z0-9_\\-]{40,}$");

    private BriefSectionRules() {
    }

    // ------------------------------------------------------------------ tools / mcp / knowledge / secrets / channels

    /**
     * A tool that carries a credential sends it to whatever server its spec names, and the spec URL can come from the
     * planner (so, indirectly, from the goal text). The review must show that host next to the secret it receives.
     */
    static void warnCredentialDestinations(Brief brief, List<String> warnings) {
        for (Brief.ToolSpec t : brief.tools()) {
            if (!"OPENAPI".equals(t.kind()) || t.auth() == null || t.auth().type() == null
                || "NONE".equals(t.auth().type()) || !isHttpUrl(t.specUrl())) {
                continue;
            }
            String host;
            try {
                host = java.net.URI.create(t.specUrl().trim()).getHost();
            } catch (IllegalArgumentException e) {
                host = null;
            }
            String secret = brief.secrets().stream().filter(x -> x.ref().equals(t.auth().secretRef()))
                .map(Brief.SecretSpec::name).findFirst().orElse(t.auth().secretRef());
            warnings.add("Tool " + (blank(t.name()) ? t.ref() : t.name()) + " sends the credential " + secret + " to "
                + (host == null ? "the server in its spec" : host) + ". Check that this is the service you mean before applying.");
        }
    }

    static void validateTools(Brief brief, Map<String, Character> refs, List<Issue> errors) {
        if (brief.tools().size() > 8) {
            errors.add(new Issue("tools", "At most 8 tools."));
        }
        for (Brief.ToolSpec t : brief.tools()) {
            String at = "tools[" + t.ref() + "]";
            if (t.kind() == null || !TOOL_KINDS.contains(t.kind())) {
                errors.add(new Issue(at + ".kind", "kind must be OPENAPI or CODE."));
                continue;
            }
            if ("OPENAPI".equals(t.kind()) && !isHttpUrl(t.specUrl())) {
                errors.add(new Issue(at + ".specUrl", "An OPENAPI tool needs an http(s) spec URL."));
            }
            if ("CODE".equals(t.kind()) && blank(t.codeDescription())) {
                errors.add(new Issue(at + ".codeDescription", "A CODE tool needs a description of what to draft."));
            }
            if (t.auth() != null) {
                if (t.auth().type() == null || !TOOL_AUTH.contains(t.auth().type())) {
                    errors.add(new Issue(at + ".auth.type", "Unsupported auth type."));
                } else if (!"NONE".equals(t.auth().type())) {
                    requireRef(at + ".auth.secretRef", t.auth().secretRef(), BriefRefs.SECRET, refs, errors);
                }
            }
        }
    }

    static void validateMcp(Brief brief, Map<String, Character> refs, List<Issue> errors) {
        if (brief.mcpServers().size() > 6) {
            errors.add(new Issue("mcpServers", "At most 6 MCP servers."));
        }
        for (Brief.McpServerSpec m : brief.mcpServers()) {
            String at = "mcpServers[" + m.ref() + "]";
            if (blank(m.registryId())) {
                errors.add(new Issue(at + ".registryId", "A registry id is required."));
            }
            m.env().forEach((name, binding) -> {
                if (blank(name)) {
                    errors.add(new Issue(at + ".env", "An env variable needs a name."));
                } else if (binding == null) {
                    errors.add(new Issue(at + ".env." + name, "An env variable must reference a secret."));
                } else {
                    requireRef(at + ".env." + name + ".secretRef", binding.secretRef(), BriefRefs.SECRET, refs, errors);
                }
            });
        }
    }

    static void validateKnowledge(Brief brief, List<Issue> errors) {
        if (brief.knowledge().size() > 4) {
            errors.add(new Issue("knowledge", "At most 4 knowledge collections."));
        }
        for (Brief.KnowledgeSpec k : brief.knowledge()) {
            String at = "knowledge[" + k.ref() + "]";
            if (blank(k.name())) {
                errors.add(new Issue(at + ".name", "A name is required."));
            }
            for (int i = 0; i < k.sources().size(); i++) {
                Brief.KnowledgeSource s = k.sources().get(i);
                if (s == null || s.kind() == null || !KNOWLEDGE_KINDS.contains(s.kind())) {
                    errors.add(new Issue(at + ".sources[" + i + "].kind", "kind must be UPLOAD_LATER, URL or CONNECTOR."));
                } else if ("URL".equals(s.kind()) && !isHttpUrl(s.url())) {
                    errors.add(new Issue(at + ".sources[" + i + "].url", "A URL source needs an http(s) URL."));
                } else if ("CONNECTOR".equals(s.kind()) && blank(s.connectorType())) {
                    errors.add(new Issue(at + ".sources[" + i + "].connectorType", "A CONNECTOR source needs a connectorType."));
                }
            }
        }
    }

    static void validateSecrets(Brief brief, List<Issue> errors) {
        if (brief.secrets().size() > 10) {
            errors.add(new Issue("secrets", "At most 10 secrets."));
        }
        Set<String> names = new HashSet<>();
        for (Brief.SecretSpec s : brief.secrets()) {
            String at = "secrets[" + s.ref() + "]";
            if (s.name() == null || !BriefRefs.SECRET_NAME.matcher(s.name()).matches()) {
                errors.add(new Issue(at + ".name", "A secret name is UPPER_SNAKE_CASE, 3 to 64 characters, starting with a letter."));
            } else if (!names.add(s.name() + "/" + s.scope())) {
                errors.add(new Issue(at + ".name", "Secret '" + s.name() + "' is declared twice for the same scope."));
            }
            if (s.scope() == null || !SECRET_SCOPES.contains(s.scope())) {
                errors.add(new Issue(at + ".scope", "scope must be TENANT or USER."));
            }
            if (blank(s.why())) {
                errors.add(new Issue(at + ".why", "Say why this secret is needed."));
            }
            if (s.obtainAt() != null && SECRET_VALUE_SHAPE.matcher(s.obtainAt().strip()).matches()) {
                errors.add(new Issue(at + ".obtainAt",
                    "obtainAt must say WHERE to get the value, never contain a value - this looks like a credential."));
            }
        }
    }

    static void validateChannels(Brief brief, Map<String, Character> refs, List<Issue> errors) {
        if (brief.channels().size() > 4) {
            errors.add(new Issue("channels", "At most 4 channels."));
        }
        for (Brief.ChannelSpec c : brief.channels()) {
            String at = "channels[" + c.ref() + "]";
            if (c.kind() == null || !CHANNEL_KINDS.contains(c.kind())) {
                errors.add(new Issue(at + ".kind", "Unsupported channel kind."));
            }
            if (blank(c.bindTo())) {
                errors.add(new Issue(at + ".bindTo", "A channel must bind to an agent or workflow ref."));
            } else if (!(BriefRefs.isRef(c.bindTo(), BriefRefs.AGENT) || BriefRefs.isRef(c.bindTo(), BriefRefs.WORKFLOW))
                || !refs.containsKey(c.bindTo())) {
                errors.add(new Issue(at + ".bindTo", "'" + c.bindTo() + "' is not an agent or workflow ref declared in this brief."));
            }
        }
    }

    // ------------------------------------------------------------------ eval / rehearsal

    static void validateEvalSet(Brief brief, Map<String, Character> refs, List<Issue> errors, List<String> warnings) {
        Brief.EvalSet e = brief.evalSet();
        if (e == null) {
            warnings.add("No eval set: without one the quality gate cannot be armed and this brief cannot be promoted past UNDER_WATCH.");
            return;
        }
        if (e.cases().size() < 4 || e.cases().size() > 16) {
            errors.add(new Issue("evalSet.cases", "An eval set needs between 4 and 16 cases."));
        }
        for (int i = 0; i < e.cases().size(); i++) {
            Brief.EvalCase c = e.cases().get(i);
            if (c == null || blank(c.input()) || blank(c.rubric())) {
                errors.add(new Issue("evalSet.cases[" + i + "]", "Each case needs an input and a rubric."));
            }
        }
        if (e.targetAgent() != null) {
            requireRef("evalSet.targetAgent", e.targetAgent(), BriefRefs.AGENT, refs, errors);
        }
        if (e.gate() != null && e.gate().minScore() != null
            && (e.gate().minScore() < 0 || e.gate().minScore() > 1)) {
            errors.add(new Issue("evalSet.gate.minScore", "minScore must be between 0 and 1."));
        }
    }

    static void validateRehearsal(Brief brief, List<Issue> errors) {
        if (brief.rehearsal() == null) {
            return;
        }
        if (brief.rehearsal().personas().size() > 4) {
            errors.add(new Issue("rehearsal.personas", "At most 4 personas."));
        }
        for (int i = 0; i < brief.rehearsal().personas().size(); i++) {
            Brief.Persona p = brief.rehearsal().personas().get(i);
            if (p == null || blank(p.name()) || p.traits().isEmpty()) {
                errors.add(new Issue("rehearsal.personas[" + i + "]", "A persona needs a name and at least one trait."));
            }
        }
    }
}

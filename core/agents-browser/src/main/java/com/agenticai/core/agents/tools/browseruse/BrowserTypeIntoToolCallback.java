package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.security.TenantContext;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code browser_type_into} (browser agent v2): fills text into the element at {@code index},
 * replacing its value. The text may contain {@code {{secret.NAME}}} placeholders (the vault's
 * canonical reference, the same form {@code http_request} accepts): the value is resolved HERE, on
 * the tool thread (the vault needs the tenant context), at the moment of typing, after {@link
 * BrowserSecretPlaceholders#authorize} confirmed the page's domain may receive that secret. The
 * resolved value goes only to Playwright's {@code fill} and to the session's redaction set - it is
 * never part of any result, step event or log line, and the field is masked on screen so
 * screenshots do not show it. See {@link BrowserSecretPlaceholders} for the domain binding rule
 * (refused when no allow-list exists).
 *
 * <p>Classified like {@code browser_click_element}: {@code EGRESS}, not irreversible (typing alone
 * does not submit anything; the submit click is what the irreversibility heuristic gates).
 */
public class BrowserTypeIntoToolCallback extends BrowserV2ToolBase {

    public static final String NAME = "browser_type_into";

    public BrowserTypeIntoToolCallback(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        super(sessionManager, agentId, sessionId);
    }

    @Override
    String toolName() {
        return NAME;
    }

    @Override
    String toolDescription() {
        return "Types text into element [index] from the latest browser_snapshot, replacing its value. For credentials write "
                + "{{secret.NAME}} (e.g. {{secret.PORTAL_PASSWORD}}): the real value is filled in server-side and never shown to "
                + "you; never ask the user for a password. Secrets work only on domains an administrator allowed.";
    }

    @Override
    String toolInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "index": {
                        "type": "integer",
                        "description": "The input/textarea number from the latest browser_snapshot."
                    },
                    "text": {
                        "type": "string",
                        "description": "The text to type. May contain {{secret.NAME}} placeholders."
                    }
                },
                "required": ["index", "text"]
            }
            """;
    }

    @Override
    String execute(String tenantId, Map<String, Object> args) {
        Integer index = asIndex(args.get("index"));
        if (index == null) {
            return missingIndex();
        }
        Object rawText = args.get("text");
        String template = rawText == null ? "" : rawText.toString();
        BrowserUseV2Settings settings = sessionManager.v2Settings();
        if (settings == null) {
            return BrowserUseToolSupport.errorJson(
                    "browser agent v2 is not available on this platform");
        }
        BrowserSecretPlaceholders.requireWellFormed(template);
        Set<String> names = BrowserSecretPlaceholders.names(template);
        String resolved = template;
        Set<String> secretValues = new LinkedHashSet<>();
        String host = null;
        if (!names.isEmpty()) {
            // 1. Which domain is the browser on right now? (Playwright thread)
            host =
                    sessionManager.runV2(
                            tenantId,
                            agentId,
                            sessionId,
                            (session, page, s) -> BrowserV2Engine.currentHost(page));
            // 2. May these secrets be typed there? Refuses when there is no allow-list / binding.
            BrowserSecretPlaceholders.authorize(
                    names, host, settings.secretBindings(), settings.domainPolicy(), agentId);
            // 3. Resolve from the vault on THIS thread (tenant context), then substitute.
            String userId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
            Map<String, String> values = settings.resolveSecrets(names, userId);
            // Fail closed on values too short to scrub from later page text (a page can echo what
            // was typed).
            BrowserSecretPlaceholders.requireTypeable(values);
            resolved = BrowserSecretPlaceholders.substitute(template, values);
            secretValues.addAll(values.values());
        }
        final String textToType = resolved;
        final String expectedHost = host;
        Map<String, Object> result =
                sessionManager.runV2(
                        tenantId,
                        agentId,
                        sessionId,
                        (session, page, s) ->
                                BrowserV2Engine.typeInto(
                                        session,
                                        page,
                                        s,
                                        index,
                                        textToType,
                                        template,
                                        names,
                                        secretValues,
                                        expectedHost));
        return BrowserUseToolSupport.toJson(result);
    }
}

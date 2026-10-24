package com.agenticai.core.agents.turn.replay;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.guardrails.ToolEffectSandbox;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Which tools an offline eval turn may really run, decided from the tool CLASSIFICATION (never from the tool's
 * name, which anyone can choose) so the eval measures the real pipeline without ever changing data or reaching
 * outside the platform on the strength of a test.
 *
 * <p>A call runs for real only when its tool is declared:
 * <ul>
 *   <li>a reader: {@link ToolSensitivity#READ_PUBLIC}, {@link ToolSensitivity#READ_TENANT} or {@link
 *       ToolSensitivity#CREDENTIALED} (reads with a stored credential, such as a translation or a status
 *       lookup), and
 *   <li>repeatable and reversible: not {@code DURABLE} (a message sent, a file uploaded) and not {@code
 *       irreversible}.
 * </ul>
 * Everything else is answered with a simulated result and never reaches the tool: writes ({@link
 * ToolSensitivity#WRITE_TENANT}), anything that leaves the tenant ({@link ToolSensitivity#EGRESS}), durable or
 * irreversible effects, shell, coding, scheduling, payments, agent-to-agent and delegation tools (a delegated agent
 * runs its own turn, outside this one's sandbox).
 *
 * <p><b>Fails closed.</b> A tool nobody classified, a quarantined or invalidly declared one, a missing registry and a
 * registry that throws are all simulated. A classification derived only from an MCP server's own annotations is a
 * third-party claim and does not count (the same rule {@code AgentToolGovernance#replaySafeVerdict} applies to
 * replays): to let such a tool run in evals, classify it or name it in {@code
 * app.quality.eval.sandbox.read-only-tools}. That list only ever covers a tool with NO declared classification, so it
 * can never loosen a declared write.
 *
 * <p><b>Arguments count for one tool.</b> {@code http_request} is a reader for GET, HEAD and OPTIONS only; any other
 * method, or arguments that cannot be read, are simulated, whatever the tool's declared class (the classification is
 * by name and cannot see the method).
 */
@Component
public class EvalToolEffectPolicy {

    private static final Logger log = LoggerFactory.getLogger(EvalToolEffectPolicy.class);

    /** Tools whose class depends on one of their arguments, and the argument values that keep them read-only. */
    private static final String HTTP_REQUEST = "http_request";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectProvider<ToolClassificationRegistry> registry;

    public EvalToolEffectPolicy(ObjectProvider<ToolClassificationRegistry> registry) {
        this.registry = registry;
    }

    /**
     * The rule for one eval run.
     *
     * @param operatorReadOnlyTools names or {@code *} globs the operator vouches are read-only; each only applies to
     *     a tool that has no declared classification
     */
    public ToolEffectSandbox forRun(Collection<String> operatorReadOnlyTools) {
        List<Pattern> vouched = compile(operatorReadOnlyTools);
        return (toolName, toolInput) -> {
            Function<String, ToolClassificationDecision> resolve = classifier();
            return decide(resolve, vouched, toolName, toolInput);
        };
    }

    private Function<String, ToolClassificationDecision> classifier() {
        ToolClassificationRegistry resolved = registry.getIfAvailable();
        if (resolved == null) {
            return null;
        }
        // The MCP server's own readOnlyHint is a claim by a third party, so it is not consulted here.
        return resolved::resolveIgnoringMcpAnnotations;
    }

    /** The decision over an explicit resolver, so a test can drive every branch without a registry. */
    public static ToolEffectSandbox.Verdict decide(
            Function<String, ToolClassificationDecision> resolve,
            Collection<Pattern> operatorReadOnly,
            String toolName,
            String toolInput) {
        if (toolName == null || toolName.isBlank()) {
            return ToolEffectSandbox.Verdict.simulate("the call names no tool");
        }
        if (resolve == null) {
            return ToolEffectSandbox.Verdict.simulate("no tool classification is available");
        }
        ToolClassificationDecision decision;
        try {
            decision = resolve.apply(toolName);
        } catch (RuntimeException e) {
            log.warn("Could not classify tool '{}' for an eval turn - simulating it: {}", toolName, e.toString());
            return ToolEffectSandbox.Verdict.simulate("its classification could not be read");
        }
        if (decision instanceof ToolClassificationDecision.Classified classified) {
            ToolClassification c = classified.classification();
            if (!isReader(c)) {
                return ToolEffectSandbox.Verdict.simulate("declared " + c.riskLabel());
            }
            return readsOnlyForTheseArguments(c.toolName(), toolInput)
                    ? ToolEffectSandbox.Verdict.run()
                    : ToolEffectSandbox.Verdict.simulate("these arguments are not read-only");
        }
        if (decision instanceof ToolClassificationDecision.Blocked blocked
                && blocked.reason() == ToolClassificationDecision.Reason.UNCLASSIFIED
                && vouchedFor(operatorReadOnly, toolName)) {
            return readsOnlyForTheseArguments(toolName, toolInput)
                    ? ToolEffectSandbox.Verdict.run()
                    : ToolEffectSandbox.Verdict.simulate("these arguments are not read-only");
        }
        return ToolEffectSandbox.Verdict.simulate(
                decision == null ? "no classification" : decision.describe());
    }

    /** A reader that can be repeated and undone: see the class comment. */
    static boolean isReader(ToolClassification c) {
        boolean readSide =
                switch (c.sensitivity()) {
                    case READ_PUBLIC, READ_TENANT, CREDENTIALED -> true;
                    case WRITE_TENANT, EGRESS -> false;
                };
        return readSide && !c.irreversible() && !c.determinism().replayMustUseJournaledResponse();
    }

    private static boolean readsOnlyForTheseArguments(String toolName, String toolInput) {
        if (!HTTP_REQUEST.equals(toolName)) {
            return true;
        }
        try {
            JsonNode args = JsonMappers.plain().readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            if (args == null || !args.isObject()) {
                return false;
            }
            String method = args.path("method").asText("").trim().toUpperCase(Locale.ROOT);
            return method.isEmpty() || READ_METHODS.contains(method);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean vouchedFor(Collection<Pattern> patterns, String toolName) {
        if (patterns == null || toolName == null) {
            return false;
        }
        String name = toolName.toLowerCase(Locale.ROOT);
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** {@code *} matches any run of characters; everything else is literal; case-insensitive. */
    static List<Pattern> compile(Collection<String> globs) {
        List<Pattern> out = new ArrayList<>();
        if (globs == null) {
            return out;
        }
        for (String raw : globs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            StringBuilder regex = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (char ch : raw.strip().toLowerCase(Locale.ROOT).toCharArray()) {
                if (ch == '*') {
                    if (literal.length() > 0) {
                        regex.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    regex.append(".*");
                } else {
                    literal.append(ch);
                }
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
            }
            out.add(Pattern.compile(regex.toString()));
        }
        return out;
    }
}

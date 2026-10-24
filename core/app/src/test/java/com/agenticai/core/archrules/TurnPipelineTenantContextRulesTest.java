package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 6: the chat turn pipeline takes the tenant from its explicit {@code TurnContext},
 * never from the {@code TenantContext} ThreadLocal. Stages 1-3 brought the pipeline classes below
 * to zero {@code TenantContext.current()} / {@code TenantScope.capture()} calls and stage 4 moved
 * the one remaining read to the entries; this rule keeps it at zero (frozen at the stage-3 count,
 * 0, so nothing is baselined). A class that needs the tenant reads {@code context.tenant()} or
 * applies {@code context.scope()}.
 *
 * <p>The ThreadLocal stays legal everywhere else: repositories and the tenancy module (the JDBC
 * boundary, ADR-0016), background jobs, and the entries that build the context ({@code
 * TurnContext.forEntry}). Nested and lambda-synthetic classes of a pipeline class count as that
 * class.
 */
class TurnPipelineTenantContextRulesTest {

    private static final String TURN_PACKAGE = "com.agenticai.core.agents.turn";

    /** The turn pipeline classes (ADR-0022 sections 2.3 and 2.5), simple names in TURN_PACKAGE. */
    static final Set<String> PIPELINE_CLASSES =
            Set.of(
                    "AgentChatService",
                    "ChatTurnPreflight",
                    "RlArmSelection",
                    "ChatClientSelection",
                    "ChatPromptAssembler",
                    "ChatTurnDispatcher",
                    "ChatTurnExecutor",
                    "FastStreamTurn",
                    "FastStreamRecovery",
                    "BufferedTurnRunner",
                    "BufferedTurnCalls");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(TURN_PACKAGE);
    }

    /**
     * Releases the imported class graphs. Surefire runs all of this module's tests in one JVM, so
     * the statics of a finished test class would otherwise stay on the heap for the rest of the
     * run, and the other ArchUnit tests import the classpath again.
     */
    @AfterAll
    static void releaseImports() {
        classes = null;
    }

    /** True for {@code TenantContext.current()} and {@code TenantScope.capture()}. */
    static boolean readsTheThreadLocal(JavaCall<?> call) {
        String owner = call.getTargetOwner().getName();
        String method = call.getName();
        return (owner.equals("com.agenticai.core.security.TenantContext")
                        && method.equals("current"))
                || (owner.equals("com.agenticai.core.security.TenantScope")
                        && method.equals("capture"));
    }

    static String topLevelSimpleName(JavaClass c) {
        String name = c.getName().substring(c.getPackageName().length() + 1);
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    @Test
    void turnPipelineClassesNeverReadTheTenantThreadLocal() {
        Set<String> seen = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (JavaClass c : classes) {
            if (!c.getPackageName().equals(TURN_PACKAGE)
                    || !PIPELINE_CLASSES.contains(topLevelSimpleName(c))) {
                continue;
            }
            seen.add(topLevelSimpleName(c));
            for (JavaCodeUnit unit : c.getCodeUnits()) {
                for (JavaCall<?> call : unit.getMethodCallsFromSelf()) {
                    if (readsTheThreadLocal(call)) {
                        offenders.add(
                                c.getSimpleName()
                                        + "#"
                                        + unit.getName()
                                        + " calls "
                                        + call.getTargetOwner().getSimpleName()
                                        + "."
                                        + call.getName()
                                        + "() (line "
                                        + call.getLineNumber()
                                        + ")");
                    }
                }
            }
        }

        assertThat(seen)
                .as("every listed pipeline class must be imported, or this guard is vacuous")
                .containsExactlyInAnyOrderElementsOf(PIPELINE_CLASSES);
        assertThat(offenders)
                .as(
                        "ADR-0022: the turn pipeline reads the tenant from its TurnContext"
                                + " (context.tenant(), context.scope()), not from the"
                                + " TenantContext ThreadLocal. Offenders")
                .isEmpty();
    }

    /** The matcher must really flag the two reads, or a refactor could silently blind the rule. */
    @Test
    void theRuleRecognisesBothThreadLocalReads() {
        JavaClasses fixture = new ClassFileImporter().importClasses(Fixture.class);
        List<String> flagged = new ArrayList<>();
        for (JavaClass c : fixture) {
            for (JavaCodeUnit unit : c.getCodeUnits()) {
                for (JavaCall<?> call : unit.getMethodCallsFromSelf()) {
                    if (readsTheThreadLocal(call)) {
                        flagged.add(unit.getName() + ":" + call.getName());
                    }
                }
            }
        }
        assertThat(flagged).containsExactlyInAnyOrder("current:current", "capture:capture");
    }

    @SuppressWarnings("unused")
    static final class Fixture {
        Object current() {
            return com.agenticai.core.security.TenantContext.current();
        }

        Object capture() {
            return com.agenticai.core.security.TenantScope.capture();
        }

        Object scoped(com.agenticai.core.security.TenantContext tenant) {
            return com.agenticai.core.security.TenantScope.of(tenant);
        }
    }
}

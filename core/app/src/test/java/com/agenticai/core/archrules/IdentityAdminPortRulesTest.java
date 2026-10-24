package com.agenticai.core.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.archrules.fixtures.ViolatingIdentityCaller;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Identity administration goes through the ports in {@code com.agenticai.core.admin.identity}
 * ({@code IdentityUserAdminPort}, {@code IdentityProviderAdminPort}), never through the Keycloak
 * client directly (docs/architecture/ADR-cloud-portability.md, identity row). Only the Keycloak
 * adapter package may name {@code KeycloakAdminClient}, so a second identity provider is an adapter
 * plus configuration, not a sweep through the callers.
 *
 * <p>Production classes only: unit tests may still mock the adapter directly. The rule is also run
 * against a deliberately violating fixture to prove it can fail.
 */
class IdentityAdminPortRulesTest {

    private static final String ADAPTER_PACKAGE = "com.agenticai.core.admin.keycloak..";

    private static final ArchRule RULE =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(ADAPTER_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("com.agenticai.core.admin.keycloak.KeycloakAdminClient")
                    .because(
                            "callers must depend on IdentityUserAdminPort /"
                                    + " IdentityProviderAdminPort");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
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

    @Test
    void onlyTheKeycloakAdapterDependsOnKeycloakAdminClient() {
        RULE.check(classes);
    }

    @Test
    void ruleFailsForACallerThatNamesTheAdapterEvenAsAGenericArgument() {
        JavaClasses violating =
                new ClassFileImporter().importClasses(ViolatingIdentityCaller.class);
        AssertionError e = assertThrows(AssertionError.class, () -> RULE.check(violating));
        assertTrue(e.getMessage().contains("ViolatingIdentityCaller"), e.getMessage());
    }
}

package com.agenticai.core.agents.memorystore;

import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Placement rules for the unified-memory module (ADR-0028 section 7): the ports live in the
 * kernel's {@code agents.memory.spi}, the store in {@code agents.memorystore}, and neither reaches
 * into the turn path.
 */
class MemoryStoreArchTest {

    private static final JavaClasses STORE =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.agenticai.core.agents.memorystore");

    private static final JavaClasses SPI =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.agenticai.core.agents.memory.spi");

    @Test
    void storeDoesNotDependOnTheTurnPath() {
        noClasses()
                .that()
                .resideInAPackage("com.agenticai.core.agents.memorystore..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.agenticai.core.agents.turn..",
                        "com.agenticai.core.agents.memory",
                        "com.agenticai.core.agents.usermemory..",
                        "com.agenticai.core.knowledge..")
                .check(STORE);
    }

    @Test
    void storeIsNotInTheSpiOrLegacyMemoryPackage() {
        noClasses()
                .that()
                .resideInAnyPackage("com.agenticai.core.agents.memory..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.agenticai.core.agents.memorystore..")
                .check(SPI);
    }

    /**
     * Promotion to TRUSTED + ACTIVE has one door: {@code MemoryItemReviewService.approve}. Nothing that
     * writes proposals, recalls, records or consolidates may reference the review service (only it, the
     * configuration and a REST controller may), and only the review service calls {@code
     * MemoryItemStorage.activate}. The same is checked for classes outside this module in the app
     * module's {@code MemoryPromotionRulesTest}.
     */
    @Test
    void onlyTheReviewServiceCanPromote() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(MemoryItemReviewService.class.getName())
                .and()
                .doNotHaveFullyQualifiedName(MemoryItemReviewService.class.getName() + "$Outcome")
                .and()
                .doNotHaveSimpleName("MemoryStoreConfiguration")
                .and()
                .haveSimpleNameNotEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(MemoryItemReviewService.class.getName())
                .check(STORE);
    }

    @Test
    void onlyTheReviewServiceCallsActivate() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(MemoryItemReviewService.class.getName())
                .and()
                .doNotHaveFullyQualifiedName(JdbcMemoryItemStorage.class.getName())
                .should()
                .callMethodWhere(target(declaredIn(MemoryItemStorage.class)).and(target(nameActivate())))
                .check(STORE);
    }

    private static com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.properties.HasName> nameActivate() {
        return com.tngtech.archunit.core.domain.properties.HasName.Predicates.name("activate");
    }

    @Test
    void spiHasNoPersistenceTurnOrKnowledgeTypes() {
        noClasses()
                .that()
                .resideInAPackage("com.agenticai.core.agents.memory.spi..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.agenticai.core.agents.turn..",
                        "com.agenticai.core.agents.memory",
                        "com.agenticai.core.knowledge..",
                        "org.springframework.data..",
                        "org.springframework.jdbc..",
                        "jakarta.persistence..")
                .check(SPI);
    }
}

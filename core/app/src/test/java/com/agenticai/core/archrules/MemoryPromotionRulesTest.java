package com.agenticai.core.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unified memory (ADR-0028): the only path to a TRUSTED + ACTIVE item is {@code
 * MemoryItemReviewService.approve}, which needs an identified human. This rule covers the whole
 * assembled application: no consolidation job, recorder, distiller, skill learner or any other class
 * outside the memory store package may reference the review service or the storage port's
 * {@code activate}. A new violation fails the build; there is no baseline for this rule.
 */
class MemoryPromotionRulesTest {

    private static final String STORE = "com.agenticai.core.agents.memorystore";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.agenticai.core");
    }

    @AfterAll
    static void releaseImports() {
        classes = null;
    }

    @Test
    void nothingOutsideTheStoreCanReachTheReviewServiceOrPromotion() {
        noClasses()
                .that()
                .resideOutsideOfPackage(STORE)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(STORE + ".MemoryItemReviewService")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(STORE + ".MemoryItemStorage")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(STORE + ".JdbcMemoryItemStorage")
                .because(
                        "approve is the only promotion path and needs a human actor; consolidation, recording and"
                                + " distillation code must go through MemoryProposalSink, which can only create PENDING"
                                + " or QUARANTINED rows")
                .check(classes);
    }
}

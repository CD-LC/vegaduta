package com.agenticai.core.agents.memorystore;

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

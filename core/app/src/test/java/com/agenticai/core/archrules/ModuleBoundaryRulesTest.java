package com.agenticai.core.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-0011 phase 1: module-boundary rules between the vendor-neutral kernel and
 * vendor adapters. Lives in core/app because app's test classpath sees (almost)
 * every module; run it through the reactor ({@code -am}) so sibling modules are
 * read from their target/classes, not from stale jars in ~/.m2.
 *
 * <p>The three structural rules are {@link FreezingArchRule}s: today's violations
 * are recorded in {@code src/test/resources/archunit_store} and tolerated, any NEW
 * violation fails the build, and fixed violations drop out of the store
 * automatically ({@code allowStoreUpdate=true}). The store must only shrink.
 *
 * <p>Maintaining the store (see also {@code src/test/resources/archunit.properties}):
 * <ul>
 *   <li>Violation fixed: just run the test; the store file is rewritten without it. Commit it.</li>
 *   <li>Deliberately accepting a new violation (needs review sign-off):
 *       {@code mvn -o -pl app -am test -Dtest=ModuleBoundaryRulesTest -Dsurefire.failIfNoSpecifiedTests=false
 *       -Darchunit.freeze.refreeze=true}, then commit the store diff.</li>
 *   <li>New or renamed rule (its description is the store key):
 *       add {@code -Darchunit.freeze.store.default.allowStoreCreation=true} once.</li>
 * </ul>
 *
 * <p>The classification below mirrors {@code core/tools/module-graph/gen_module_graph.py}
 * and ADR-0011 section 2.1. Keep them in sync.
 */
class ModuleBoundaryRulesTest {

    static final String ROOT = "com.agenticai.core";

    /** Maven modules that exist only to adapt one external vendor. */
    static final Set<String> VENDOR_MODULES = Set.of("elevenlabs", "sarvam", "google", "imagegen", "payments");

    /** Per-channel sub-packages of omnichannel (vendor adapters inside a kernel module). */
    static final Set<String> CHANNEL_PACKAGES =
        Set.of("discord", "email", "matrix", "meta", "slack", "teams", "telegram", "twilio", "x");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    }

    /**
     * The vendor unit a class belongs to (e.g. {@code sarvam}, {@code billing-razorpay},
     * {@code channel-slack}), or empty for kernel / composition-root classes.
     */
    static Optional<String> vendorUnit(JavaClass c) {
        String pkg = c.getPackageName();
        if (!pkg.startsWith(ROOT + ".")) {
            return Optional.empty();
        }
        String[] parts = pkg.substring(ROOT.length() + 1).split("\\.");
        String top = parts[0];
        if (VENDOR_MODULES.contains(top)) {
            return Optional.of(top);
        }
        if (top.equals("billing") && parts.length >= 2 && parts[1].equals("gateway")) {
            String outer = topLevelSimpleName(c);
            if (outer.startsWith("Razorpay")) {
                return Optional.of("billing-razorpay");
            }
            if (outer.startsWith("PhonePe")) {
                return Optional.of("billing-phonepe");
            }
        }
        if (top.equals("omnichannel") && parts.length >= 2 && CHANNEL_PACKAGES.contains(parts[1])) {
            return Optional.of("channel-" + parts[1]);
        }
        return Optional.empty();
    }

    private static String topLevelSimpleName(JavaClass c) {
        String name = c.getName().substring(c.getName().lastIndexOf('.') + 1);
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    static final DescribedPredicate<JavaClass> VENDOR =
        DescribedPredicate.describe("are vendor adapter code", c -> vendorUnit(c).isPresent());

    /**
     * Kernel = everything under com.agenticai.core except vendor units and the
     * composition root (classes directly in {@code com.agenticai.core}: the
     * {@code @SpringBootApplication} classes and app-config's environment post-processors).
     */
    static final DescribedPredicate<JavaClass> KERNEL = DescribedPredicate.describe(
        "are kernel code",
        c -> c.getPackageName().startsWith(ROOT + ".") && vendorUnit(c).isEmpty());

    static final SliceAssignment VENDOR_UNITS = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            return vendorUnit(javaClass).map(SliceIdentifier::of).orElse(SliceIdentifier.ignore());
        }

        @Override
        public String getDescription() {
            return "vendor units";
        }
    };

    static final ArchRule KERNEL_MUST_NOT_DEPEND_ON_VENDORS = noClasses()
        .that(KERNEL)
        .should().dependOnClassesThat(VENDOR)
        .as("kernel code must not depend on vendor adapter code (ADR-0011 rule a)")
        .because("vendors must be removable from a distribution; the kernel talks to them through SPI "
            + "interfaces it owns");

    static final ArchRule VENDORS_MUST_NOT_DEPEND_ON_EACH_OTHER = slices()
        .assignedFrom(VENDOR_UNITS)
        .should().notDependOnEachOther()
        .as("vendor units must not depend on each other (ADR-0011 rule b)");

    static final ArchRule TOP_LEVEL_PACKAGES_FREE_OF_CYCLES = slices()
        .matching(ROOT + ".(*)..")
        .should().beFreeOfCycles()
        .as("top-level com.agenticai.core.* packages must be free of cycles (ADR-0011 rule c)");

    @Test
    void kernelDoesNotDependOnVendors() {
        FreezingArchRule.freeze(KERNEL_MUST_NOT_DEPEND_ON_VENDORS).check(classes);
    }

    @Test
    void vendorsDoNotDependOnEachOther() {
        FreezingArchRule.freeze(VENDORS_MUST_NOT_DEPEND_ON_EACH_OTHER).check(classes);
    }

    @Test
    void topLevelPackagesAreFreeOfCycles() {
        FreezingArchRule.freeze(TOP_LEVEL_PACKAGES_FREE_OF_CYCLES).check(classes);
    }

    /**
     * ADR-0011 phase 2 exit criterion, enforced strictly (not frozen): the kernel
     * provider registry has zero vendor dependencies. Vendors plug in by implementing
     * the providers-owned SPIs (TextToSpeechProvider, SpeechToTextProvider,
     * ExternalProviderCredentialSource).
     */
    @Test
    void providersRegistryHasNoVendorDependency() {
        noClasses()
            .that().resideInAPackage(ROOT + ".providers..")
            .should().dependOnClassesThat(VENDOR)
            .because("ADR-0011 phase 2 inverted providers -> sarvam/elevenlabs")
            .check(classes);
    }
}

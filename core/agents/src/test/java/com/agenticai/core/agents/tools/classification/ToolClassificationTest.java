package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolClassification} - the validating, immutable record the
 * whole registry hands out.
 *
 * <p>Three properties are asserted, and each one is load-bearing rather than
 * decorative:
 * <ol>
 *   <li><b>The derived human-confirmation predicate fires on exactly the right
 *       combinations.</b> It is {@code irreversible AND consequential}, and both
 *       halves matter - a too-broad predicate prompts on every knowledge write and
 *       trains operators to click approve without reading, which is strictly worse
 *       than not asking at all.</li>
 *   <li><b>The record is genuinely immutable</b>, not merely written without
 *       setters. A classification is read concurrently on the tool-dispatch path
 *       and cached, so a mutable one would make "the thing I checked is the thing I
 *       enforced" a hope instead of a fact.</li>
 *   <li><b>Construction fails rather than defaulting</b> for a missing dimension.
 *       There is no safe default: guessing {@code TRUSTED_INTERNAL} un-taints an
 *       untrusted source.</li>
 * </ol>
 */
class ToolClassificationTest {

    private static ToolClassification classification(
        ToolSensitivity sensitivity, boolean irreversible
    ) {
        return new ToolClassification(
            "some_tool", ToolTrustTier.TENANT_SCOPED, sensitivity, ToolDeterminism.DURABLE,
            null, irreversible, true);
    }

    // ---- requiresHumanConfirmation: the full matrix ------------------------

    @Test
    void requiresHumanConfirmationWhenIrreversibleAndConsequential() {
        assertTrue(classification(ToolSensitivity.WRITE_TENANT, true).requiresHumanConfirmation(),
            "an irreversible write to tenant state must be confirmed");
        assertTrue(classification(ToolSensitivity.CREDENTIALED, true).requiresHumanConfirmation(),
            "an irreversible credentialed action must be confirmed");
        assertTrue(classification(ToolSensitivity.EGRESS, true).requiresHumanConfirmation(),
            "an irreversible egress must be confirmed - this is the send-email case");
    }

    @Test
    void doesNotRequireHumanConfirmationForIrreversibleReads() {
        // fetch_web_page cannot meaningfully be "undone" either; gating reads on
        // irreversibility alone would make the platform unusable, which is exactly
        // why the predicate needs both halves.
        assertFalse(classification(ToolSensitivity.READ_PUBLIC, true).requiresHumanConfirmation(),
            "an irreversible public read is not consequential enough to confirm");
        assertFalse(classification(ToolSensitivity.READ_TENANT, true).requiresHumanConfirmation(),
            "an irreversible tenant read is not consequential enough to confirm");
    }

    @Test
    void doesNotRequireHumanConfirmationForReversibleConsequentialActions() {
        // add_to_knowledge_base is the real instance of this row: WRITE_TENANT and
        // DURABLE, but an indexed document can be deleted again.
        assertFalse(classification(ToolSensitivity.WRITE_TENANT, false).requiresHumanConfirmation());
        assertFalse(classification(ToolSensitivity.CREDENTIALED, false).requiresHumanConfirmation());
        assertFalse(classification(ToolSensitivity.EGRESS, false).requiresHumanConfirmation());
    }

    @Test
    void confirmationIsIndependentOfTrustTier() {
        // Taint adds a second, orthogonal reason to escalate, evaluated per
        // invocation. Folding it in here would mean that turning the taint gate off
        // for a tenant also silently turned off confirmation on irreversible
        // actions.
        for (ToolTrustTier trust : ToolTrustTier.values()) {
            ToolClassification c = new ToolClassification(
                "t", trust, ToolSensitivity.EGRESS, ToolDeterminism.DURABLE, null, true, true);
            assertTrue(c.requiresHumanConfirmation(), "trust tier must not affect confirmation: " + trust);
        }
    }

    // ---- the delegated predicates consumers actually call ------------------

    @Test
    void taintSourceAndSinkPredicatesDelegateToTheEnums() {
        ToolClassification webFetch = new ToolClassification(
            "fetch_web_page", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.SIDE_EFFECT, null, false, true);
        assertTrue(webFetch.producesTaintedOutput(), "an untrusted-external tool taints its output");
        assertFalse(webFetch.isTaintSink(), "a public read is not a sink - gating it would break the common case");

        ToolClassification gmail = new ToolClassification(
            "google_gmail", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.EGRESS,
            ToolDeterminism.DURABLE, "https://www.googleapis.com/auth/gmail.send", true, true);
        assertTrue(gmail.producesTaintedOutput(), "reading an inbound mailbox taints");
        assertTrue(gmail.isTaintSink(), "sending mail is a sink");
        assertTrue(gmail.requiresIdempotencyKey(), "a DURABLE send must never be blind-retried");
    }

    @Test
    void pureToolsNeedNoIdempotencyKeyAndEverythingElseDoes() {
        assertFalse(new ToolClassification(
            "p", ToolTrustTier.TRUSTED_INTERNAL, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.PURE, null, false, true).requiresIdempotencyKey());
        assertTrue(new ToolClassification(
            "s", ToolTrustTier.TRUSTED_INTERNAL, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.SIDE_EFFECT, null, false, true).requiresIdempotencyKey());
        assertTrue(new ToolClassification(
            "d", ToolTrustTier.TRUSTED_INTERNAL, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.DURABLE, null, false, true).requiresIdempotencyKey());
    }

    @Test
    void riskLabelRendersAllThreeDimensionsAndTheIrreversibleFlag() {
        assertEquals("TENANT_SCOPED/EGRESS/DURABLE+irreversible",
            classification(ToolSensitivity.EGRESS, true).riskLabel());
        assertEquals("TENANT_SCOPED/WRITE_TENANT/DURABLE",
            classification(ToolSensitivity.WRITE_TENANT, false).riskLabel());
    }

    // ---- validation: fail to construct rather than default -----------------

    @Test
    void rejectsMissingDimensions() {
        assertThrows(NullPointerException.class, () -> new ToolClassification(
            "t", null, ToolSensitivity.EGRESS, ToolDeterminism.DURABLE, null, false, true));
        assertThrows(NullPointerException.class, () -> new ToolClassification(
            "t", ToolTrustTier.TENANT_SCOPED, null, ToolDeterminism.DURABLE, null, false, true));
        assertThrows(NullPointerException.class, () -> new ToolClassification(
            "t", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, null, null, false, true));
        assertThrows(NullPointerException.class, () -> new ToolClassification(
            null, ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, ToolDeterminism.DURABLE, null, false, true));
    }

    @Test
    void rejectsBlankToolNameAndTrimsWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> new ToolClassification(
            "   ", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, ToolDeterminism.DURABLE,
            null, false, true));

        ToolClassification trimmed = new ToolClassification(
            "  padded_name  ", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS,
            ToolDeterminism.DURABLE, null, false, true);
        assertEquals("padded_name", trimmed.toolName(),
            "a stray space would silently key a row to a tool that is never dispatched");
    }

    @Test
    void normalisesBlankRequiredScopeToNull() {
        // "absent" must have exactly one representation, otherwise an empty-string
        // scope read from the database compares unequal to a null one declared in
        // code and shows up as phantom drift.
        assertNull(new ToolClassification(
            "t", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, ToolDeterminism.DURABLE,
            "   ", false, true).requiredScope());
        assertEquals("scope:x", new ToolClassification(
            "t", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, ToolDeterminism.DURABLE,
            "scope:x", false, true).requiredScope());
    }

    // ---- immutability ------------------------------------------------------

    @Test
    void recordIsStructurallyImmutable() {
        Class<?> type = ToolClassification.class;
        assertTrue(Modifier.isFinal(type.getModifiers()), "the class must be final so nobody can subclass in mutability");

        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertTrue(Modifier.isFinal(field.getModifiers()), "field must be final: " + field.getName());
            assertTrue(Modifier.isPrivate(field.getModifiers()), "field must be private: " + field.getName());
        }

        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set"), "no mutator may exist: " + method.getName());
        }
    }

    @Test
    void componentTypesAreThemselvesImmutable() {
        // A final field pointing at a mutable object is not immutability. Every
        // component here is an enum, a String or a primitive - assert that, so a
        // future component of, say, List<String> has to justify itself.
        for (Field field : ToolClassification.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> componentType = field.getType();
            assertTrue(
                componentType.isEnum() || componentType == String.class || componentType.isPrimitive(),
                "component " + field.getName() + " is " + componentType.getName()
                    + "; only enums, Strings and primitives keep this record deeply immutable");
        }
    }

    @Test
    void withToolNameReturnsACopyAndLeavesTheOriginalAlone() {
        ToolClassification pattern = new ToolClassification(
            "mcp__github__*", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, "repo:write", true, true);

        ToolClassification concrete = pattern.withToolName("mcp__github__create_issue");

        assertNotSame(pattern, concrete);
        assertEquals("mcp__github__*", pattern.toolName(), "the pattern instance must not be mutated");
        assertEquals("mcp__github__create_issue", concrete.toolName());
        assertEquals(pattern.trust(), concrete.trust());
        assertEquals(pattern.sensitivity(), concrete.sensitivity());
        assertEquals(pattern.determinism(), concrete.determinism());
        assertEquals(pattern.requiredScope(), concrete.requiredScope());
        assertEquals(pattern.irreversible(), concrete.irreversible());
        assertEquals(pattern.needsToolContext(), concrete.needsToolContext());
    }

    @Test
    void equalsIsValueBasedSoDriftDetectionWorks() {
        // driftFromBuiltInDeclarations() compares a stored row against a code
        // declaration with equals(); if that were identity-based the report would
        // claim every tool had drifted.
        ToolClassification a = classification(ToolSensitivity.EGRESS, true);
        ToolClassification b = classification(ToolSensitivity.EGRESS, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotSame(a, b);

        assertSame(a, a);
        assertNotEquals(a, classification(ToolSensitivity.EGRESS, false),
            "the irreversible flag must participate in equality - it is what drives confirmation");
    }
}

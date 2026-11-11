package com.agenticai.core.rl.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/**
 * The three arm-management exceptions are mapped to 400/404/409 by {@code GlobalExceptionHandler}
 * purely on their TYPE - so each must stay a distinct {@link RuntimeException} carrying the
 * operator-readable message verbatim.
 */
class RlArmExceptionsTest {

    @Test
    void rejectedIsADistinctRuntimeExceptionWithTheOperatorsMessage() {
        RlArmRejectedException e =
                new RlArmRejectedException("armConfigJson must be a JSON object");

        assertInstanceOf(RuntimeException.class, e);
        assertEquals("armConfigJson must be a JSON object", e.getMessage());
    }

    @Test
    void notFoundAndConflictAreDistinctTypesNotSubclassesOfEachOther() {
        RlArmNotFoundException notFound = new RlArmNotFoundException("no arm arm-1");
        RlArmConflictException conflict = new RlArmConflictException("arm key already exists");

        assertInstanceOf(RuntimeException.class, notFound);
        assertInstanceOf(RuntimeException.class, conflict);
        assertEquals("no arm arm-1", notFound.getMessage());
        assertEquals("arm key already exists", conflict.getMessage());
        // Distinct types: the handler distinguishes 404 from 409 by class alone.
        assertEquals(RlArmNotFoundException.class, notFound.getClass());
        assertEquals(RlArmConflictException.class, conflict.getClass());
    }
}

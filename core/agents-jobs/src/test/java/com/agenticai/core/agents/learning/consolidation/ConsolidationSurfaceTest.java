package com.agenticai.core.agents.learning.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The structural half of "consolidation can never promote" (phase 1 design 03, section 5): the
 * sweep's only view of memory is the read-only {@link ConsolidationFactSource} port, and its only
 * write is a tombstone insert. No class in this package may take the memory services (whose
 * {@code approve}/{@code forget}/{@code update} methods would grant or delete), the unified store's
 * review/storage types, or declare mutating methods of its own.
 */
class ConsolidationSurfaceTest {

    /** Types that could grant, rewrite or delete memory and must stay out of the sweep's reach. */
    private static final List<String> FORBIDDEN =
            List.of(
                    "AgentMemoryService",
                    "TenantMemoryService",
                    "AgentMemoryController",
                    "UserMemoryController",
                    "MemoryItemStorage",
                    "JdbcMemoryItemStorage",
                    "MemoryItemReviewService");

    @Test
    void factSourceExposesOnlyReads() {
        Set<String> methods =
                Arrays.stream(ConsolidationFactSource.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet());
        assertEquals(Set.of("subjects", "facts"), methods);
    }

    @Test
    void tombstoneWriterExposesOnlyMarkForgotten() {
        Set<String> methods =
                Arrays.stream(MemoryTombstoneWriter.class.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName)
                        .collect(Collectors.toSet());
        assertEquals(Set.of("markForgotten"), methods);
    }

    @Test
    void serviceAndSweepCannotReachTheMemoryWriteSurface() {
        for (Class<?> type : List.of(MemoryConsolidationService.class, MemoryConsolidationSweep.class)) {
            for (java.lang.reflect.Constructor<?> c : type.getDeclaredConstructors()) {
                for (Class<?> p : c.getParameterTypes()) {
                    assertTrue(FORBIDDEN.stream().noneMatch(p.getName()::endsWith),
                            type.getSimpleName() + " takes " + p.getName());
                }
            }
            for (java.lang.reflect.Field f : type.getDeclaredFields()) {
                assertTrue(FORBIDDEN.stream().noneMatch(f.getType().getName()::endsWith),
                        type.getSimpleName() + " holds " + f.getType().getName());
            }
        }
    }

    @Test
    void onlyTheAdapterTouchesTheFactServices() {
        // Every other class in this package must reach memory through ConsolidationFactSource.
        for (Class<?> type : List.of(MemoryConsolidationService.class, MemoryConsolidationSweep.class)) {
            for (Method m : type.getDeclaredMethods()) {
                for (Class<?> p : m.getParameterTypes()) {
                    assertTrue(FORBIDDEN.stream().noneMatch(p.getName()::endsWith),
                            type.getSimpleName() + "." + m.getName() + " takes " + p.getName());
                }
            }
        }
    }
}

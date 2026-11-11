package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** {@link Limits}: the documented defaults and the record-copy {@code with*} methods. */
class LimitsTest {

    @Test
    void defaultsMatchTheSpec() {
        Limits d = Limits.defaults();
        assertEquals(2_000, d.maxSourceLength());
        assertEquals(500, d.maxNodes());
        assertEquals(20_000, d.maxSteps());
        assertEquals(50_000_000L, d.maxWallNanos());
        assertEquals(256 * 1024, d.maxResultChars());
        assertEquals(200, d.maxRegexLength());
        assertEquals(64, d.maxDepth());
        assertFalse(d.strict());
        assertTrue(d.nanoTime().getAsLong() > 0, "the default clock is System::nanoTime");
    }

    @Test
    void withersCopyEveryFieldButTheirOwn() {
        Limits base = Limits.defaults();
        AtomicLong fake = new AtomicLong(7);
        Limits custom = base.withNanoTime(fake::get);

        assertEquals(7L, custom.nanoTime().getAsLong());
        assertEquals(base.maxSteps(), custom.maxSteps());

        Limits c1 = custom.withMaxSourceLength(10);
        assertEquals(10, c1.maxSourceLength());
        assertEquals(7L, c1.nanoTime().getAsLong());
        assertNotSame(base, c1);

        assertEquals(9, custom.withMaxNodes(9).maxNodes());
        assertEquals(9, custom.withMaxSteps(9).maxSteps());
        assertEquals(9L, custom.withMaxWallNanos(9L).maxWallNanos());
        assertEquals(9, custom.withMaxResultChars(9).maxResultChars());
        assertEquals(9, custom.withMaxRegexLength(9).maxRegexLength());
        assertEquals(9, custom.withMaxDepth(9).maxDepth());
        assertTrue(custom.withStrict(true).strict());
        assertFalse(custom.strict(), "the original record is untouched");

        Limits preserved = custom.withMaxSteps(9);
        assertEquals(custom.maxSourceLength(), preserved.maxSourceLength());
        assertEquals(custom.maxWallNanos(), preserved.maxWallNanos());
        assertSame(custom.nanoTime(), preserved.nanoTime());
    }
}

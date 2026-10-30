package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link BasicEvalContext}: builder defaults, null handling and node registration. */
class BasicEvalContextTest {

    @Test
    void ofInputCarriesOnlyTheInput() {
        EvalContext ctx = BasicEvalContext.ofInput("{\"a\":1}");
        assertEquals("{\"a\":1}", ctx.input());
        assertNull(ctx.nodeByLabel("L"));
        assertNull(ctx.nodeById("n1"));
        assertNull(ctx.trigger());
        assertTrue(ctx.run().isEmpty());
        assertTrue(ctx.workflow().isEmpty());
        assertTrue(ctx.vars().isEmpty());
        assertNull(ctx.item());
        assertNull(ctx.index());
        assertNull(ctx.items());
        assertNotNull(ctx.clock());
    }

    @Test
    void nullInputBecomesEmptyAndNullMapsBecomeEmpty() {
        BasicEvalContext ctx =
                BasicEvalContext.builder()
                        .input(null)
                        .run(null)
                        .workflow(null)
                        .vars(null)
                        .clock(null)
                        .build();
        assertEquals("", ctx.input());
        assertTrue(ctx.run().isEmpty());
        assertTrue(ctx.workflow().isEmpty());
        assertTrue(ctx.vars().isEmpty());
        assertNotNull(ctx.clock());
    }

    @Test
    void nodesResolveByLabelAndByIdAndTheFirstLabelWins() {
        NodeOutput a = new NodeOutput("n1", "Fetch", "1", "OK", null);
        NodeOutput dup = new NodeOutput("n2", "Fetch", "2", "OK", null);
        NodeOutput anon = new NodeOutput(null, null, "3", "OK", null);
        BasicEvalContext ctx =
                BasicEvalContext.builder().input("").node(a).node(dup).node(anon).build();

        assertSame(a, ctx.nodeByLabel("Fetch"), "on duplicate labels the first wins");
        assertSame(a, ctx.nodeById("n1"));
        assertSame(dup, ctx.nodeById("n2"));
        assertNull(ctx.nodeByLabel(null));
        assertNull(ctx.nodeById(null));
        assertNull(ctx.nodeByLabel("Other"));
    }

    @Test
    void loopRunWorkflowVarsAndTriggerAreStoredAsGiven() {
        BasicEvalContext ctx =
                BasicEvalContext.builder()
                        .input("")
                        .trigger(Map.of("k", 1L))
                        .run(Map.of("id", "r"))
                        .workflow(Map.of("name", "w"))
                        .vars(Map.of("v", 2L))
                        .loop("item", 3L, List.of("a", "b"))
                        .clock(Clock.systemUTC())
                        .build();
        assertEquals(Map.of("k", 1L), ctx.trigger());
        assertEquals(Map.of("id", "r"), ctx.run());
        assertEquals(Map.of("name", "w"), ctx.workflow());
        assertEquals(Map.of("v", 2L), ctx.vars());
        assertEquals("item", ctx.item());
        assertEquals(3L, ctx.index());
        assertEquals(List.of("a", "b"), ctx.items());
    }

    @Test
    void evalContextDefaultsAreSensible() {
        EvalContext bare = () -> "";
        assertFalse(bare.rawInputWins());
        assertNull(bare.nodeByLabel("x"));
        assertNull(bare.nodeById("x"));
        assertNull(bare.trigger());
        assertTrue(bare.run().isEmpty());
        assertTrue(bare.workflow().isEmpty());
        assertTrue(bare.vars().isEmpty());
        assertNull(bare.item());
        assertNull(bare.index());
        assertNull(bare.items());
        assertNotNull(bare.clock());
        assertTrue(bare.inputTree().isObject(), "blank input parses as an empty object");
    }
}

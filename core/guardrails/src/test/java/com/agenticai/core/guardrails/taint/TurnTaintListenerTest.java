package com.agenticai.core.guardrails.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnTaintListenerTest {

    @Test
    void hookSeesTheFirstMarkAndEachNewSourceButNotRepeats() {
        TurnTaint taint = new TurnTaint();
        List<List<String>> seen = new ArrayList<>();
        taint.onChange(seen::add);

        taint.mark("a");
        taint.mark("a");
        taint.mark("b");

        assertEquals(List.of(List.of("a"), List.of("a", "b")), seen);
    }

    @Test
    void aThrowingHookNeverBreaksTheMark() {
        TurnTaint taint = new TurnTaint();
        taint.onChange(s -> {
            throw new IllegalStateException("redis down");
        });

        assertTrue(taint.mark("a"));
        assertTrue(taint.isTainted());
    }
}

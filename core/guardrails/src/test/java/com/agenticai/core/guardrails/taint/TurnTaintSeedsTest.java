package com.agenticai.core.guardrails.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnTaintSeedsTest {

    @Test
    void sourceMarkedBeforeBindIsReplayed() {
        TurnTaintSeeds seeds = new TurnTaintSeeds();
        seeds.mark("memory_recall");
        List<String> got = new ArrayList<>();
        seeds.bind(got::add);
        assertEquals(List.of("memory_recall"), got);
    }

    @Test
    void sourceMarkedAfterBindReachesTheTurn() {
        TurnTaintSeeds seeds = new TurnTaintSeeds();
        TurnTaint turn = new TurnTaint();
        seeds.bind(turn::mark);
        seeds.mark("memory_recall");
        seeds.mark("memory_recall");
        assertEquals(List.of("memory_recall"), turn.sources());
        assertEquals(List.of("memory_recall"), seeds.sources());
    }
}

package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DatasetContentHash} (V343): the gate's staleness binding. The hash
 * MUST change for every edit that alters what a run replays or measures -
 * case content, case order (positions replay in order), pass threshold,
 * tool-execution flag - and MUST NOT change for the human-facing fields
 * that mean nothing to a rerun (name, description), or every rename would
 * force a pointless re-run.
 */
class DatasetContentHashTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    private static EvalDataset dataset(String name, String description, double threshold, boolean tools) {
        return new EvalDataset(AGENT_ID, name, description, threshold, tools, "tester");
    }

    private static EvalDatasetCase datasetCase(String userMessage, String expectedOutput, int position) {
        return new EvalDatasetCase(DATASET_ID, userMessage, expectedOutput, null, position);
    }

    @Test
    void sameContentHashesIdentically() {
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        List<EvalDatasetCase> cases = List.of(
            datasetCase("hello", "hi", 0), datasetCase("bye", "goodbye", 1));
        assertEquals(
            DatasetContentHash.of(dataset, cases),
            DatasetContentHash.of(dataset("golden", "desc", 0.7, true), cases));
    }

    @Test
    void aCaseEditChangesTheHash() {
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        String before = DatasetContentHash.of(dataset, List.of(datasetCase("hello", "hi", 0)));
        String after = DatasetContentHash.of(dataset, List.of(datasetCase("hello?", "hi", 0)));
        assertNotEquals(before, after);
    }

    @Test
    void anExpectedOutputEditChangesTheHash() {
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        String before = DatasetContentHash.of(dataset, List.of(datasetCase("hello", "hi", 0)));
        String after = DatasetContentHash.of(dataset, List.of(datasetCase("hello", "hello!", 0)));
        assertNotEquals(before, after);
    }

    @Test
    void nullAndEmptyExpectedOutputHashDifferently() {
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        String nullExpected = DatasetContentHash.of(dataset, List.of(datasetCase("hello", null, 0)));
        String emptyExpected = DatasetContentHash.of(dataset, List.of(datasetCase("hello", "", 0)));
        assertNotEquals(nullExpected, emptyExpected);
    }

    @Test
    void reorderingCasesChangesTheHash() {
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        EvalDatasetCase first = datasetCase("hello", "hi", 0);
        EvalDatasetCase second = datasetCase("bye", "goodbye", 1);
        String before = DatasetContentHash.of(dataset, List.of(first, second));
        // Replay order is position order: swapping positions means the run
        // replays a different sequence - the hash must move with it.
        first.updateCase("hello", "hi", null, 1);
        second.updateCase("bye", "goodbye", null, 0);
        assertNotEquals(before, DatasetContentHash.of(dataset, List.of(second, first)));
    }

    @Test
    void aThresholdOrToolFlagChangeChangesTheHash() {
        List<EvalDatasetCase> cases = List.of(datasetCase("hello", "hi", 0));
        String base = DatasetContentHash.of(dataset("golden", "desc", 0.7, true), cases);
        assertNotEquals(base, DatasetContentHash.of(dataset("golden", "desc", 0.8, true), cases));
        assertNotEquals(base, DatasetContentHash.of(dataset("golden", "desc", 0.7, false), cases));
    }

    @Test
    void aNameOrDescriptionOnlyEditDoesNotChangeTheHash() {
        List<EvalDatasetCase> cases = List.of(datasetCase("hello", "hi", 0));
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        String before = DatasetContentHash.of(dataset, cases);
        dataset.updateDefinition("renamed", "different description", 0.7, true);
        assertEquals(before, DatasetContentHash.of(dataset, cases));
    }

    @Test
    void aSeparatorByteInAMessageCannotForgeACaseBoundary() {
        // \u001F is the hash's field separator - pre-hashing each free-text
        // field means embedding it in content can never concatenate two
        // distinct case lists into the same canonical form.
        EvalDataset dataset = dataset("golden", "desc", 0.7, true);
        String one = DatasetContentHash.of(dataset, List.of(datasetCase("a\u001Fb", "x", 0)));
        String two = DatasetContentHash.of(dataset, List.of(datasetCase("a", "x", 0), datasetCase("b", "x", 1)));
        assertNotEquals(one, two);
    }
}

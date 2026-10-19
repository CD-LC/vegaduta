package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OwnFactAdmissionTest {

    @Test
    void roomInTheStoreAndUnderTheOwnCapEvictsNothing() {
        assertEquals(0, OwnFactAdmission.ownRowsToEvict(0, 0, 25, 50));
        assertEquals(0, OwnFactAdmission.ownRowsToEvict(24, 49, 25, 50));
    }

    @Test
    void atTheOwnCapIsRefusedEvenWhenTheStoreHasRoom() {
        assertThrows(MemoryCapExceededException.class, () -> OwnFactAdmission.ownRowsToEvict(25, 30, 25, 50));
        assertThrows(MemoryCapExceededException.class, () -> OwnFactAdmission.ownRowsToEvict(0, 0, 0, 50));
    }

    @Test
    void aFullStoreMakesRoomFromTheCallersOwnRowsOnly() {
        assertEquals(1, OwnFactAdmission.ownRowsToEvict(3, 50, 25, 50));
        assertEquals(2, OwnFactAdmission.ownRowsToEvict(3, 51, 25, 50));
    }

    @Test
    void aFullStoreWithNoRowsOfTheCallersIsRefusedRatherThanEvictingSomeoneElse() {
        assertThrows(MemoryCapExceededException.class, () -> OwnFactAdmission.ownRowsToEvict(0, 50, 25, 50));
        assertThrows(MemoryCapExceededException.class, () -> OwnFactAdmission.ownRowsToEvict(1, 51, 25, 50));
    }
}

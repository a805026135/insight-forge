package com.insightforge.agent.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptGateTest {

    @Test
    void pauseThrowsInterruptAndResumeClears() {
        InterruptGate gate = new InterruptGate();
        gate.pause("run-1");
        assertTrue(gate.isPaused("run-1"));
        InterruptSignal e = assertThrows(InterruptSignal.class, () -> gate.throwIfPaused("run-1"));
        assertFalse(e.isCancelled());

        gate.resume("run-1");
        assertFalse(gate.isPaused("run-1"));
        // 恢复后不再抛
        gate.throwIfPaused("run-1");
    }

    @Test
    void cancelThrowsCancelledSignal() {
        InterruptGate gate = new InterruptGate();
        gate.cancel("run-2");
        InterruptSignal e = assertThrows(InterruptSignal.class, () -> gate.throwIfPaused("run-2"));
        assertTrue(e.isCancelled());
    }
}

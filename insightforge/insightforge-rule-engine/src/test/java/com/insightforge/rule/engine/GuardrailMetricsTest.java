package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证护栏命中埋点 + 风险分级计数。
 */
class GuardrailMetricsTest {

    @Test
    void hitCountsActionsAndRiskLevels() {
        GuardrailMetrics m = new GuardrailMetrics();
        m.hit(new ControlInstruction(1, 1, Action.REPRICE, Map.of(), RiskLevel.MEDIUM, "t"));
        m.hit(new ControlInstruction(1, 1, Action.REALLOCATE, Map.of(), RiskLevel.MEDIUM, "t"));
        m.hit(new ControlInstruction(1, 1, Action.ALERT, Map.of(), RiskLevel.LOW, "t"));

        var s = m.snapshot();
        assertEquals(1, s.repriceHits());
        assertEquals(1, s.reallocateHits());
        assertEquals(1, s.alertHits());
        assertEquals(1, s.lowRisk());
        assertEquals(2, s.mediumRisk());
        assertEquals(0, s.highRisk());
    }

    @Test
    void shadowMismatchRecorded() {
        GuardrailMetrics m = new GuardrailMetrics();
        m.recordShadow(true);
        m.recordShadow(false);
        var s = m.snapshot();
        assertEquals(2, s.shadowChecks());
        assertEquals(1, s.shadowMismatches());
    }
}

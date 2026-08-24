package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskComplexityRouterTest {
    private final TaskComplexityRouter router = new TaskComplexityRouter();

    @Test
    void simplePricingUsesFocusedGraph() {
        RoutingDecision decision = router.route(
                new AgentTask(1L, "将商品价格调整到合理利润区间", Map.of()));
        assertEquals(RoutingDecision.Mode.FOCUSED, decision.mode());
        assertEquals(2, decision.roles().size());
        assertTrue(decision.roles().contains(Role.PRICER));
        assertTrue(decision.roles().contains(Role.RISK));
        assertFalse(decision.roles().contains(Role.SELECTOR));
        assertFalse(decision.roles().contains(Role.WARMUP));
    }

    @Test
    void campaignPlanUsesFullGraph() {
        RoutingDecision decision = router.route(
                new AgentTask(2L, "制定双11整体控盘预案", Map.of()));
        assertEquals(RoutingDecision.Mode.FULL, decision.mode());
        assertEquals(4, decision.roles().size());
        assertEquals("HIGH", decision.riskLevel());
    }

    @Test
    void ambiguousGoalFailsSafeToFullGraph() {
        RoutingDecision decision = router.route(
                new AgentTask(3L, "帮我优化一下", Map.of()));
        assertEquals(RoutingDecision.Mode.FULL, decision.mode());
        assertEquals(4, decision.roles().size());
    }
}

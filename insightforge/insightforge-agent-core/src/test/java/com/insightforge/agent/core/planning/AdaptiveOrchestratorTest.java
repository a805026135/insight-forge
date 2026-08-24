package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = com.insightforge.agent.core.AgentCoreTestConfig.class)
@ActiveProfiles("test")
class AdaptiveOrchestratorTest {
    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @Test
    void focusedPricingExecutesOnlyRequiredRoleAgentsAndStillPublishes() {
        PlanningResult result = orchestrator.plan(
                new AgentTask(556L, "只调整商品价格并检查利润风险", Map.of()));
        assertEquals(AgentRunStatus.SUCCESS, result.status());
        Set<String> producedBy = result.trace().spans().stream()
                .filter(span -> "produce".equals(span.stepName()))
                .map(span -> span.role())
                .collect(Collectors.toSet());
        assertEquals(Set.of(Role.PRICER.name(), Role.RISK.name()), producedBy);
        assertTrue(result.published().rules().stream()
                .anyMatch(rule -> rule.action() == Action.REPRICE));
        assertFalse(result.published().rules().stream()
                .anyMatch(rule -> rule.action() == Action.REALLOCATE));
    }
}

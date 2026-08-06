package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 多智能体编排器「拒绝路径」测试：
 * force-invalid-policy=true → PRICER 故意售价<成本 → DSL 编译 Fail-Close → Critic 反思重试耗尽
 * → CORRECT_FAILURE / REFUSE（正确失败）。证明「不合规 DSL 绝不发布」。
 */
@SpringBootTest(classes = com.insightforge.agent.core.AgentCoreTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "insightforge.agent.planning.force-invalid-policy=true")
class MultiAgentOrchestratorRejectTest {

    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @Autowired
    private GuardrailPolicyRepository repository;

    @Test
    void invalidPolicyLeadsToCorrectFailureAndNoPublish() {
        // 独占 activityId=888（避开 golden 1~10 / repo 77 / happy 555），防御性隔离
        AgentTask task = new AgentTask(888L, "为活动888制定预案", null);
        PlanningResult r = orchestrator.plan(task);

        assertEquals(AgentRunStatus.CORRECT_FAILURE, r.status());
        assertNull(r.published(), "Fail-Close：不合规 DSL 不得发布");
        assertNull(repository.getLatest(888L), "仓库里不应出现 activity 888 的策略");
    }
}

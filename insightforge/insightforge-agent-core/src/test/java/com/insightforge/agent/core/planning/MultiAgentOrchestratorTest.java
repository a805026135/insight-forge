package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多智能体离线规划编排器测试（happy 路径）：
 * 输入目标 → 多 Agent 产出 → 汇编 DSL → 编译通过 → 版本化发布 + 缓存。
 *
 * 注意：本类与 GuardrailPolicyRepositoryTest / GoldenEvalHarnessTest 共享同一 Spring 测试上下文
 * （配置相同，Spring 复用 ApplicationContext）⇒ 复用同一 H2 内存库。因此这里必须用「独占 activityId」
 * （避开 golden 评估集占用的 1~10、仓库测试占用的 77），否则会被其它测试先发布而污染版本号。
 */
@SpringBootTest(classes = com.insightforge.agent.core.AgentCoreTestConfig.class)
@ActiveProfiles("test")
class MultiAgentOrchestratorTest {

    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @Autowired
    private GuardrailPolicyRepository repository;

    @Test
    void happyPathProducesSuccessAndPublishes() {
        // 独占 activityId=555，避开 golden(1~10)/repo(77)，防止共享库版本号污染
        AgentTask task = new AgentTask(555L, "为活动555制定双11控盘预案", null);
        PlanningResult r = orchestrator.plan(task);

        assertEquals(AgentRunStatus.SUCCESS, r.status());
        assertNotNull(r.published(), "SUCCESS 必须产出已发布 DSL");

        // 版本号健壮断言：非空且以 "v" 开头（不硬编码 v1，规避共享库下偶发 v2）
        String ver = r.published().version();
        assertNotNull(ver, "版本号不得为空");
        assertTrue(ver.startsWith("v"), "版本号应以 v 开头: " + ver);

        // 热更新缓存命中（Phase 9 规则引擎 Worker 直接读它），且缓存版本与发布版本一致
        GuardrailPolicy latest = repository.getLatest(555L);
        assertNotNull(latest, "缓存应命中刚发布的策略");
        assertEquals(ver, latest.version(), "缓存版本应与发布版本一致");
    }
}

package com.insightforge.agent.core;

import com.insightforge.agent.core.runtime.AgentMetrics;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentRuntime;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.agent.core.runtime.FailureKind;
import com.insightforge.agent.core.runtime.TerminationReason;
import com.insightforge.agent.core.runtime.Trace;
import com.insightforge.persistence.entity.AgentTrace;
import com.insightforge.persistence.mapper.AgentTraceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时闭环测试 —— Critic 拒绝 → 正确失败（REFUSE）路径。
 * 类级 @TestPropertySource 打开本地模型桩的「故意产出不合规方案」开关，
 * 验证 Agent 不会交付「售价<成本」的方案，而是反思重试耗尽后正确拒绝。
 */
@SpringBootTest(classes = AgentCoreTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "insightforge.agent.local.force-invalid-plan=true")
class AgentRuntimeRejectTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private AgentTraceMapper traceMapper;

    @Autowired
    private AgentMetrics metrics;

    @Test
    void criticRejectionLeadsToCorrectFailure() {
        long before = metrics.correctFailures().get();
        Trace t = runtime.run(new AgentTask(1L, "为活动 activityId=1 制定双11 控盘预案", null));

        // 模型反复产出「售价<成本」的不合规方案 → 反思重试耗尽 → 正确失败（拒绝交付）
        assertEquals(AgentRunStatus.CORRECT_FAILURE, t.status());
        assertEquals(FailureKind.REFUSE, t.failureKind());
        assertTrue(t.correctFailure());
        assertEquals(TerminationReason.REFUSED, t.terminationReason());

        AgentTrace entity = traceMapper.selectById(t.traceId());
        assertNotNull(entity);
        assertEquals("CORRECT_FAILURE", entity.getStatus());
        assertTrue(entity.getCorrectFailure());
        assertEquals("REFUSED", entity.getTerminationReason());

        assertEquals(before + 1, metrics.correctFailures().get());
    }
}

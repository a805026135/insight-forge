package com.insightforge.agent.core;

import com.insightforge.agent.core.runtime.AgentRuntime;
import com.insightforge.agent.core.runtime.AgentRunStatus;
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

/**
 * 运行时闭环测试 —— 预算超限 → 优雅停止为 ESCALATE（正确失败）路径。
 * 类级 @TestPropertySource 把 token 上限压到 10，验证 BudgetGate 触发后
 * 运行时「正确地停手转人工」，而不是硬撑崩溃（FAILED）。
 */
@SpringBootTest(classes = AgentCoreTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "insightforge.agent.budget.max-tokens=10")
class AgentRuntimeBudgetTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private AgentTraceMapper traceMapper;

    @Test
    void budgetExhaustionLeadsToEscalate() {
        Trace t = runtime.run(new AgentTask(1L, "为活动 activityId=1 制定双11 控盘预案", null));

        // 预算闸门触发 → 优雅停止为正确失败（ESCALATE），而非崩溃
        assertEquals(AgentRunStatus.CORRECT_FAILURE, t.status());
        assertEquals(FailureKind.ESCALATE, t.failureKind());
        assertEquals(TerminationReason.BUDGET_EXCEEDED, t.terminationReason());

        AgentTrace entity = traceMapper.selectById(t.traceId());
        assertNotNull(entity);
        assertEquals("CORRECT_FAILURE", entity.getStatus());
        assertEquals("ESCALATE", entity.getFailureKind());
        assertEquals("BUDGET_EXCEEDED", entity.getTerminationReason());
    }
}

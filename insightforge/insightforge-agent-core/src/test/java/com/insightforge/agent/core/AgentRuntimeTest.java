package com.insightforge.agent.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.runtime.AgentMetrics;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentRuntime;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.agent.core.runtime.Trace;
import com.insightforge.persistence.entity.AgentSpan;
import com.insightforge.persistence.entity.AgentTrace;
import com.insightforge.persistence.mapper.AgentSpanMapper;
import com.insightforge.persistence.mapper.AgentTraceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时闭环测试 —— SUCCESS 路径。
 * （Critic 拒绝→正确失败、预算超限→ESCALATE 两条路径见同包下的独立测试类，
 *  因为它们的属性覆盖必须放在「类级」@TestPropertySource 上，不能放方法级。）
 */
@SpringBootTest(classes = AgentCoreTestConfig.class)
@ActiveProfiles("test")
class AgentRuntimeTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private AgentTraceMapper traceMapper;

    @Autowired
    private AgentSpanMapper spanMapper;

    @Autowired
    private AgentMetrics metrics;

    @Test
    void happyPathProducesSuccessAndPersistsTrace() {
        long before = metrics.successes().get();
        Trace t = runtime.run(new AgentTask(1L, "为活动 activityId=1 制定双11 控盘预案", null));

        assertEquals(AgentRunStatus.SUCCESS, t.status());
        assertTrue(t.correctFailure() == Boolean.FALSE);

        // 落库校验
        AgentTrace entity = traceMapper.selectById(t.traceId());
        assertNotNull(entity, "agent_trace 应落库");
        assertEquals("SUCCESS", entity.getStatus());

        // span 树：至少 建模 + 工具 + Critic 三步
        List<AgentSpan> spans = spanMapper.selectList(
                new QueryWrapper<AgentSpan>().eq("trace_id", t.traceId()));
        assertTrue(spans.size() >= 3, "应至少记录 建模/工具/Critic 三个 span，实际=" + spans.size());

        assertEquals(before + 1, metrics.successes().get());
    }
}

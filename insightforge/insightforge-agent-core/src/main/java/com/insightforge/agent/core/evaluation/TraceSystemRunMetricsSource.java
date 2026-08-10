package com.insightforge.agent.core.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AgentTrace;
import com.insightforge.persistence.mapper.AgentTraceMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 真实系统运行指标源（Phase 14.4：评估报告不再用种子，直接聚合 agent_trace 表）。
 *
 * 正确失败率 / 成功数 / 崩溃数 / 最大延迟 / 累计成本全部来自真实落库的 Trace——
 * 评估闭环自己跑出的 golden（SUCCESS）与失败样本回归（CORRECT_FAILURE）会如实计入，
 * 「该拒绝时拒绝」的红线从此有真实数据背书，而不是 InMemory 种子。
 */
@Component
public class TraceSystemRunMetricsSource implements SystemRunMetricsSource {

    private final AgentTraceMapper agentTraceMapper;

    public TraceSystemRunMetricsSource(AgentTraceMapper agentTraceMapper) {
        this.agentTraceMapper = agentTraceMapper;
    }

    @Override
    public SystemRunMetrics metrics() {
        long success = agentTraceMapper.selectCount(new QueryWrapper<AgentTrace>().eq("status", "SUCCESS"));
        long correctFailure = agentTraceMapper.selectCount(
                new QueryWrapper<AgentTrace>().eq("status", "CORRECT_FAILURE"));
        long failed = agentTraceMapper.selectCount(new QueryWrapper<AgentTrace>().eq("status", "FAILED"));

        long maxLatency = 0L;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (AgentTrace t : agentTraceMapper.selectList(null)) {
            if (t.getTotalLatencyMs() != null && t.getTotalLatencyMs() > maxLatency) {
                maxLatency = t.getTotalLatencyMs();
            }
            if (t.getBudgetCostUsd() != null) {
                totalCost = totalCost.add(t.getBudgetCostUsd());
            }
        }
        return new SystemRunMetrics(success, correctFailure, failed, maxLatency, totalCost);
    }
}

package com.insightforge.api.observability;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.AgentTrace;
import com.insightforge.persistence.entity.GuardrailPolicyEntity;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.AgentTraceMapper;
import com.insightforge.persistence.mapper.GuardrailPolicyMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.api.observability.dto.DashboardSnapshot;
import com.insightforge.api.observability.dto.GuardrailView;
import com.insightforge.api.observability.dto.TraceView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 可观测聚合服务（Phase 10）。
 *
 * 这就是大屏的「数据面」后端：把所有分散在 DB 里的真相源（秒杀经营 / Agent trace / 护栏策略）
 * 聚合成大屏要的几张图。它只读不写，且全部走 persistence 的 Mapper——
 * 因此不依赖 agent-core / rule-engine 运行时（它们是独立 Worker），沙箱里也能跑通。
 *
 * 设计要点：
 *   - 秒杀 stock/sold 用 Java 端求和（教学库表小，避免给每张表加 sum SQL）；
 *   - Agent 的「正确失败率」是红线指标，单独算、单独暴露；
 *   - 护栏风险等级从 policy_json 确定性推导（见 GuardrailView 说明）。
 */
@Service
public class ObservabilityService {

    private final ActivityMapper activityMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;
    private final AgentTraceMapper agentTraceMapper;
    private final GuardrailPolicyMapper guardrailPolicyMapper;

    public ObservabilityService(ActivityMapper activityMapper,
                                ActivitySkuMapper activitySkuMapper,
                                OrdersMapper ordersMapper,
                                AgentTraceMapper agentTraceMapper,
                                GuardrailPolicyMapper guardrailPolicyMapper) {
        this.activityMapper = activityMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.agentTraceMapper = agentTraceMapper;
        this.guardrailPolicyMapper = guardrailPolicyMapper;
    }

    // ============ 计数方法（同时供 Prometheus Gauge 复用，见 MetricsConfig） ============

    public long countActivities() {
        return activityMapper.selectCount(new QueryWrapper<>());
    }

    public long countLiveActivities() {
        return activityMapper.selectCount(new QueryWrapper<Activity>().eq("status", "LIVE"));
    }

    public long countOrders() {
        return ordersMapper.selectCount(new QueryWrapper<>());
    }

    public long sumStock() {
        return activitySkuMapper.selectList(new QueryWrapper<>()).stream()
                .mapToLong(s -> s.getStock() == null ? 0 : s.getStock())
                .sum();
    }

    public long sumSold() {
        return activitySkuMapper.selectList(new QueryWrapper<>()).stream()
                .mapToLong(s -> s.getSold() == null ? 0 : s.getSold())
                .sum();
    }

    public long countTraces() {
        return agentTraceMapper.selectCount(new QueryWrapper<>());
    }

    public long countSuccessTraces() {
        return agentTraceMapper.selectCount(new QueryWrapper<AgentTrace>().eq("status", "SUCCESS"));
    }

    public long countCorrectFailureTraces() {
        return agentTraceMapper.selectCount(new QueryWrapper<AgentTrace>()
                .eq("status", "CORRECT_FAILURE"));
    }

    public long countFailedTraces() {
        return agentTraceMapper.selectCount(new QueryWrapper<AgentTrace>().eq("status", "FAILED"));
    }

    public long countPolicies() {
        return guardrailPolicyMapper.selectCount(new QueryWrapper<>());
    }

    /** 正确失败率（0~1）；无运行时返回 0，不抛异常。 */
    public double correctFailureRate() {
        long total = countTraces();
        if (total == 0) {
            return 0d;
        }
        return (double) countCorrectFailureTraces() / total;
    }

    // ============================ 快照 / 列表 ============================

    public DashboardSnapshot buildSnapshot() {
        long totalActivities = countActivities();
        long liveActivities = countLiveActivities();
        long totalStock = sumStock();
        long soldStock = sumSold();
        long totalOrders = countOrders();

        long totalRuns = countTraces();
        long successRuns = countSuccessTraces();
        long correctFailureRuns = countCorrectFailureTraces();
        long failedRuns = countFailedTraces();

        // Agent 延迟最大值 + 成本累计（扫描一遍 trace 表，教学量小）
        long maxLatency = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (AgentTrace t : agentTraceMapper.selectList(new QueryWrapper<>())) {
            if (t.getTotalLatencyMs() != null && t.getTotalLatencyMs() > maxLatency) {
                maxLatency = t.getTotalLatencyMs();
            }
            if (t.getBudgetCostUsd() != null) {
                totalCost = totalCost.add(t.getBudgetCostUsd());
            }
        }

        List<GuardrailView> guardrails = guardrails(null);
        long activitiesWithPolicy = guardrails.size();
        String topRisk = guardrails.stream()
                .map(GuardrailView::topRiskLevel)
                .max(Comparator.comparingInt(RiskRank::of))
                .orElse("低");

        DashboardSnapshot.GuardrailSummary guardrailSummary =
                new DashboardSnapshot.GuardrailSummary(countPolicies(), activitiesWithPolicy, topRisk);
        DashboardSnapshot.AgentSummary agentSummary = new DashboardSnapshot.AgentSummary(
                totalRuns, successRuns, correctFailureRuns, failedRuns,
                correctFailureRate(), maxLatency, totalCost.doubleValue());
        DashboardSnapshot.SeckillSummary seckillSummary = new DashboardSnapshot.SeckillSummary(
                totalActivities, liveActivities, totalStock, soldStock, totalOrders);

        return new DashboardSnapshot(seckillSummary, agentSummary, guardrailSummary,
                LocalDateTime.now().toString());
    }

    /** 最近 N 条 trace（大屏 Trace 查看器）。 */
    public List<TraceView> recentTraces(int limit) {
        List<AgentTrace> traces = agentTraceMapper.selectList(
                new QueryWrapper<AgentTrace>().orderByDesc("started_at").last("LIMIT " + Math.max(1, limit)));
        return traces.stream().map(this::toView).collect(Collectors.toList());
    }

    /** HITL 人工复核队列：正确失败（ASK/REFUSE/ESCALATE）或显式标记 correct_failure 的 trace。 */
    public List<TraceView> hitlQueue() {
        List<AgentTrace> traces = agentTraceMapper.selectList(
                new QueryWrapper<AgentTrace>()
                        .eq("correct_failure", true)
                        .or().eq("status", "CORRECT_FAILURE")
                        .orderByDesc("started_at"));
        return traces.stream().map(this::toView).collect(Collectors.toList());
    }

    /** 护栏策略视图；activityId 为空则返回全部活动最新版。 */
    public List<GuardrailView> guardrails(Long activityId) {
        QueryWrapper<GuardrailPolicyEntity> q = new QueryWrapper<>();
        if (activityId != null) {
            q.eq("activity_id", activityId);
        }
        List<GuardrailPolicyEntity> all = guardrailPolicyMapper.selectList(q);

        // 每个活动保留 id 最大（= 最新）的一条
        Map<Long, GuardrailPolicyEntity> latest = new LinkedHashMap<>();
        for (GuardrailPolicyEntity e : all) {
            latest.merge(e.getActivityId(), e, (oldE, newE) -> newE.getId() > oldE.getId() ? newE : oldE);
        }

        List<GuardrailView> result = new ArrayList<>();
        for (GuardrailPolicyEntity e : latest.values()) {
            String json = e.getPolicyJson() == null ? "" : e.getPolicyJson();
            int ruleCount = countOccurrences(json, "\"action\"");
            String risk = deriveRisk(json);
            result.add(new GuardrailView(
                    e.getActivityId(), e.getVersion(), ruleCount, risk,
                    String.format("活动 %d 当前生效策略 %s，含 %d 条规则，最高风险：%s。",
                            e.getActivityId(), e.getVersion(), ruleCount, risk)));
        }
        return result;
    }

    // ============================ 内部工具 ============================

    private TraceView toView(AgentTrace t) {
        return new TraceView(
                t.getTraceId(), t.getActivityId(), t.getStatus(), t.getFailureKind(),
                t.getCorrectFailure(), t.getStartedAt(), t.getFinishedAt(),
                t.getTotalLatencyMs(), t.getBudgetCostUsd(), t.getModelVersion(),
                TraceView.toVerdict(t.getStatus(), t.getFailureKind()));
    }

    /** 从 policy_json 确定性推导最高风险（不依赖 DSL 类型）。 */
    private String deriveRisk(String json) {
        if (json.contains("\"CROSS") || json.contains("REALLOCATE")) {
            return "高"; // 写入 / 跨活动预算再分配
        }
        if (json.contains("REPRICE")) {
            return "中"; // 调价
        }
        return "低"; // 只读类
    }

    private int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 风险等级排序工具（高 > 中 > 低），用于取「最高风险」。 */
    private static final class RiskRank {
        static int of(String risk) {
            return switch (risk) {
                case "高" -> 3;
                case "中" -> 2;
                default -> 1;
            };
        }
    }
}

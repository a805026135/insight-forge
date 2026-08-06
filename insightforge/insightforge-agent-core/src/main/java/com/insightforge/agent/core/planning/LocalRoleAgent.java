package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.memory.Artifact;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地确定性角色 Agent（离线桩，默认实现）。
 *
 * 它不联网、不调 LLM，而是按固定启发式产出各角色的结构化产物——
 * 真实驱动了「Planner 拆任务 → 角色 Agent 产出 → 汇编 DSL → 编译 → 发布」整条管线，
 * 方便本地/测试跑通与教学演示。生产换成调 DeepSeek 的角色 Agent 即可，接口不变。
 *
 * forceInvalid=true 时 PRICER 故意把售价定得低于成本，用于演示 DSL 编译 Fail-Close → Critic 拒绝。
 */
@Component
@Primary
public class LocalRoleAgent implements RoleAgent {

    @Override
    public Artifact run(SubTask task, PlanningContext ctx) {
        return switch (task.role()) {
            case SELECTOR -> Artifact.of("sel-" + task.activityId(),
                    "selection", Map.of(
                            "includeSkus", List.of(101, 102),
                            "stockRatioThreshold", 0.2));
            case PRICER -> {
                BigDecimal cost = ctx.costFloor();
                // 正常：成本上浮 5% 定价；forceInvalid：故意低于成本（演示护栏拦截）
                BigDecimal reprice = ctx.forceInvalid
                        ? cost.subtract(BigDecimal.valueOf(500))
                        : cost.multiply(BigDecimal.valueOf(1.05));
                yield Artifact.of("price-" + task.activityId(),
                        "pricing", Map.of("repriceTo", reprice, "costFloor", cost));
            }
            case WARMUP -> Artifact.of("warm-" + task.activityId(),
                    "warmup", Map.of(
                            "reallocateAmount", 50,
                            "targetSegment", "seg_hot"));
            case RISK -> Artifact.of("risk-" + task.activityId(),
                    "risk", Map.of("alertOnVelocityDrop", true));
            default -> null;   // 未知角色不产出（编排器会记一次 invalidStep）
        };
    }

    /** 把 Artifact.content（我们存的是 Map）安全转成 Map。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Artifact a) {
        if (a == null || !(a.content() instanceof Map)) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) a.content();
    }
}

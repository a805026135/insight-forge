package com.insightforge.agent.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立验证器（设计文档 §4 第一道：确定性程序校验）。
 *
 * 它校验「方案 JSON」是否违反硬编码护栏——对应 spec §5.6 离线规划期静态校验：
 *   - 售价必须 ≥ 成本（调价不得低于成本价）
 *   - 再分配数量必须 ≥ 0（库存不可透支）
 *   - 必填字段齐全、方案是合法 JSON
 * 这些是「能程序判定的绝不先问模型」的典范。Phase 8 会把 Critic 升级为
 * 「程序 → 规则 → 模型/人工」三道校验；Phase 7 先把确定性第一道打底。
 */
@Component
public class Critic {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public CriticVerdict verifyPlan(String planJson) {
        List<String> reasons = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(planJson);

            // 必填字段
            if (!node.has("activityId")) {
                reasons.add("方案缺少 activityId");
            }

            // 定价护栏：repriceTo >= costFloor
            JsonNode pricing = node.get("pricing");
            if (pricing == null) {
                reasons.add("方案缺少 pricing 段");
            } else {
                double repriceTo = pricing.path("repriceTo").asDouble(Double.NaN);
                double costFloor = pricing.path("costFloor").asDouble(Double.NaN);
                if (Double.isNaN(repriceTo) || Double.isNaN(costFloor)) {
                    reasons.add("pricing.repriceTo / costFloor 缺失或非数值");
                } else if (repriceTo < costFloor) {
                    reasons.add(String.format("售价 %.2f < 成本 %.2f（违反『调价不得低于成本价』护栏）",
                            repriceTo, costFloor));
                }
            }

            // 库存护栏：再分配数量 >= 0
            JsonNode realloc = node.get("reallocation");
            if (realloc != null) {
                int amount = realloc.path("amount").asInt(Integer.MIN_VALUE);
                if (amount == Integer.MIN_VALUE) {
                    reasons.add("reallocation.amount 缺失或非整数");
                } else if (amount < 0) {
                    reasons.add("再分配数量为负（违反『库存不可透支』护栏）");
                }
            }
        } catch (Exception e) {
            reasons.add("方案不是合法 JSON: " + e.getMessage());
        }

        boolean pass = reasons.isEmpty();
        return pass ? CriticVerdict.pass()
                : CriticVerdict.fail(reasons, 0.97, "HIGH");
    }
}

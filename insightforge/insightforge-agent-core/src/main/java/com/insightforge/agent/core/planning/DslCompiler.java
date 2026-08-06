package com.insightforge.agent.core.planning;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 护栏 DSL 静态编译器（设计文档 §5 L3 硬门槛 + Phase 8 自编译校验）。
 *
 * 对应 spec §5.5「DSL 静态编译器（JSON Schema 校验 + 翻译，Fail-Close 阻断上线」的「校验」部分——
 * Phase 9 才做「JSON→Drools 翻译」；这里先把「编译不过就不上线」的门禁打底。
 *
 * 两道确定性校验（能程序判定的绝不先问模型）：
 *   ① 结构校验：activityId/version/rules 合法；每条规则的 id 唯一、metric/operator/action 在白名单、阈值非负。
 *   ② 业务护栏：REPRICE 的 repriceTo 必须 ≥ costFloor（调价不得低于成本价）；REALLOCATE 的 amount ≥ 0（库存不可透支）。
 * 任一不过 → valid=false（Fail-Close），编排器拒绝发布并走 Critic 反思重试。
 */
@Component
public class DslCompiler {

    public DslCompileResult compile(GuardrailPolicy p) {
        List<String> errs = new ArrayList<>();
        if (p == null) {
            return DslCompileResult.fail(List.of("policy 为 null"));
        }
        if (p.activityId() == null || p.activityId() <= 0) {
            errs.add("activityId 非法");
        }
        if (p.version() == null || p.version().isBlank()) {
            errs.add("version 缺失");
        }
        if (p.rules() == null || p.rules().isEmpty()) {
            errs.add("rules 为空（策略必须有至少一条规则）");
        }

        Set<String> ids = new HashSet<>();
        for (GuardrailRule r : p.rules()) {
            String rid = r.id() == null ? "<null>" : r.id();
            if (r.id() == null || r.id().isBlank()) {
                errs.add("存在规则缺少 id");
            } else if (!ids.add(r.id())) {
                errs.add("规则 id 重复: " + r.id());
            }
            if (r.metric() == null || r.metric() == Metric.INVALID) {
                errs.add("规则 " + rid + " 指标非法（不在白名单）");
            }
            if (r.operator() == null || r.operator() == Operator.INVALID) {
                errs.add("规则 " + rid + " 算子非法（不在白名单）");
            }
            if (r.threshold() == null || r.threshold().compareTo(BigDecimal.ZERO) < 0) {
                errs.add("规则 " + rid + " 阈值非法（缺失或为负）");
            }
            if (r.action() == null || r.action() == Action.INVALID) {
                errs.add("规则 " + rid + " 动作非法（不在白名单）");
            }

            // —— 业务护栏（确定性第二道）——
            if (r.action() == Action.REPRICE) {
                BigDecimal repriceTo = num(r.params(), "repriceTo");
                BigDecimal costFloor = num(r.params(), "costFloor");
                if (repriceTo == null || costFloor == null) {
                    errs.add("规则 " + rid + " REPRICE 缺少 repriceTo/costFloor");
                } else if (repriceTo.compareTo(costFloor) < 0) {
                    errs.add(String.format("规则 %s 售价 %.2f < 成本 %.2f（违反『调价不得低于成本价』护栏）",
                            rid, repriceTo, costFloor));
                }
            }
            if (r.action() == Action.REALLOCATE) {
                BigDecimal amount = num(r.params(), "amount");
                if (amount == null) {
                    errs.add("规则 " + rid + " REALLOCATE 缺少 amount");
                } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                    errs.add("规则 " + rid + " 再分配数量为负（违反『库存不可透支』护栏）");
                }
            }
        }
        return errs.isEmpty() ? DslCompileResult.ok() : DslCompileResult.fail(errs);
    }

    private BigDecimal num(Map<String, Object> m, String key) {
        if (m == null || !m.containsKey(key)) {
            return null;
        }
        Object v = m.get(key);
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}

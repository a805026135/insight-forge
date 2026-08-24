package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.AgentTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 风险与复杂度自适应路由器。
 *
 * 固定四 Agent 会让简单任务付出不必要的延迟和 token。本路由器先做低成本、
 * 可解释的业务分类，只选择完成目标所需的角色；目标模糊、跨域或上下文标记
 * 高风险时 Fail-Safe 升级到完整四角色图。
 */
@Component
public class TaskComplexityRouter {

    private static final List<String> FULL_TASK_WORDS = List.of(
            "控盘", "预案", "综合", "全量", "大促", "双11", "双十二", "整体");
    private static final List<String> SELECT_WORDS = List.of(
            "选品", "商品组合", "sku", "品类");
    private static final List<String> PRICE_WORDS = List.of(
            "调价", "定价", "价格", "折扣", "利润", "毛利", "成本");
    private static final List<String> STOCK_WORDS = List.of(
            "库存", "补货", "调拨", "预热", "售罄");
    private static final List<String> RISK_WORDS = List.of(
            "风险", "异常", "告警", "安全", "越权");

    public RoutingDecision route(AgentTask task) {
        String goal = task.goal() == null ? "" : task.goal().toLowerCase(Locale.ROOT);
        Map<String, Object> context = task.context() == null ? Map.of() : task.context();
        Set<Role> roles = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        int score = 0;

        boolean forceFull = containsAny(goal, FULL_TASK_WORDS)
                || flag(context, "forceFullPlanning")
                || flag(context, "crossMerchant")
                || flag(context, "budgetChange");
        if (containsAny(goal, SELECT_WORDS)) {
            roles.add(Role.SELECTOR);
            reasons.add("涉及选品");
            score++;
        }
        if (containsAny(goal, PRICE_WORDS)) {
            roles.add(Role.PRICER);
            reasons.add("涉及价格或利润");
            score += 2;
        }
        if (containsAny(goal, STOCK_WORDS)) {
            roles.add(Role.WARMUP);
            reasons.add("涉及库存写操作");
            score += 2;
        }
        if (containsAny(goal, RISK_WORDS)) {
            reasons.add("显式风险诉求");
            score += 2;
        }

        roles.add(Role.RISK);
        if (forceFull || roles.size() >= 3 || roles.size() == 1) {
            roles.clear();
            roles.addAll(List.of(Role.SELECTOR, Role.PRICER, Role.WARMUP, Role.RISK));
            reasons.add(forceFull ? "跨域/活动级目标，升级完整协作"
                    : "目标不明确，Fail-Safe 升级完整协作");
            return new RoutingDecision(RoutingDecision.Mode.FULL, List.copyOf(roles),
                    Math.max(score, 4), "HIGH", reasons);
        }

        String risk = roles.contains(Role.PRICER) || roles.contains(Role.WARMUP) ? "HIGH" : "MEDIUM";
        reasons.add("仅调用最小必要角色");
        return new RoutingDecision(RoutingDecision.Mode.FOCUSED, List.copyOf(roles),
                score, risk, reasons);
    }

    public RoutingDecision fullDecision(String reason) {
        return new RoutingDecision(RoutingDecision.Mode.FULL,
                List.of(Role.SELECTOR, Role.PRICER, Role.WARMUP, Role.RISK),
                4, "HIGH", List.of(reason));
    }

    private boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    private boolean flag(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }
}

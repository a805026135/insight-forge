package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;

import java.util.Map;

/**
 * 标准化控盘指令（规则引擎「想做的事」）。
 *
 * 大白话：规则引擎读完 tick、匹配到规则后，不会自己动手改库存，而是开出一张「工单」：
 * 动作(action) + 参数(params) + 风险等级(riskLevel) + 溯源(traceId)。真正的执行交给
 * {@link ControlInstructionExecutor}，而且执行时走「和用户抢购同一套锁」——
 * 这就是为什么 AI 再聪明也不会把库存搞乱（控制面/数据面分离的最终落点）。
 */
public class ControlInstruction {

    private final long activityId;
    private final long skuId;
    private final Action action;                 // REPRICE / REALLOCATE / ALERT（复用 Phase 8 DSL 的动作枚举）
    private final Map<String, Object> params;    // 动作参数（如 repriceTo / amount / reason）
    private final RiskLevel riskLevel;           // 风险分级（LOW/MEDIUM/HIGH）
    private final String traceId;                // 溯源：这条指令来自哪次规则运行（便于线上归因）

    public ControlInstruction(long activityId, long skuId, Action action,
                              Map<String, Object> params, RiskLevel riskLevel, String traceId) {
        this.activityId = activityId;
        this.skuId = skuId;
        this.action = action;
        this.params = params;
        this.riskLevel = riskLevel;
        this.traceId = traceId;
    }

    public long getActivityId() {
        return activityId;
    }

    public long getSkuId() {
        return skuId;
    }

    public Action getAction() {
        return action;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getTraceId() {
        return traceId;
    }

    public int intParam(String key, int defaultValue) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    public double doubleParam(String key, double defaultValue) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Number n ? n.doubleValue() : defaultValue;
    }

    @Override
    public String toString() {
        return "ControlInstruction{" + action + " sku=" + skuId + " params=" + params + " risk=" + riskLevel + '}';
    }
}

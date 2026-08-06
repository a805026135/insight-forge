package com.insightforge.agent.core.planning;

import java.math.BigDecimal;

/**
 * 离线规划的可复用上下文（跨角色 Agent 传递，不塞进 prompt）。
 * 大白话：这次规划围绕哪个活动、成本基线多少、要不要故意产错（测试开关）。
 */
public class PlanningContext {
    public final Long activityId;
    public final BigDecimal costFloor;
    public final String goal;
    public final boolean forceInvalid;

    public PlanningContext(Long activityId, BigDecimal costFloor, String goal, boolean forceInvalid) {
        this.activityId = activityId;
        this.costFloor = costFloor;
        this.goal = goal;
        this.forceInvalid = forceInvalid;
    }

    public Long activityId() {
        return activityId;
    }

    public BigDecimal costFloor() {
        return costFloor;
    }

    public String goal() {
        return goal;
    }

    public boolean forceInvalid() {
        return forceInvalid;
    }
}

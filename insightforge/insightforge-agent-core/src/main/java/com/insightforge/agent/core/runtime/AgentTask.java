package com.insightforge.agent.core.runtime;

import java.util.Map;

/**
 * 一次 Agent 规划任务（Orchestrator 发起的运行单元）。
 * 对应设计文档：一个 trace 由一个任务驱动。
 */
public class AgentTask {

    private final String id;
    private final Long activityId;
    /** 自然语言目标，如「为活动 activityId=1 制定双11 控盘预案」。 */
    private final String goal;
    /** 额外上下文（如成本基线、合规约束），可选。 */
    private final Map<String, Object> context;

    public AgentTask(Long activityId, String goal, Map<String, Object> context) {
        this.id = "task-" + System.nanoTime();
        this.activityId = activityId;
        this.goal = goal;
        this.context = context;
    }

    public String id() {
        return id;
    }

    public Long activityId() {
        return activityId;
    }

    public String goal() {
        return goal;
    }

    public Map<String, Object> context() {
        return context;
    }
}

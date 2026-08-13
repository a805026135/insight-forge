package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.memory.Artifact;

/**
 * 角色 Agent 接口（大白话：不管谁来干这个活儿，都长这样）。
 * 生产环境换成「调 DeepSeek 的某角色 Agent」只需另写一个实现，接口不变（不绑死模型）。
 */
public interface RoleAgent {
    /** 执行一个子任务，返回结构化产物（Artifact），供 Guardrail Policy Agent 汇编。 */
    Artifact run(SubTask task, PlanningContext ctx);

    /** 带模型计量信息的执行结果；本地角色默认没有模型消耗。 */
    default RoleAgentResult runMeasured(SubTask task, PlanningContext ctx) {
        return new RoleAgentResult(run(task, ctx), 0, 0, 0);
    }

    record RoleAgentResult(Artifact artifact, int tokenIn, int tokenOut, long latencyMs) {}
}

package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.Trace;

/**
 * 一次离线规划运行的结果（给调用方/测试用）。
 * trace 已落库（可观测）；published 仅 SUCCESS 时有值（Fail-Close：不合规不发布）。
 */
public record PlanningResult(Trace trace, GuardrailPolicy published, AgentRunStatus status) {
}

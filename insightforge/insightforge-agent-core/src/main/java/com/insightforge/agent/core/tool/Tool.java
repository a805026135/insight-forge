package com.insightforge.agent.core.tool;

import com.insightforge.agent.core.memory.ArtifactStore;
import com.insightforge.agent.core.memory.MemoryStore;

import java.util.Map;

/**
 * 工具契约（设计文档 §5.1 工具系统）。
 * 每个工具用 JSON Schema 声明参数；运行时先校验 schema 再执行，执行结果（observation）回灌短期记忆。
 */
public interface Tool {

    /** 工具名（模型 tool_call 的 name 必须能在此命中）。 */
    String name();

    /** 给模型看的说明。 */
    String description();

    /** 参数 JSON Schema（字符串），供 ParameterValidator 校验 + 暴露给模型。 */
    String parametersJson();

    /**
     * 执行工具。
     * @param args   经 JSON Schema 校验后的参数
     * @param ctx    运行上下文（traceId / activityId / ArtifactStore / MemoryStore），工具可写产物、查记忆
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}

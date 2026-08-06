package com.insightforge.agent.core.model;

/**
 * 暴露给模型的工具声明（给模型「看」的说明书）。
 * parametersJson 是标准 JSON Schema 字符串，模型据此生成合法 args。
 */
public record ToolSpec(String name, String description, String parametersJson) {
}

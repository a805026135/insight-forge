package com.insightforge.agent.core.model;

import java.util.Map;

/**
 * 模型决定要调用的工具（函数调用）。
 * name 必须能在 ToolRegistry 找到；args 是经 JSON Schema 校验后的参数。
 */
public record ToolCall(String name, Map<String, Object> args) {
}

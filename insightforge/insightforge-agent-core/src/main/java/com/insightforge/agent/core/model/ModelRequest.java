package com.insightforge.agent.core.model;

import java.util.List;

/**
 * 一次模型推理请求：对话历史 + 模型名 + 可用工具声明。
 */
public record ModelRequest(List<ChatMessage> messages, String model, List<ToolSpec> tools) {
}

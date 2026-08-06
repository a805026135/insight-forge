package com.insightforge.agent.core.model;

/**
 * 一条对话消息（系统 / 用户 / 助手 / 工具）。
 * 用 record 保证不可变；Agent 的短期记忆就是一串 ChatMessage。
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** 工具回灌的观察结果——runtime 会以 role=tool 追加，本地模型据此判断「该出最终方案了」。 */
    public static ChatMessage tool(String content) {
        return new ChatMessage("tool", "TOOL_RESULT: " + content);
    }
}

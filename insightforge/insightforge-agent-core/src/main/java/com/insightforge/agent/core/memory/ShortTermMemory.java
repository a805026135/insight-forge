package com.insightforge.agent.core.memory;

import com.insightforge.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 短期记忆：当前任务的一次消息窗口（设计文档 §5.2 短期记忆）。
 * Agent 运行时每轮把 system / tool 结果 / Critic 意见追加进来，喂给模型。
 * 超过 cap 自动丢弃最旧消息（对应「超长触发摘要压缩」的简化版——Phase 7 用截断，不写摘要模型）。
 */
public class ShortTermMemory {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final int cap;

    public ShortTermMemory() {
        this(40);
    }

    public ShortTermMemory(int cap) {
        this.cap = cap;
    }

    public void add(ChatMessage message) {
        messages.add(message);
        // 超过容量：丢弃最旧（保留系统提示之外的上下文可控）
        while (messages.size() > cap) {
            messages.remove(0);
        }
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public int size() {
        return messages.size();
    }
}

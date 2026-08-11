package com.insightforge.agent.core.memory;

import com.insightforge.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 压缩型短期记忆（滑动窗口 + 摘要压缩，对应 Claude Code 的 compaction）：
 * 消息超过 compressThreshold 时，把最旧的 compressWindow 条压缩成一条“历史摘要”，
 * 保留摘要 + 最近消息；摘要本身继续增长到 cap 仍会兜底截断。
 * 默认本地提取式摘要（确定性、零延迟、不额外调模型），可注入 LLM 摘要器升级。
 */
public class CompactingMemory {

    private final int cap;
    private final int compressThreshold;
    private final int compressWindow;
    private final Function<List<ChatMessage>, String> summarizer;
    private final List<ChatMessage> messages = new ArrayList<>();

    public CompactingMemory() {
        this(24, 18, 8);
    }

    public CompactingMemory(int cap, int compressThreshold, int compressWindow) {
        this(cap, compressThreshold, compressWindow, CompactingMemory::localSummarize);
    }

    public CompactingMemory(int cap, int compressThreshold, int compressWindow,
                            Function<List<ChatMessage>, String> summarizer) {
        this.cap = Math.max(6, cap);
        this.compressThreshold = Math.max(4, Math.min(compressThreshold, this.cap - 2));
        this.compressWindow = Math.max(2, Math.min(compressWindow, this.compressThreshold - 1));
        this.summarizer = summarizer;
    }

    public void add(ChatMessage message) {
        messages.add(message);
        while (messages.size() > compressThreshold) {
            int end = Math.min(compressWindow, messages.size() - 1);
            List<ChatMessage> oldest = new ArrayList<>(messages.subList(0, end));
            String summary = summarizer.apply(oldest);
            messages.subList(0, end).clear();
            messages.add(0, ChatMessage.user("【历史摘要】" + summary));
            if (messages.size() <= compressThreshold || messages.size() <= compressWindow + 1) {
                break;
            }
        }
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

    private static String localSummarize(List<ChatMessage> window) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : window) {
            String role = "assistant".equals(m.role()) ? "答" : "问";
            String content = m.content() == null ? "" : m.content();
            String brief = content.length() > 80 ? content.substring(0, 80) + "…" : content;
            sb.append(role).append("：").append(brief).append("；");
        }
        return sb.toString();
    }
}

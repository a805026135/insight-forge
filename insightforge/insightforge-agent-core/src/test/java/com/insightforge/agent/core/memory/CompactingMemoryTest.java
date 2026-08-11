package com.insightforge.agent.core.memory;

import com.insightforge.agent.core.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactingMemoryTest {

    @Test
    void compressesOldestWindowIntoSummaryAndKeepsRecent() {
        CompactingMemory mem = new CompactingMemory(10, 6, 4);
        for (int i = 1; i <= 8; i++) {
            mem.add(ChatMessage.user("问题" + i));
            mem.add(ChatMessage.assistant("回答" + i));
        }
        assertTrue(mem.size() <= 6, "压缩后应远小于原始条数，实际=" + mem.size());
        assertTrue(mem.messages().stream().anyMatch(m -> m.content().startsWith("【历史摘要】")));
        assertTrue(mem.messages().stream().anyMatch(m -> m.content().contains("问题8")),
                "最近消息必须保留");
    }

    @Test
    void customSummarizerIsInvoked() {
        AtomicInteger calls = new AtomicInteger();
        CompactingMemory mem = new CompactingMemory(10, 5, 3,
                msgs -> {
                    calls.incrementAndGet();
                    return "CUSTOM_SUMMARY";
                });
        for (int i = 1; i <= 6; i++) {
            mem.add(ChatMessage.user("q" + i));
        }
        assertTrue(calls.get() >= 1, "摘要器应被调用");
        assertTrue(mem.messages().stream().anyMatch(m -> m.content().contains("CUSTOM_SUMMARY")));
    }

    @Test
    void neverExceedsHardCap() {
        CompactingMemory mem = new CompactingMemory(8, 6, 4);
        for (int i = 0; i < 30; i++) {
            mem.add(ChatMessage.user("x" + i));
        }
        assertTrue(mem.size() <= 8, "硬顶 cap 必须生效，实际=" + mem.size());
    }
}

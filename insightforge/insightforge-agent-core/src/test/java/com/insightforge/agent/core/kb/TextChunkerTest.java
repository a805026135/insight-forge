package com.insightforge.agent.core.kb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void splitsLongTextIntoBoundedChunks() {
        String text = IntStream.range(0, 20)
                .mapToObj(i -> "这是第" + i + "段商品说明内容，用于测试切块。")
                .collect(Collectors.joining("\n\n"));
        List<String> chunks = TextChunker.chunk(text, 80, 10);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 80));
        assertTrue(String.join("", chunks).contains("第5段商品说明内容"));
    }

    @Test
    void keepsShortParagraphAsOneChunk() {
        List<String> chunks = TextChunker.chunk("秒杀活动商品必须真实在售。", 400, 50);
        assertEquals(1, chunks.size());
    }

    @Test
    void emptyAndNullReturnEmpty() {
        assertTrue(TextChunker.chunk("", 100, 10).isEmpty());
        assertTrue(TextChunker.chunk(null, 100, 10).isEmpty());
    }
}

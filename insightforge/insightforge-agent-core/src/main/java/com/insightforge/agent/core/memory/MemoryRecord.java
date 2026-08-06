package com.insightforge.agent.core.memory;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一条长期记忆（向量化前的原始记录）。
 * 生产环境会把它 Embedding 后写入 Milvus；本地桩直接按文本关键词检索。
 */
public record MemoryRecord(String id, String text, Map<String, Object> metadata, Long activityId, LocalDateTime createdAt) {

    public static MemoryRecord of(String id, String text, Map<String, Object> metadata, Long activityId) {
        return new MemoryRecord(id, text, metadata, activityId, LocalDateTime.now());
    }
}

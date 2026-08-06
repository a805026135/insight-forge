package com.insightforge.agent.core.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 长期记忆（生产实现，当前为桩）。
 *
 * 诚实边界：本项目未引入 Milvus 依赖与向量写入管线，故此处直接抛 UnsupportedOperationException，
 * 明确表示「这是生产才接的能力」——绝不假装已接入向量库。
 * 接入时：save 里做 Embedding（Spring AI EmbeddingClient）写 Milvus；search 做向量相似度召回。
 * 接口与 {@link LocalMemoryStore} 完全一致，运行时无感切换。
 */
@Component
@ConditionalOnProperty(name = "insightforge.agent.memory.milvus-enabled", havingValue = "true")
public class MilvusMemoryStore implements MemoryStore {

    @Override
    public void save(MemoryRecord record) {
        throw new UnsupportedOperationException(
                "Milvus 向量库未接入：需部署 Milvus 并用 EmbeddingClient 写入向量（Phase 7 桩，生产通过 insightforge.agent.memory.milvus-enabled=true 启用）");
    }

    @Override
    public List<MemoryRecord> search(String query, int topK) {
        throw new UnsupportedOperationException(
                "Milvus 向量库未接入：需部署 Milvus 并做向量相似度召回（Phase 7 桩）");
    }
}

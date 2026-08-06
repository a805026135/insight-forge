package com.insightforge.agent.core.memory;

import java.util.List;

/**
 * 长期记忆存储抽象（设计文档 §5.2 长期记忆）。
 * 实现：{@link LocalMemoryStore}（本地内存，默认）/ {@link MilvusMemoryStore}（向量库，生产启用）。
 */
public interface MemoryStore {

    /** 写入一条记忆（生产会同步 Embedding 入 Milvus）。 */
    void save(MemoryRecord record);

    /** 按语义/关键词召回 topK 条相似记忆（规划期参考、相似活动复用）。 */
    List<MemoryRecord> search(String query, int topK);
}

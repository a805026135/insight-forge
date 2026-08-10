package com.insightforge.agent.core.kb;

/**
 * 文本向量化抽象（Phase 19）：RAG 的 EmbeddingModel 一层。
 * 生产用 DashScope text-embedding-v4（Spring AI OpenAiEmbeddingModel 走
 * 阿里云百炼 OpenAI 兼容端点）；无 key 时自动降级为本地词法向量
 * （确定性、零网络），保证全链路在 2C4G 上可跑通。
 * 所有实现均输出 L2 归一化向量。
 */
public interface EmbeddingProvider {

    /** 提供方标识，写入 stats 便于前端/排查显示 */
    String name();

    int dimensions();

    float[] embed(String text);
}

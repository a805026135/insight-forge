package com.insightforge.agent.core.kb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库装配（Phase 19）：
 *  - embedding.provider=auto 且配置了 api-key → DashScope text-embedding-v4
 *  - 否则 → 本地词法向量兜底（离线可跑，key 一到即切换，代码零改动）
 * 向量存储：自研 MySQL 持久化 + 进程内归一化余弦索引（2C4G 上零新增服务），
 * 通过 Spring AI VectorStore 抽象可平滑替换 Redis/PGVector/Milvus。
 */
@Configuration
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
public class KnowledgeBaseConfig {

    @Bean
    public EmbeddingProvider embeddingProvider(
            @Value("${insightforge.agent.embedding.provider:auto}") String provider,
            @Value("${insightforge.agent.embedding.api-key:}") String apiKey,
            @Value("${insightforge.agent.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${insightforge.agent.embedding.model:text-embedding-v4}") String model,
            @Value("${insightforge.agent.embedding.dimensions:1024}") int dimensions,
            @Value("${insightforge.agent.embedding.local-dimensions:512}") int localDimensions) {
        boolean useDashScope = "dashscope".equalsIgnoreCase(provider)
                || ("auto".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank());
        if (useDashScope) {
            return new DashScopeEmbeddingProvider(apiKey, baseUrl, model, dimensions);
        }
        return new LocalLexicalEmbeddingProvider(localDimensions);
    }
}

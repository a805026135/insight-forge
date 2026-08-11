package com.insightforge.agent.core.kb;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库装配（Phase 19 / Milvus）：
 *  - embedding.provider=auto 且配置了 api-key → DashScope（qwen3.7-text-embedding）；
 *    否则 → 本地词法向量兜底（离线可跑，本地/测试用）。
 *  - vectorstore.type=milvus → Spring AI MilvusVectorStore（生产）；
 *    type=simple → SimpleVectorStore（本地/测试，零外部依赖）。
 *    同一套 VectorStore 抽象，切换只改配置。
 *  - Milvus collection 维度在启动时用真实 embedding 探测得到，避免模型维度写死。
 */
@Configuration
@EnableConfigurationProperties({KnowledgeBaseProperties.class, VectorStoreProperties.class})
public class KnowledgeBaseConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseConfig.class);

    @Bean
    public EmbeddingProvider embeddingProvider(
            @Value("${insightforge.agent.embedding.provider:auto}") String provider,
            @Value("${insightforge.agent.embedding.api-key:}") String apiKey,
            @Value("${insightforge.agent.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${insightforge.agent.embedding.model:qwen3.7-text-embedding}") String model,
            @Value("${insightforge.agent.embedding.dimensions:1024}") int dimensions,
            @Value("${insightforge.agent.embedding.local-dimensions:512}") int localDimensions) {
        boolean useDashScope = "dashscope".equalsIgnoreCase(provider)
                || ("auto".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank());
        if (useDashScope) {
            return new DashScopeEmbeddingProvider(apiKey, baseUrl, model, dimensions);
        }
        return new LocalLexicalEmbeddingProvider(localDimensions);
    }

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingProvider provider) {
        return new SpringAiEmbeddingModel(provider);
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, EmbeddingProvider provider,
                                   VectorStoreProperties props) {
        if ("milvus".equalsIgnoreCase(props.getType())) {
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(props.getMilvusHost())
                    .withPort(props.getMilvusPort())
                    .build();
            MilvusServiceClient client = new MilvusServiceClient(connectParam);
            int dims = probeDimensions(provider);
            MilvusVectorStore store = MilvusVectorStore.builder(client, embeddingModel)
                    .collectionName(props.getMilvusCollection())
                    .databaseName(props.getMilvusDatabase())
                    .embeddingDimension(dims)
                    .metricType(MetricType.COSINE)
                    .indexType(IndexType.AUTOINDEX)
                    .initializeSchema(true)
                    .build();
            log.info("知识库向量库已启用 Milvus：{}:{}/{}，embedding={}({}d)",
                    props.getMilvusHost(), props.getMilvusPort(),
                    props.getMilvusCollection(), provider.name(), dims);
            return store;
        }
        log.info("知识库向量库使用进程内 SimpleVectorStore（本地/测试，零外部依赖）");
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    private int probeDimensions(EmbeddingProvider provider) {
        try {
            float[] v = provider.embed("知识库向量维度探测");
            if (v == null || v.length == 0) {
                throw new IllegalStateException("embedding 探测返回空向量");
            }
            return v.length;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法探测 embedding 维度（provider=" + provider.name()
                            + "）：" + e.getMessage()
                            + "；请检查 INSIGHTFORGE_AGENT_EMBEDDING_API_KEY 与模型名是否有效", e);
        }
    }
}

package com.insightforge.agent.core.kb;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import java.util.List;

/**
 * DashScope text-embedding-v4（生产 embedding，Phase 19）。
 * 通过 Spring AI 的 OpenAiEmbeddingModel 走阿里云百炼 OpenAI 兼容端点，
 * 与 DeepSeekModelClient 同一套“仅用 Spring AI 接模型”的约定。
 * 需配置 INSIGHTFORGE_AGENT_EMBEDDING_API_KEY（百炼 API Key）才启用；
 * 缺 key 时由 KnowledgeBaseConfig 自动降级到本地词法向量。
 */
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private final OpenAiEmbeddingModel model;
    private final String modelName;
    private final int dimensions;

    public DashScopeEmbeddingProvider(String apiKey, String baseUrl, String modelName, int dimensions) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        this.model = new OpenAiEmbeddingModel(api);
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    @Override
    public String name() {
        return "dashscope:" + modelName;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(modelName)
                .dimensions(dimensions)
                .build();
        EmbeddingResponse resp = model.call(new EmbeddingRequest(List.of(text), options));
        Embedding emb = resp.getResult();
        return Vectors.normalize(emb.getOutput());
    }
}

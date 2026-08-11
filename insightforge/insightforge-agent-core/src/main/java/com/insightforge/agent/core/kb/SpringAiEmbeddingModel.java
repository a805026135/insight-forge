package com.insightforge.agent.core.kb;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 把自研 {@link EmbeddingProvider} 适配成 Spring AI 的 {@link EmbeddingModel}，
 * 供 VectorStore（Milvus / SimpleVectorStore）内部统一做向量化。
 * 与项目"自研 Agent 运行时，仅用 Spring AI 接模型"的约定一致。
 */
public class SpringAiEmbeddingModel implements EmbeddingModel {

    private final EmbeddingProvider delegate;

    public SpringAiEmbeddingModel(EmbeddingProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;
        for (String instruction : request.getInstructions()) {
            embeddings.add(new Embedding(delegate.embed(instruction), index++));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}

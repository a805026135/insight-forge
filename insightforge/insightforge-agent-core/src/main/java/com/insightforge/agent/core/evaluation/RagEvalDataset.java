package com.insightforge.agent.core.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/** 可版本控制的 RAG 离线基准集。 */
public final class RagEvalDataset {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RagEvalDataset() {}

    public record Item(String id, String category, String question, long merchantId,
                       List<String> expectedDocKeys, List<String> expectedKeywords,
                       boolean answerable) {}

    public static List<Item> load() throws Exception {
        try (InputStream in = RagEvalDataset.class.getClassLoader()
                .getResourceAsStream("evaluation/rag-eval-dataset.json")) {
            if (in == null) throw new IllegalStateException("RAG 评测集未找到");
            return MAPPER.readValue(in, new TypeReference<>() {});
        }
    }
}

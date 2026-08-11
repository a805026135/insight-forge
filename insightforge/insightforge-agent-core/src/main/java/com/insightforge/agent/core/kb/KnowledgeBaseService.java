package com.insightforge.agent.core.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AgentKbChunk;
import com.insightforge.persistence.entity.AgentKbDocument;
import com.insightforge.persistence.mapper.AgentKbChunkMapper;
import com.insightforge.persistence.mapper.AgentKbDocumentMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家知识库（RAG 核心，Phase 19 / Milvus）。
 *
 * 设计：
 *  - 文档（agent_kb_document）按 doc_key 幂等重入库，版本 +1；
 *  - 切块（agent_kb_chunk）落 MySQL（记录 chunkId 作为向量库主键，兼作审计）；
 *  - 向量与正文进 VectorStore：生产 Milvus（COSINE + AUTOINDEX），本地/测试 SimpleVectorStore；
 *  - 商家隔离：平台知识（merchantId=0）所有商家可见，商家知识仅归属商家可见。
 *    检索取回候选后在 Java 侧按 merchantId 强制过滤（两种向量库行为一致，绝不越权）；
 *    生产数据量大时可把过滤下推到 Milvus（SearchRequest.filterExpression），
 *    Spring AI 抽象已支持，属后续优化点。
 */
@Service
public class KnowledgeBaseService {

    private final AgentKbDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeBaseProperties properties;

    public KnowledgeBaseService(AgentKbDocumentMapper documentMapper,
                                AgentKbChunkMapper chunkMapper,
                                VectorStore vectorStore,
                                EmbeddingProvider embeddingProvider,
                                KnowledgeBaseProperties properties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
    }

    /**
     * 幂等入库：删除 doc_key 旧块（MySQL + 向量库）→ 切块 → 插入新块 →
     * 向量化写入向量库 → 文档版本 +1。
     */
    @Transactional
    public int ingestText(String docKey, String title, String sourceType, Long merchantId, String text) {
        List<String> chunks = TextChunker.chunk(text, properties.getMaxChunkChars(), properties.getOverlapChars());
        List<AgentKbChunk> oldChunks = chunkMapper.selectList(
                new QueryWrapper<AgentKbChunk>().eq("doc_key", docKey));
        if (!oldChunks.isEmpty()) {
            vectorStore.delete(oldChunks.stream().map(c -> String.valueOf(c.getId())).toList());
        }
        chunkMapper.delete(new QueryWrapper<AgentKbChunk>().eq("doc_key", docKey));

        AgentKbDocument doc = documentMapper.selectOne(
                new QueryWrapper<AgentKbDocument>().eq("doc_key", docKey));
        int version = (doc == null || doc.getVersion() == null) ? 1 : doc.getVersion() + 1;

        List<AgentKbChunk> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (String c : chunks) {
            AgentKbChunk row = new AgentKbChunk();
            row.setDocKey(docKey);
            row.setMerchantId(merchantId);
            row.setTitle(title);
            row.setSourceType(sourceType);
            row.setContent(c);
            row.setCreatedAt(now);
            rows.add(row);
        }
        rows.forEach(chunkMapper::insert);

        long merchantVisible = merchantId == null ? 0L : merchantId;
        List<Document> documents = new ArrayList<>();
        for (AgentKbChunk row : rows) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("docKey", docKey);
            metadata.put("title", title == null ? "" : title);
            metadata.put("sourceType", sourceType == null ? "" : sourceType);
            metadata.put("merchantId", merchantVisible);
            documents.add(new Document(String.valueOf(row.getId()), row.getContent(), metadata));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }

        if (doc == null) {
            AgentKbDocument created = new AgentKbDocument();
            created.setDocKey(docKey);
            created.setTitle(title);
            created.setSourceType(sourceType);
            created.setMerchantId(merchantId);
            created.setVersion(version);
            created.setChunkCount(rows.size());
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            documentMapper.insert(created);
            for (AgentKbChunk row : rows) {
                row.setDocumentId(created.getId());
                chunkMapper.updateById(row);
            }
        } else {
            doc.setTitle(title);
            doc.setSourceType(sourceType);
            doc.setMerchantId(merchantId);
            doc.setVersion(version);
            doc.setChunkCount(rows.size());
            doc.setUpdatedAt(now);
            documentMapper.updateById(doc);
        }
        return rows.size();
    }

    /** 检索：平台知识 + 本商家知识，相似度 topK。商家隔离在 Java 侧强制执行。 */
    public List<KbSearchHit> search(long merchantId, String query, int topK) {
        return search(merchantId, query, topK, null);
    }

    /**
     * 检索：平台知识 + 本商家知识，可按来源类型（PLAYBOOK/PRODUCT/DECISION/ACTIVITY/FAQ/BUYER_POLICY）过滤。
     * 商家隔离在 Java 侧强制执行。
     */
    public List<KbSearchHit> search(long merchantId, String query, int topK, String sourceType) {
        if (!properties.isEnabled() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        int fetchK = Math.max(24, topK * 8);
        List<Document> candidates = fetchCandidates(query, fetchK);
        Map<String, KbSearchHit> hits = new LinkedHashMap<>();
        for (Document d : candidates) {
            if (hits.size() >= topK) {
                break;
            }
            if (!visibleTo(d, merchantId)) {
                continue;
            }
            if (sourceType != null && !sourceType.isBlank()
                    && !sourceType.equals(str(d.getMetadata().get("sourceType")))) {
                continue;
            }
            hits.putIfAbsent(d.getId(), toHit(d));
        }
        return List.copyOf(hits.values());
    }

    private List<Document> fetchCandidates(String query, int fetchK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(fetchK)
                .similarityThresholdAll()
                .build());
    }

    private boolean visibleTo(Document d, long merchantId) {
        Object raw = d.getMetadata().get("merchantId");
        long mid = raw instanceof Number n ? n.longValue() : 0L;
        return mid == 0L || mid == merchantId;
    }

    private KbSearchHit toHit(Document d) {
        Map<String, Object> m = d.getMetadata();
        return new KbSearchHit(
                str(m.get("docKey")),
                str(m.get("title")),
                d.getText(),
                str(m.get("sourceType")),
                d.getScore() == null ? 0.0 : d.getScore());
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    public boolean exists(String docKey) {
        return documentMapper.selectCount(new QueryWrapper<AgentKbDocument>().eq("doc_key", docKey)) > 0;
    }

    public long chunkCount() {
        return chunkMapper.selectCount(new QueryWrapper<>());
    }

    public long documentCount() {
        return documentMapper.selectCount(new QueryWrapper<>());
    }

    public String providerName() {
        return embeddingProvider.name();
    }

    public int providerDimensions() {
        return embeddingProvider.dimensions();
    }

    public String vectorStoreName() {
        return vectorStore.getClass().getSimpleName();
    }

    public KnowledgeBaseProperties properties() {
        return properties;
    }
}

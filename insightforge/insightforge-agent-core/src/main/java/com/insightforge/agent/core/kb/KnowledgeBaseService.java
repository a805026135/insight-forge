package com.insightforge.agent.core.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AgentKbChunk;
import com.insightforge.persistence.entity.AgentKbDocument;
import com.insightforge.persistence.entity.AgentKbDocumentVersion;
import com.insightforge.persistence.mapper.AgentKbChunkMapper;
import com.insightforge.persistence.mapper.AgentKbDocumentMapper;
import com.insightforge.persistence.mapper.AgentKbDocumentVersionMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final AgentKbDocumentVersionMapper versionMapper;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeBaseProperties properties;

    public KnowledgeBaseService(AgentKbDocumentMapper documentMapper,
                                AgentKbChunkMapper chunkMapper,
                                AgentKbDocumentVersionMapper versionMapper,
                                VectorStore vectorStore,
                                EmbeddingProvider embeddingProvider,
                                KnowledgeBaseProperties properties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.versionMapper = versionMapper;
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
        String checksum = sha256(text);

        AgentKbDocumentVersion existingVersion = versionMapper.selectOne(
                new QueryWrapper<AgentKbDocumentVersion>().eq("doc_key", docKey)
                        .orderByDesc("version").last("LIMIT 1"));
        if (existingVersion != null && doc != null && checksum.equals(existingVersion.getChecksum())) {
            return doc.getChunkCount() == null ? chunks.size() : doc.getChunkCount();
        }

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
        AgentKbDocumentVersion snapshot = new AgentKbDocumentVersion();
        snapshot.setDocKey(docKey);
        snapshot.setVersion(version);
        snapshot.setTitle(title);
        snapshot.setSourceType(sourceType);
        snapshot.setMerchantId(merchantId);
        snapshot.setContent(text);
        snapshot.setChecksum(checksum);
        snapshot.setCreatedAt(now);
        versionMapper.insert(snapshot);
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
        return properties.isHybridEnabled()
                ? searchHybrid(merchantId, query, topK, sourceType)
                : searchDense(merchantId, query, topK, sourceType);
    }

    /** 仅稠密向量检索，保留为消融实验基线和混合检索的第一路召回。 */
    public List<KbSearchHit> searchDense(long merchantId, String query, int topK, String sourceType) {
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
            Double score = d.getScore();
            if (score != null && score < properties.getMinScore()) {
                continue;
            }
            hits.putIfAbsent(d.getId(), toHit(d));
        }
        return List.copyOf(hits.values());
    }

    /**
     * 轻量混合检索：向量召回负责语义近似，字符二元组/精确词召回负责型号、数字和专有名词，
     * 最后用 RRF 融合两个排名。当前知识库规模小，词法候选直接来自切块表；数据量增长后应把
     * 词法通道替换为 Elasticsearch/OpenSearch BM25，RRF 接口和评测口径无需变化。
     */
    public List<KbSearchHit> searchHybrid(long merchantId, String query, int topK, String sourceType) {
        if (!properties.isEnabled() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        int candidateK = Math.max(24, topK * 8);
        List<KbSearchHit> dense = searchDense(merchantId, query, candidateK, sourceType);
        List<KbSearchHit> lexical = lexicalCandidates(merchantId, query, candidateK, sourceType);

        Map<String, Double> rrf = new HashMap<>();
        Map<String, KbSearchHit> byKey = new LinkedHashMap<>();
        addRrfRanking(dense, rrf, byKey);
        addRrfRanking(lexical, rrf, byKey);
        return byKey.entrySet().stream()
                .sorted((a, b) -> Double.compare(rrf.get(b.getKey()), rrf.get(a.getKey())))
                .limit(topK)
                .map(e -> {
                    KbSearchHit h = e.getValue();
                    return new KbSearchHit(h.docKey(), h.title(), h.content(), h.sourceType(), rrf.get(e.getKey()));
                })
                .toList();
    }

    private List<KbSearchHit> lexicalCandidates(long merchantId, String query, int limit, String sourceType) {
        Set<String> queryTerms = lexicalTerms(query);
        if (queryTerms.isEmpty()) return List.of();
        return chunkMapper.selectList(new QueryWrapper<AgentKbChunk>()
                        .and(w -> w.isNull("merchant_id").or().eq("merchant_id", 0L)
                                .or().eq("merchant_id", merchantId)))
                .stream()
                .filter(c -> sourceType == null || sourceType.isBlank() || sourceType.equals(c.getSourceType()))
                .map(c -> Map.entry(c, lexicalScore(queryTerms, c.getTitle() + " " + c.getContent())))
                .filter(e -> e.getValue() > 0d)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> new KbSearchHit(e.getKey().getDocKey(), e.getKey().getTitle(),
                        e.getKey().getContent(), e.getKey().getSourceType(), e.getValue()))
                .toList();
    }

    private double lexicalScore(Set<String> queryTerms, String candidate) {
        Set<String> candidateTerms = lexicalTerms(candidate);
        long overlap = queryTerms.stream().filter(candidateTerms::contains).count();
        return overlap == 0 ? 0d : overlap / Math.sqrt((double) queryTerms.size() * candidateTerms.size());
    }

    private Set<String> lexicalTerms(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ").trim();
        Set<String> terms = new HashSet<>();
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) continue;
            terms.add(part);
            for (int i = 0; i + 2 <= part.length(); i++) terms.add(part.substring(i, i + 2));
        }
        return terms;
    }

    private void addRrfRanking(List<KbSearchHit> ranking, Map<String, Double> scores,
                               Map<String, KbSearchHit> hits) {
        for (int i = 0; i < ranking.size(); i++) {
            KbSearchHit hit = ranking.get(i);
            String key = hit.docKey() + "\u0000" + hit.content();
            hits.putIfAbsent(key, hit);
            scores.merge(key, 1d / (60d + i + 1), Double::sum);
        }
    }

    private List<Document> fetchCandidates(String query, int fetchK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(fetchK)
                .similarityThresholdAll()
                .build());
    }

    /**
     * 检索指定商家的"公开知识"（商品目录/活动规则），供买家客服代理回答商品问题。
     * 商家私有知识（自定义文档/决策/运营手册）不会出现在这里。
     */
    public List<KbSearchHit> searchMerchantPublic(long merchantId, String query, int topK) {
        return searchMerchantPublic(merchantId, query, topK, properties.getMinScore());
    }

    /** 指定置信度门槛的商家公开知识检索（锁定商家后可放宽，用户已确认商家）。 */
    public List<KbSearchHit> searchMerchantPublic(long merchantId, String query, int topK, double minScore) {
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
            if (merchantOf(d) != merchantId) {
                continue;
            }
            Double score = d.getScore();
            if (score != null && score < minScore) {
                continue;
            }
            String st = str(d.getMetadata().get("sourceType"));
            if (!("PRODUCT".equals(st) || "ACTIVITY".equals(st) || "STORE_INFO".equals(st))) {
                continue;
            }
            hits.putIfAbsent(d.getId(), toHit(d));
        }
        return List.copyOf(hits.values());
    }

    private boolean visibleTo(Document d, long merchantId) {
        long mid = merchantOf(d);
        return mid == 0L || mid == merchantId;
    }

    private long merchantOf(Document d) {
        Object raw = d.getMetadata().get("merchantId");
        return raw instanceof Number n ? n.longValue() : 0L;
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

    /** 删除一个文档（自定义知识删除入口）：清理向量库向量 + 切块 + 文档行。 */
    @Transactional
    public void deleteDoc(String docKey) {
        List<AgentKbChunk> chunks = chunkMapper.selectList(
                new QueryWrapper<AgentKbChunk>().eq("doc_key", docKey));
        if (!chunks.isEmpty()) {
            vectorStore.delete(chunks.stream().map(c -> String.valueOf(c.getId())).toList());
        }
        chunkMapper.delete(new QueryWrapper<AgentKbChunk>().eq("doc_key", docKey));
        documentMapper.delete(new QueryWrapper<AgentKbDocument>().eq("doc_key", docKey));
    }

    public long chunkCount() {
        return chunkMapper.selectCount(new QueryWrapper<>());
    }

    public long documentCount() {
        return documentMapper.selectCount(new QueryWrapper<>());
    }

    /** 当前商家可见的文档列表（平台级 + 本商家），供管理页展示。 */
    public List<AgentKbDocument> listVisibleDocuments(long merchantId) {
        return documentMapper.selectList(new QueryWrapper<AgentKbDocument>()
                .and(w -> w.isNull("merchant_id").or().eq("merchant_id", merchantId))
                .orderByAsc("id"));
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

    private String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("知识库版本校验失败", e);
        }
    }
}

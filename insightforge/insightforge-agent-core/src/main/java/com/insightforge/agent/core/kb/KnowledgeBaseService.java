package com.insightforge.agent.core.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AgentKbChunk;
import com.insightforge.persistence.entity.AgentKbDocument;
import com.insightforge.persistence.mapper.AgentKbChunkMapper;
import com.insightforge.persistence.mapper.AgentKbDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 商家知识库（RAG 核心，Phase 19）。
 *
 * 设计：
 *  - 文档（agent_kb_document）按 doc_key 幂等重入库，版本 +1；
 *  - 切块（agent_kb_chunk）保存正文 + 归一化向量（JSON，MySQL TEXT）；
 *  - 进程内维护不可变索引快照，search 走余弦相似度，零外部服务；
 *  - merchant_id 隔离：平台知识（NULL）所有商家可见，商家知识仅归属商家可见。
 *
 * 生产切换：EmbeddingProvider 换成 DashScope 只需配置；向量库如需
 * Redis/PGVector/Milvus，替换本类索引实现即可（Spring AI VectorStore 抽象可复用）。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final AgentKbDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeBaseProperties properties;

    private volatile List<IndexedChunk> index = List.of();

    public KnowledgeBaseService(AgentKbDocumentMapper documentMapper,
                                AgentKbChunkMapper chunkMapper,
                                EmbeddingProvider embeddingProvider,
                                KnowledgeBaseProperties properties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
    }

    /**
     * 幂等入库：删除 doc_key 旧块 → 切块 → 向量化 → 插入 → 重建内存索引。
     * 重复调用同一 doc_key 会覆盖旧版本（文档版本号 +1）。
     */
    @Transactional
    public int ingestText(String docKey, String title, String sourceType, Long merchantId, String text) {
        List<String> chunks = TextChunker.chunk(text, properties.getMaxChunkChars(), properties.getOverlapChars());
        chunkMapper.delete(new QueryWrapper<AgentKbChunk>().eq("doc_key", docKey));

        AgentKbDocument doc = documentMapper.selectOne(
                new QueryWrapper<AgentKbDocument>().eq("doc_key", docKey));
        int version = (doc == null || doc.getVersion() == null) ? 1 : doc.getVersion() + 1;

        List<AgentKbChunk> rows = new ArrayList<>();
        for (String c : chunks) {
            float[] vec = embeddingProvider.embed(c);
            AgentKbChunk row = new AgentKbChunk();
            row.setDocKey(docKey);
            row.setMerchantId(merchantId);
            row.setTitle(title);
            row.setSourceType(sourceType);
            row.setContent(c);
            row.setEmbedding(Vectors.toJson(vec));
            row.setDimension(vec.length);
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        rows.forEach(chunkMapper::insert);

        LocalDateTime now = LocalDateTime.now();
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
        reloadIndex();
        return rows.size();
    }

    /** 检索：平台知识 + 本商家知识，按余弦相似度取 topK。 */
    public List<KbSearchHit> search(long merchantId, String query, int topK) {
        if (!properties.isEnabled() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        float[] q = embeddingProvider.embed(query);
        int dim = q.length;
        int limit = Math.max(1, topK);
        return index.stream()
                .filter(ic -> ic.dimension() == dim)
                .filter(ic -> ic.merchantId() == null || ic.merchantId() == merchantId)
                .map(ic -> new ScoredHit(ic, Vectors.cosine(ic.vector(), q)))
                .filter(s -> s.score() > 0.01)
                .sorted(Comparator.comparingDouble(ScoredHit::score).reversed())
                .limit(limit)
                .map(s -> new KbSearchHit(s.hit().docKey(), s.hit().title(),
                        s.hit().content(), s.hit().sourceType(), s.score()))
                .toList();
    }

    /** 从 MySQL 全量加载并重建内存索引（启动 / 入库后调用）。 */
    public void reloadIndex() {
        List<AgentKbChunk> rows = chunkMapper.selectList(new QueryWrapper<>());
        List<IndexedChunk> rebuilt = new ArrayList<>();
        for (AgentKbChunk row : rows) {
            try {
                float[] vec = Vectors.fromJson(row.getEmbedding());
                rebuilt.add(new IndexedChunk(row.getDocKey(), row.getTitle(), row.getContent(),
                        row.getSourceType(), row.getMerchantId(), vec, row.getDimension()));
            } catch (Exception e) {
                log.warn("知识库切块向量解析失败，跳过 id={} doc={}", row.getId(), row.getDocKey());
            }
        }
        this.index = rebuilt;
        log.info("知识库索引重建完成：{} 条切块，embedding={}({}d)",
                rebuilt.size(), embeddingProvider.name(), embeddingProvider.dimensions());
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

    public KnowledgeBaseProperties properties() {
        return properties;
    }

    private record IndexedChunk(String docKey, String title, String content,
                                String sourceType, Long merchantId, float[] vector, Integer dimension) {
    }

    private record ScoredHit(IndexedChunk hit, double score) {
    }
}

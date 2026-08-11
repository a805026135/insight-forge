package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import com.insightforge.persistence.entity.AgentKbCustomDoc;
import com.insightforge.persistence.mapper.AgentKbCustomDocMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商家自定义知识（Phase 19e）：上传自己的文档进 RAG 知识库。
 *  - 文档以 docKey = merchant/{mid}/custom/{uuid} 入库，sourceType=CUSTOM，商家级隔离；
 *  - 全文落 agent_kb_custom_doc（可展示/审计），切块与向量走既有 KnowledgeBaseService；
 *  - 删除时同时清理向量库、切块与全文。
 */
@Service
public class MerchantKbCustomService {

    private static final int MAX_CHARS = 100_000;

    private final KnowledgeBaseService kb;
    private final AgentKbCustomDocMapper customDocMapper;

    public MerchantKbCustomService(KnowledgeBaseService kb, AgentKbCustomDocMapper customDocMapper) {
        this.kb = kb;
        this.customDocMapper = customDocMapper;
    }

    public Map<String, Object> upload(long merchantId, String title, String content) {
        String t = title == null ? "" : title.trim();
        if (t.isBlank() || t.length() > 100) {
            throw new IllegalArgumentException("标题需为 1~100 字符");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (content.length() > MAX_CHARS) {
            throw new IllegalArgumentException("内容不能超过 10 万字符");
        }
        String docKey = "merchant/" + merchantId + "/custom/"
                + UUID.randomUUID().toString().replace("-", "");

        AgentKbCustomDoc doc = new AgentKbCustomDoc();
        doc.setMerchantId(merchantId);
        doc.setDocKey(docKey);
        doc.setTitle(t);
        doc.setContent(content);
        doc.setCreatedAt(LocalDateTime.now());
        customDocMapper.insert(doc);

        int chunks = kb.ingestText(docKey, t, "CUSTOM", merchantId, content);
        return Map.of("docKey", docKey, "chunks", chunks);
    }

    public void delete(long merchantId, String docKey) {
        if (docKey == null || docKey.isBlank()) {
            throw new IllegalArgumentException("缺少文档 docKey");
        }
        AgentKbCustomDoc doc = customDocMapper.selectOne(
                new QueryWrapper<AgentKbCustomDoc>().eq("doc_key", docKey));
        if (doc == null || doc.getMerchantId() == null || doc.getMerchantId() != merchantId) {
            throw new IllegalArgumentException("无权删除该文档或文档不存在");
        }
        kb.deleteDoc(docKey);
        customDocMapper.delete(new QueryWrapper<AgentKbCustomDoc>().eq("doc_key", docKey));
    }

    public List<AgentKbCustomDoc> list(long merchantId) {
        return customDocMapper.selectList(
                new QueryWrapper<AgentKbCustomDoc>().eq("merchant_id", merchantId).orderByDesc("id"));
    }
}

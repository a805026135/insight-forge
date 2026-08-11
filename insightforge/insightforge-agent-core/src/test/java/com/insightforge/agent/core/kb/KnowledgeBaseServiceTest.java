package com.insightforge.agent.core.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.AgentCoreTestConfig;
import com.insightforge.persistence.entity.AgentKbChunk;
import com.insightforge.persistence.entity.AgentKbDocument;
import com.insightforge.persistence.mapper.AgentKbChunkMapper;
import com.insightforge.persistence.mapper.AgentKbDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库（RAG）服务测试：真实 H2 + 真实 Mapper，验证
 * 入库/检索、商家隔离、同 docKey 幂等重入库（版本 +1、旧内容替换）。
 */
@SpringBootTest(classes = AgentCoreTestConfig.class)
@ActiveProfiles("test")
class KnowledgeBaseServiceTest {

    @Autowired
    private KnowledgeBaseService service;

    @Autowired
    private AgentKbDocumentMapper documentMapper;

    @Autowired
    private AgentKbChunkMapper chunkMapper;

    @Test
    void ingestAndSearchFindsRelevantChunk() {
        service.ingestText("test/ingest-search/playbook", "平台运营手册", "PLAYBOOK", null,
                "秒杀活动商品必须真实在售，售价必须高于成本价，秒杀价建议为市场价的5到8折。");
        List<KbSearchHit> hits = service.search(1L, "秒杀活动售价成本价", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> "test/ingest-search/playbook".equals(h.docKey())));
        assertEquals("local-lexical-512", service.providerName());
        assertTrue(service.vectorStoreName().contains("SimpleVectorStore"));
    }

    @Test
    void merchantIsolationKeepsOwnAndPlatformOnly() {
        service.ingestText("test/isolation/platform", "平台运营手册", "PLAYBOOK", null,
                "秒杀活动商品必须真实在售，售价必须高于成本价。");
        service.ingestText("test/isolation/a", "商家A商品", "PRODUCT", 1L,
                "无线蓝牙耳机 主动降噪 超长续航 高清通话");
        service.ingestText("test/isolation/b", "商家B商品", "PRODUCT", 2L,
                "家用跑步机 折叠免安装 静音减震 家庭健身");

        List<KbSearchHit> hits = service.search(1L, "无线蓝牙耳机 主动降噪 续航", 5);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> "test/isolation/a".equals(h.docKey())));
        assertTrue(hits.stream().noneMatch(h -> "test/isolation/b".equals(h.docKey())));
    }

    @Test
    void reingestSameDocKeyReplacesChunksAndBumpsVersion() {
        String docKey = "test/version/products";
        service.ingestText(docKey, "商家商品", "PRODUCT", 9L, "第一版商品说明内容只有一句。");
        String v2 = IntStream.range(1, 31)
                .mapToObj(i -> "第二版商品说明内容第" + i + "句。")
                .collect(Collectors.joining());
        service.ingestText(docKey, "商家商品", "PRODUCT", 9L, v2);

        AgentKbDocument doc = documentMapper.selectOne(
                new QueryWrapper<AgentKbDocument>().eq("doc_key", docKey));
        assertEquals(2, doc.getVersion());
        long chunkCount = chunkMapper.selectCount(
                new QueryWrapper<AgentKbChunk>().eq("doc_key", docKey));
        assertEquals(2, chunkCount);

        List<KbSearchHit> hits = service.search(9L, "第二版商品说明内容第二句", 5);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> h.content().contains("第二版")));
        assertTrue(hits.stream().noneMatch(h -> h.content().contains("第一版")));
    }

    @Test
    void blankQueryReturnsEmpty() {
        assertTrue(service.search(1L, "   ", 3).isEmpty());
    }
}

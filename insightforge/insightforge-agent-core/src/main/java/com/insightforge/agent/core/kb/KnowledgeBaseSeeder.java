package com.insightforge.agent.core.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AuditLog;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.AuditLogMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库种子（Phase 19）：应用启动时把四类内容幂等入库——
 * 平台运营手册、平台 FAQ（merchantId=NULL，所有商家可见）；
 * 每个商家的商品目录与历史决策（merchantId=商家，仅本人可见）。
 * 已有文档则跳过（用 /api/merchant/kb/reindex 手动刷新）。
 */
@Component
public class KnowledgeBaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final KnowledgeBaseService kb;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final AuditLogMapper auditLogMapper;

    public KnowledgeBaseSeeder(KnowledgeBaseService kb, MerchantMapper merchantMapper,
                               ProductMapper productMapper, AuditLogMapper auditLogMapper) {
        this.kb = kb;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!kb.properties().isEnabled() || !kb.properties().isSeedOnStartup()) {
            return;
        }
        try {
            seedPlatformDocs();
            seedAllMerchantDocs();
            log.info("知识库种子入库完成：{} 文档 / {} 切块，embedding={}",
                    kb.documentCount(), kb.chunkCount(), kb.providerName());
        } catch (Exception e) {
            log.warn("知识库种子入库失败（不影响主流程启动）：{}", e.getMessage());
        }
    }

    private void seedPlatformDocs() {
        ingestResource("platform/playbook", "平台运营手册", "PLAYBOOK", null, "kb/playbook.txt");
        ingestResource("platform/faq", "平台常见问题 FAQ", "FAQ", null, "kb/faq.txt");
    }

    private void seedAllMerchantDocs() {
        for (Merchant m : merchantMapper.selectList(new QueryWrapper<>())) {
            long mid = m.getId();
            if (!kb.exists(docKeyProducts(mid))) {
                seedMerchantProducts(mid);
            }
            if (!kb.exists(docKeyDecisions(mid))) {
                seedMerchantDecisions(mid);
            }
        }
    }

    /**
     * 手动刷新某个商家的知识库（商品目录 + 历史决策），返回各来源切块数。
     */
    @Transactional
    public Map<String, Integer> reindexMerchant(long merchantId) {
        int products = seedMerchantProducts(merchantId);
        int decisions = seedMerchantDecisions(merchantId);
        return Map.of("productChunks", products, "decisionChunks", decisions);
    }

    private int seedMerchantProducts(long merchantId) {
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("merchant_id", merchantId));
        if (products.isEmpty()) {
            return 0;
        }
        String text = products.stream()
                .map(p -> String.format("商品：%s；分类：%s；售价：%s；库存：%s；状态：%s；描述：%s",
                        p.getName(), nvl(p.getCategory()), p.getMarketPrice(),
                        p.getStock(), p.getStatus(), nvl(p.getDescription())))
                .collect(Collectors.joining("\n"));
        int n = kb.ingestText(docKeyProducts(merchantId), "商家商品目录", "PRODUCT", merchantId, text);
        log.info("商家#{} 商品知识入库 {} 块", merchantId, n);
        return n;
    }

    private int seedMerchantDecisions(long merchantId) {
        List<AuditLog> logs = auditLogMapper.selectList(
                new QueryWrapper<AuditLog>().like("detail", "商家#" + merchantId));
        if (logs.isEmpty()) {
            return 0;
        }
        String text = logs.stream()
                .map(l -> String.format("历史决策：%s（动作 %s，时间 %s）",
                        nvl(l.getDetail()), nvl(l.getActionType()), l.getCreatedAt()))
                .collect(Collectors.joining("\n"));
        int n = kb.ingestText(docKeyDecisions(merchantId), "商家历史决策", "DECISION", merchantId, text);
        log.info("商家#{} 决策知识入库 {} 块", merchantId, n);
        return n;
    }

    private void ingestResource(String docKey, String title, String sourceType, Long merchantId, String resource) {
        if (kb.exists(docKey)) {
            return;
        }
        try {
            ClassPathResource r = new ClassPathResource(resource);
            if (!r.exists()) {
                log.warn("知识库资源不存在：{}", resource);
                return;
            }
            String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int n = kb.ingestText(docKey, title, sourceType, merchantId, text);
            log.info("知识文档 {} 入库 {} 块", docKey, n);
        } catch (IOException e) {
            log.warn("知识库资源读取失败 {}：{}", resource, e.getMessage());
        }
    }

    private String docKeyProducts(long merchantId) {
        return "merchant/" + merchantId + "/products";
    }

    private String docKeyDecisions(long merchantId) {
        return "merchant/" + merchantId + "/decisions";
    }

    private String nvl(Object o) {
        return o == null ? "" : o.toString();
    }
}

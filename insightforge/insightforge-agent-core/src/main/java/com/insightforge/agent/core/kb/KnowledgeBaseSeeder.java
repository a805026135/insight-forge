package com.insightforge.agent.core.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.AuditLog;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库种子（Phase 19）：应用启动时把四类内容幂等入库——
 * 平台运营手册、平台 FAQ、买家售后政策（merchantId=0，所有商家可见）；
 * 每个商家的商品目录、历史决策、活动规则（merchantId=商家，仅本人可见）。
 *
 * 幂等策略：
 *  - 平台文档每次启动重入库（静态资源随代码发布，重入库成本低，保证最新）；
 *  - 商家文档每次启动重入库（MySQL 数据可能已变化：商品/决策/活动），
 *    ingestText 按 docKey 覆盖旧块，无需 exists 判断；
 *  - 运行时商品/动作变更由 {@link MerchantKbRefresher} 异步刷新对应商家。
 */
@Component
public class KnowledgeBaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final KnowledgeBaseService kb;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final AuditLogMapper auditLogMapper;
    private final ActivityMapper activityMapper;
    private final ActivitySkuMapper activitySkuMapper;

    public KnowledgeBaseSeeder(KnowledgeBaseService kb, MerchantMapper merchantMapper,
                               ProductMapper productMapper, AuditLogMapper auditLogMapper,
                               ActivityMapper activityMapper, ActivitySkuMapper activitySkuMapper) {
        this.kb = kb;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.auditLogMapper = auditLogMapper;
        this.activityMapper = activityMapper;
        this.activitySkuMapper = activitySkuMapper;
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
        ingestResource("platform/buyer-policy", "买家售后政策", "BUYER_POLICY", null, "kb/buyer-policy.txt");
    }

    private void seedAllMerchantDocs() {
        for (Merchant m : merchantMapper.selectList(new QueryWrapper<>())) {
            long mid = m.getId();
            seedMerchantProducts(mid);
            seedMerchantDecisions(mid);
            seedMerchantActivities(mid);
        }
    }

    /**
     * 手动/自动刷新某个商家的知识库（商品目录 + 历史决策 + 活动规则），返回各来源切块数。
     */
    @Transactional
    public Map<String, Integer> reindexMerchant(long merchantId) {
        int products = seedMerchantProducts(merchantId);
        int decisions = seedMerchantDecisions(merchantId);
        int activities = seedMerchantActivities(merchantId);
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("productChunks", products);
        result.put("decisionChunks", decisions);
        result.put("activityChunks", activities);
        return result;
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

    private int seedMerchantActivities(long merchantId) {
        List<ActivitySku> skus = activitySkuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("merchant_id", merchantId));
        if (skus.isEmpty()) {
            return 0;
        }
        Map<Long, List<ActivitySku>> byActivity = skus.stream()
                .collect(Collectors.groupingBy(s -> s.getActivityId() == null ? 0L : s.getActivityId(),
                        LinkedHashMap::new, Collectors.toList()));
        List<String> sections = new ArrayList<>();
        for (Map.Entry<Long, List<ActivitySku>> e : byActivity.entrySet()) {
            Activity a = activityMapper.selectById(e.getKey());
            String header = a == null ? "活动#" + e.getKey()
                    : String.format("活动：%s；状态：%s；时间：%s ~ %s",
                            a.getName(), nvl(a.getStatus()), a.getStartAt(), a.getEndAt());
            String body = e.getValue().stream()
                    .map(s -> String.format("参与商品：%s；秒杀价：%s；活动库存：%s；剩余：%s；已售：%s",
                            productName(s.getSkuId()), s.getSeckillPrice(),
                            s.getTotalStock(), s.getStock(), s.getSold()))
                    .collect(Collectors.joining("；"));
            sections.add(header + "。" + body);
        }
        int n = kb.ingestText(docKeyActivities(merchantId), "商家活动与促销规则", "ACTIVITY", merchantId,
                String.join("\n", sections));
        log.info("商家#{} 活动知识入库 {} 块", merchantId, n);
        return n;
    }

    private String productName(Long productId) {
        if (productId == null) {
            return "未知商品";
        }
        Product p = productMapper.selectById(productId);
        return p == null ? "商品#" + productId : p.getName();
    }

    private void ingestResource(String docKey, String title, String sourceType, Long merchantId, String resource) {
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

    private String docKeyActivities(long merchantId) {
        return "merchant/" + merchantId + "/activities";
    }

    private String nvl(Object o) {
        return o == null ? "" : o.toString();
    }
}

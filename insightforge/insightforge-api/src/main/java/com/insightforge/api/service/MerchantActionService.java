package com.insightforge.api.service;

import com.insightforge.common.result.ApiResult;
import com.insightforge.agent.core.kb.MerchantKbRefresher;
import com.insightforge.persistence.entity.AuditLog;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.AuditLogMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家 Agent 动作执行（Phase 18）：商家在前端批准后，由这里落地加库存/上下架/调价。
 * 所有操作先校验商品归属当前商家，并写入审计日志——LLM 只提建议，绝不直接改数据。
 */
@Service
public class MerchantActionService {

    private final ProductMapper productMapper;
    private final AuditLogMapper auditLogMapper;
    private final MerchantKbRefresher merchantKbRefresher;

    public MerchantActionService(ProductMapper productMapper, AuditLogMapper auditLogMapper,
                                 MerchantKbRefresher merchantKbRefresher) {
        this.productMapper = productMapper;
        this.auditLogMapper = auditLogMapper;
        this.merchantKbRefresher = merchantKbRefresher;
    }

    @Transactional
    public ApiResult<String> execute(long merchantId, String type, Long productId,
                                     Integer amount, BigDecimal price) {
        if (productId == null) {
            return ApiResult.fail(400, "缺少商品");
        }
        Product p = productMapper.selectById(productId);
        if (p == null || p.getMerchantId() == null || p.getMerchantId() != merchantId) {
            return ApiResult.fail(403, "无权操作该商品");
        }
        String detail;
        switch (type == null ? "" : type.toUpperCase()) {
            case "ADD_STOCK" -> {
                if (amount == null || amount <= 0) {
                    return ApiResult.fail(400, "库存增量必须为正数");
                }
                int cur = p.getStock() == null ? 0 : p.getStock();
                p.setStock(cur + amount);
                productMapper.updateById(p);
                detail = "增加库存 " + amount + " 件，当前库存 " + p.getStock();
            }
            case "TOGGLE_SHELF" -> {
                p.setStatus("ON_SALE".equals(p.getStatus()) ? "OFF_SHELF" : "ON_SALE");
                productMapper.updateById(p);
                detail = ("ON_SALE".equals(p.getStatus()) ? "上架" : "下架") + "成功";
            }
            case "REPRICE" -> {
                if (price == null || price.signum() <= 0) {
                    return ApiResult.fail(400, "价格必须为正数");
                }
                p.setMarketPrice(price);
                productMapper.updateById(p);
                detail = "市场价调整为 ¥" + price;
            }
            default -> {
                return ApiResult.fail(400, "未知动作类型: " + type);
            }
        }
        AuditLog log = new AuditLog();
        log.setActivityId(0L);
        log.setSkuId(productId);
        log.setActionType(type == null ? "" : type.toUpperCase());
        log.setAmount(amount);
        log.setDetail("商家#" + merchantId + " 批准执行：" + detail);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
        merchantKbRefresher.schedule(merchantId);
        return ApiResult.ok(detail);
    }
}

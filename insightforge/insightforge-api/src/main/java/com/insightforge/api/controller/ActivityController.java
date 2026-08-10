package com.insightforge.api.controller;

import com.insightforge.api.dto.CreateActivityRequest;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import com.insightforge.seckill.core.reconcile.ReconcileReport;
import com.insightforge.seckill.core.service.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 活动生命周期接入层（Phase 6 + 扩展）：状态机迁转 + 对账查看 + 创建活动。
 *
 * GET  /api/activity                  列出所有活动（给前端活动管理页）
 * POST /api/activity                  创建新活动（含 SKU 配置，状态初始化为 DRAFT）
 * POST /api/activity/{id}/arm         DRAFT → ARMED（前置 MySQL↔Redis 库存对账，不过不让上线）
 * POST /api/activity/{id}/live        ARMED → LIVE
 * POST /api/activity/{id}/settle      LIVE → SETTLING（结算对账：最终库存 = 初始 - 成功单）
 * POST /api/activity/{id}/close       SETTLING → CLOSED
 * GET  /api/activity/{id}/reconcile   查看两类对账（不改状态）
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService activityService;
    private final ActivityMapper activityMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final ProductMapper productMapper;

    public ActivityController(ActivityService activityService,
                              ActivityMapper activityMapper,
                              ActivitySkuMapper activitySkuMapper,
                              ProductMapper productMapper) {
        this.activityService = activityService;
        this.activityMapper = activityMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.productMapper = productMapper;
    }

    /** 列出所有活动（id 升序），给前端活动管理页用。 */
    @GetMapping
    public ApiResult<List<Activity>> list() {
        return ApiResult.ok(activityMapper.selectList(null));
    }

    /**
     * 创建新活动 + 挂载 SKU。活动初始状态为 DRAFT。
     * 每个 SKU 可关联已有商品（productId）或自动创建新商品。
     */
    @PostMapping
    public ApiResult<Activity> create(@RequestBody CreateActivityRequest req) {
        // 1. 创建活动
        Activity activity = new Activity();
        activity.setName(req.name());
        activity.setStatus("DRAFT");
        activity.setTargetGmv(req.targetGmv());
        activity.setTargetRoi(req.targetRoi());
        activity.setStartAt(req.startAt());
        activity.setEndAt(req.endAt());
        activityMapper.insert(activity);

        // 2. 逐个挂载 SKU
        if (req.skus() != null) {
            for (CreateActivityRequest.SkuConfig cfg : req.skus()) {
                Long productId = cfg.productId();
                // 自动创建新商品
                if (productId == null) {
                    Product p = new Product();
                    p.setName(cfg.productName());
                    p.setSku(cfg.skuCode());
                    p.setCostPrice(cfg.costPrice());
                    productMapper.insert(p);
                    productId = p.getId();
                }
                // 创建活动 SKU 行
                ActivitySku sku = new ActivitySku();
                sku.setActivityId(activity.getId());
                sku.setSkuId(productId);
                sku.setSeckillPrice(cfg.seckillPrice());
                sku.setTotalStock(cfg.totalStock());
                sku.setStock(cfg.totalStock());
                sku.setSold(0);
                sku.setVersion(0);
                activitySkuMapper.insert(sku);
            }
        }

        return ApiResult.ok(activity);
    }

    @PostMapping("/{id}/arm")
    public ApiResult<ReconcileReport> arm(@PathVariable long id) {
        return activityService.arm(id);
    }

    @PostMapping("/{id}/live")
    public ApiResult<Void> live(@PathVariable long id) {
        return activityService.goLive(id);
    }

    @PostMapping("/{id}/settle")
    public ApiResult<ReconcileReport> settle(@PathVariable long id) {
        return activityService.settle(id);
    }

    @PostMapping("/{id}/close")
    public ApiResult<Void> close(@PathVariable long id) {
        return activityService.close(id);
    }

    @GetMapping("/{id}/reconcile")
    public ApiResult<ReconcileReport> reconcile(@PathVariable long id) {
        return activityService.reconcile(id);
    }
}

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
import com.insightforge.api.dto.ReconcileSummary;
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
        // —— Phase 14.1 创建活动校验（防脏数据：空名/负数库存/低于成本价） ——
        if (req.name() == null || req.name().isBlank()) {
            return ApiResult.fail(400, "活动名称不能为空");
        }
        if (req.targetGmv() == null || req.targetGmv().signum() < 0) {
            return ApiResult.fail(400, "目标 GMV 必须 >= 0");
        }
        if (req.skus() == null || req.skus().isEmpty()) {
            return ApiResult.fail(400, "活动必须至少挂载一个 SKU");
        }
        for (CreateActivityRequest.SkuConfig cfg : req.skus()) {
            if (cfg.totalStock() == null || cfg.totalStock() <= 0) {
                return ApiResult.fail(400, "SKU 库存必须 > 0");
            }
            if (cfg.seckillPrice() == null || cfg.seckillPrice().signum() <= 0) {
                return ApiResult.fail(400, "SKU 秒杀价必须 > 0");
            }
            if (cfg.costPrice() != null && cfg.costPrice().signum() > 0
                    && cfg.seckillPrice().compareTo(cfg.costPrice()) < 0) {
                return ApiResult.fail(400, "SKU 秒杀价不得低于成本价");
            }
            if (cfg.productId() == null
                    && (cfg.productName() == null || cfg.productName().isBlank()
                        || cfg.skuCode() == null || cfg.skuCode().isBlank())) {
                return ApiResult.fail(400, "新建商品必须提供商品名与 SKU 编码");
            }
        }

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
    public ApiResult<ReconcileSummary> arm(@PathVariable long id) {
        var r = activityService.arm(id);
        return r.getData() == null ? ApiResult.fail(r.getCode(), r.getMessage())
                : ApiResult.ok(toSummary(r.getData()));
    }

    @PostMapping("/{id}/live")
    public ApiResult<Void> live(@PathVariable long id) {
        return activityService.goLive(id);
    }

    @PostMapping("/{id}/settle")
    public ApiResult<ReconcileSummary> settle(@PathVariable long id) {
        var r = activityService.settle(id);
        return r.getData() == null ? ApiResult.fail(r.getCode(), r.getMessage())
                : ApiResult.ok(toSummary(r.getData()));
    }

    @PostMapping("/{id}/close")
    public ApiResult<Void> close(@PathVariable long id) {
        return activityService.close(id);
    }

    @GetMapping("/{id}/reconcile")
    public ApiResult<ReconcileSummary> reconcile(@PathVariable long id) {
        var r = activityService.reconcile(id);
        return r.getData() == null ? ApiResult.fail(r.getCode(), r.getMessage())
                : ApiResult.ok(toSummary(r.getData()));
    }

    /** 把内部对账报告转成商家可读的业务摘要（隐藏 MySQL/Redis 维度）。 */
    private ReconcileSummary toSummary(ReconcileReport r) {
        if (r == null) {
            return new ReconcileSummary(true, "UNKNOWN", List.of("暂无对账数据"));
        }
        List<String> messages = new java.util.ArrayList<>();
        messages.add(r.isPassed() ? "对账通过：库存、销量与台账三方一致"
                : "对账异常：存在不一致项，请人工介入");
        r.getItems().stream()
                .filter(it -> !it.getDimension().startsWith("mysql-redis")
                        && !it.getDimension().startsWith("db-internal")
                        && !it.getDimension().startsWith("final-db")
                        && !it.getDimension().startsWith("final-redis"))
                .forEach(it -> messages.add((it.isOk() ? "✓ " : "✗ ") + it.getDetail()));
        return new ReconcileSummary(r.isPassed(), r.getActivityStatus(), messages);
    }
}

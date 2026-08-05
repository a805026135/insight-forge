package com.insightforge.seckill.core.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 抢购请求（Phase 1）。也是 seckill-core 对外的契约 DTO，
 * api 模块的 Controller 直接复用它作为 @RequestBody，避免跨模块再建一份。
 */
@Data
public class SeckillRequest implements Serializable {

    /** 活动 id */
    private Long activityId;

    /** SKU id（= product.id） */
    private Long skuId;

    /** 用户 id（Phase 4 起用于幂等 hash） */
    private Long userId;

    /** 购买数量（Phase 1 一般传 1） */
    private Integer qty;
}

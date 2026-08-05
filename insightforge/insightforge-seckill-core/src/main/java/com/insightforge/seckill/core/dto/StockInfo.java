package com.insightforge.seckill.core.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 库存查询返回（Phase 1，便于手动验证看「剩余/已售/初始」）。
 */
@Data
public class StockInfo implements Serializable {

    private Long skuId;
    private Integer totalStock;
    private Integer stock;
    private Integer sold;
}

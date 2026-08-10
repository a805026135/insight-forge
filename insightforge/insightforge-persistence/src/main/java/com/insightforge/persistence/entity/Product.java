package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商品主数据（Phase 1 建表）。
 * 秒杀活动挂的是某个 SKU（这里用 product.id 作为 skuId 关联）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product")
public class Product extends BaseEntity {

    /** 商品名称，如「iPhone 16 秒杀款」 */
    private String name;

    /** SKU 编码，业务侧唯一标识，如「SKU-IP16」 */
    private String sku;

    /** 成本价（元），用于后续 Agent 定价时判断「价 < 成本」等违规 */
    private BigDecimal costPrice;

    /** 市场价/原价（元），买家端展示「原价划线 + 秒杀价」用 */
    private BigDecimal marketPrice;

    /** 商品图片（前端相对路径，如 /products/iphone16pro.jpg） */
    private String imageUrl;
}

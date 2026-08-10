package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单明细（Phase 17）：一单多件，记录下单瞬间的商品/商家快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_item")
public class OrderItem extends BaseEntity {
    private String orderId;
    private Long activitySkuId;
    private Long productId;
    private String productName;
    private String productImage;
    private Long merchantId;
    private String merchantName;
    private Integer qty;
    private BigDecimal price;
    private LocalDateTime createdAt;
}

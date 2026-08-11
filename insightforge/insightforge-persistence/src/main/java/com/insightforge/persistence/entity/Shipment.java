package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运单（Phase 19o）：商家发货时生成，一个订单一条。
 */
@Data
@TableName("shipment")
public class Shipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderId;

    private String trackingNo;

    private String carrier;

    private LocalDateTime shippedAt;

    private LocalDateTime deliveredAt;
}

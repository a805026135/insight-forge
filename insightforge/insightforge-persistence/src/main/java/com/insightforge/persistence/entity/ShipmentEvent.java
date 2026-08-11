package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物流轨迹事件（Phase 19o）：揽收 → 运输中 → 派送中 → 已签收。
 */
@Data
@TableName("shipment_event")
public class ShipmentEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shipmentId;

    private LocalDateTime eventTime;

    private String status;

    private String description;
}

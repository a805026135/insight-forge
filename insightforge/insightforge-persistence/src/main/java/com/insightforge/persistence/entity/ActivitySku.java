package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 活动 × SKU 的库存真相（Phase 1 建表，秒杀核心表）。
 *
 * 三个库存相关字段的分工（也是 Phase 2/3/6 的伏笔）：
 * - totalStock：初始库存，创建活动时写入，之后不变，用于对账（最终库存 = 初始 - 已售）。
 * - stock：剩余库存，热路径每次扣减就改它（Phase 1 用 UPDATE SET stock=stock-1）。
 * - sold：已售数量，扣减时同步 +1，用于对账与销量流（供规则引擎订阅）。
 * - version：乐观锁版本号，Phase 2 启用（UPDATE ... SET ... , version=version+1 WHERE version=?）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_sku")
public class ActivitySku extends BaseEntity {

    /** 所属活动 id（关联 activity.id） */
    private Long activityId;

    /** 关联的 SKU，这里直接用 product.id */
    private Long skuId;

    /** 秒杀价（元） */
    private BigDecimal seckillPrice;

    /** 初始库存（对账用，不再变动） */
    private Integer totalStock;

    /** 剩余库存（热路径扣减字段） */
    private Integer stock;

    /** 已售数量（对账 / 销量流用） */
    private Integer sold;

    /** 乐观锁版本号（Phase 2 启用） */
    private Integer version;
}

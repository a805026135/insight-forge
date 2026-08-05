package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 大促活动（Phase 1 建表）。
 * status 在 Phase 6 之前先用字符串存（DRAFT/ARMED/LIVE/SETTLING/CLOSED），
 * Phase 6 会升级成枚举 + 状态机做合法迁转校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity")
public class Activity extends BaseEntity {

    /** 活动名称，如「双11 美妆数码场」 */
    private String name;

    /** 状态机：DRAFT → ARMED → LIVE → SETTLING → CLOSED（Phase 6 强化） */
    private String status;

    /** 运营设定的目标 GMV（元），Agent 离线规划的目标输入 */
    private BigDecimal targetGmv;

    /** 运营设定的目标 ROI（投资回报率），Agent 定价的约束 */
    private BigDecimal targetRoi;

    /** 活动开始时间 */
    private LocalDateTime startAt;

    /** 活动结束时间 */
    private LocalDateTime endAt;
}

package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护栏策略版本化实体（Phase 8 产出物的「真相源」）。
 *
 * 对应表 {@code guardrail_policy}：每次发布一条新版本（activity_id + version 唯一），
 * 在线规则引擎（Phase 9）读最新版编译执行。policy_json 存完整 DSL（VARCHAR(8000) 兼容 H2 与 MySQL）。
 */
@Data
@TableName("guardrail_policy")
public class GuardrailPolicyEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long activityId;
    private String version;
    private String policyJson;
    private LocalDateTime createdAt;
}

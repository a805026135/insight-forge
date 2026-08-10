package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家主体（Phase 17）：店铺名/简介/logo/评分/销量/认证。
 * 登录账号仍是 users 表（role=merchant），通过 user_id 关联。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("merchant")
public class Merchant extends BaseEntity {
    private Long userId;
    private String name;
    private String logo;
    private String description;
    private BigDecimal rating;
    private Long salesCount;
    private Boolean verified;
    private LocalDateTime createdAt;
}

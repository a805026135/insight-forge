package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

/**
 * 实体基类（Phase 0）。各业务表实体继承此类获得自增主键。
 * 后续 Phase 可在此扩展 createdAt / updatedAt 自动填充。
 */
@Data
public abstract class BaseEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
}

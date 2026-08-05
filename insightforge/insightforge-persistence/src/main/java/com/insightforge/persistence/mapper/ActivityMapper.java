package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.Activity;

/**
 * 活动 Mapper（Phase 1）。后续 Phase 的「创建活动 / 触发规划 / 上线」等接口由它支撑。
 */
public interface ActivityMapper extends BaseMapper<Activity> {
}

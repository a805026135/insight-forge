package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.AgentCheckpoint;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_checkpoint 持久化 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface AgentCheckpointMapper extends BaseMapper<AgentCheckpoint> {
}

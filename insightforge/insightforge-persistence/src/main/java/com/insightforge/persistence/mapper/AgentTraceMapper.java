package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_trace 持久化 Mapper（MyBatis-Plus）。
 * 由 @MapperScan("com.insightforge.persistence.mapper") 统一注册，无需写 XML。
 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {
}

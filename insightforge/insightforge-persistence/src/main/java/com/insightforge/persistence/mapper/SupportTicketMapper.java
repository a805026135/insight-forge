package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.SupportTicket;
import org.apache.ibatis.annotations.Mapper;

/**
 * support_ticket 持久化 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface SupportTicketMapper extends BaseMapper<SupportTicket> {
}

package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.BuyerChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * buyer_chat_message 持久化 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface BuyerChatMessageMapper extends BaseMapper<BuyerChatMessage> {
}

package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.Orders;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单 Mapper（Phase 1）。Phase 4 会配合 Kafka 异步建单 + Redis SETNX 幂等。
 */
public interface OrdersMapper extends BaseMapper<Orders> {

    /** 按用户 id 查订单（最近优先），给「我的订单」页用。 */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Orders> findByUserId(@Param("userId") Long userId);
}

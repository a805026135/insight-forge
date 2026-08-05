package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.Orders;

/**
 * 订单 Mapper（Phase 1）。Phase 4 会配合 Kafka 异步建单 + Redis SETNX 幂等。
 */
public interface OrdersMapper extends BaseMapper<Orders> {
}

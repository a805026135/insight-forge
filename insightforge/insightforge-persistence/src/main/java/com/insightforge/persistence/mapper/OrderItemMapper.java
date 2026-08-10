package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.OrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OrderItemMapper extends BaseMapper<OrderItem> {

    @Select("SELECT * FROM order_item WHERE order_id = #{orderId}")
    List<OrderItem> findByOrderId(@Param("orderId") String orderId);
}

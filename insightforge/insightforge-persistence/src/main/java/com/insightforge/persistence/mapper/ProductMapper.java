package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper（Phase 1）。仅继承 BaseMapper 获得基础 CRUD，
 * 后续 Phase 如需复杂查询再补自定义方法。
 */
public interface ProductMapper extends BaseMapper<Product> {

    /** 普通购买扣减商品独立库存（原子条件更新，防止超卖） */
    @Update("UPDATE product SET stock = stock - #{qty} WHERE id = #{id} AND stock >= #{qty}")
    int decrementStock(@Param("id") Long id, @Param("qty") Integer qty);

    /** 取消订单回补普通库存 */
    @Update("UPDATE product SET stock = stock + #{qty} WHERE id = #{id}")
    int incrementStock(@Param("id") Long id, @Param("qty") Integer qty);
}

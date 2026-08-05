package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.Product;

/**
 * 商品 Mapper（Phase 1）。仅继承 BaseMapper 获得基础 CRUD，
 * 后续 Phase 如需复杂查询再补自定义方法。
 */
public interface ProductMapper extends BaseMapper<Product> {
}

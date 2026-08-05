package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.ActivitySku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 活动库存 Mapper（Phase 1，秒杀核心）。
 */
public interface ActivitySkuMapper extends BaseMapper<ActivitySku> {

    /**
     * ⚠️ 故意不安全：这条扣减 SQL 没有 `WHERE stock >= #{qty}` 条件。
     *
     * 业务代码里是先 SELECT 查库存、再调本方法扣减，两步不是原子的：
     * 并发时多个线程可能「同时看到库存还有货」→ 都通过判断 → 一起扣减，
     * 于是库存被扣成负数（超卖），订单数却超过了初始库存。
     *
     * 修复路线（后续 Phase）：
     * - Phase 2：加上 `AND stock >= #{qty}`，变成 DB 级兜底（条件更新原子）。
     * - Phase 3：把扣减从 DB 移到 Redis + Lua（单线程原子执行），彻底解决性能与超卖。
     */
    @Update("UPDATE activity_sku SET stock = stock - #{qty}, sold = sold + #{qty} WHERE id = #{id}")
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);
}

package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.ActivitySku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 活动库存 Mapper（秒杀核心）。
 */
public interface ActivitySkuMapper extends BaseMapper<ActivitySku> {

    /**
     * Phase 2 修复：原子条件更新（DB 级超卖兜底）。
     *
     * 关键点：把「库存够不够」的判断直接写进 UPDATE 的 WHERE 子句里。
     * 数据库对「单条 UPDATE」是原子执行的——InnoDB（以及 H2 的 MVCC）会对命中行
     * 加记录锁，并在锁内重新校验 WHERE 条件，所以「判断 stock >= qty」和「扣减」
     * 这两步不再分离。并发下不可能有两个事务同时穿过 stock>=qty 的校验，
     * 因此库存绝不会被扣成负数。
     *
     * 返回值 = 受影响行数：
     * - 1 ：扣减成功（库存确实 >= qty，已原子扣减）
     * - 0 ：库存不足（或行不存在），本次不扣减 —— 业务层据此返回「售罄」
     *
     * 对比 Phase 1 的「先 SELECT 再 UPDATE 无 WHERE 条件」：那个写法两步不原子，
     * 并发会超卖（库存变负、订单数超过初始库存）。本写法一行 SQL 把竞态彻底关掉。
     *
     * 进阶对照（见 tutorials/phase2.md）：MyBatis-Plus `@Version` 乐观锁也是一种做法，
     * 但对「库存扣减」而言，单独靠 version 并不能挡住超卖（还得额外判断 stock>=qty），
     * 而本条件更新已经把 stock>=qty 原子化了，所以这里不依赖 version 也能 100% 不超卖。
     * version 字段仍保留在表里，留给 Phase 6 对账 / 后续乐观并发场景使用。
     */
    @Update("UPDATE activity_sku SET stock = stock - #{qty}, sold = sold + #{qty} " +
            "WHERE id = #{id} AND stock >= #{qty}")
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);
}

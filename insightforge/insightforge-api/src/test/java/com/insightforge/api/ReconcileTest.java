package com.insightforge.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.reconcile.ReconcileReport;
import com.insightforge.seckill.core.reconcile.ReconcileService;
import com.insightforge.seckill.core.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账服务测试（Phase 6，local profile：H2 内存库，无 Redis）。
 *
 * 隔离策略关键说明：
 *   实体基类 BaseEntity 用 @TableId(AUTO)，MyBatis-Plus 会忽略实体里 set 的 id、让数据库自增；
 *   而 data.sql 种子显式写了 activity_id=1 / activity_sku id=1，导致数据库自增序列未从 1 推进，
 *   reconcile 测试若用 Mapper.insert 自增分配又会从 1 开始 → 撞主键。
 *   因此这里用 JdbcTemplate 以「显式大 id（99001）」插入隔离的活动 / SKU，彻底绕开 AUTO 策略，
 *   绝不碰种子 id=1，也就不会干扰 SeckillNoOversellTest（它依赖 skuId=1 的初始库存 100）。
 *   orders 表无种子数据（data.sql 只清空不插入），自增追加永不撞，直接用 Mapper.insert 即可。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReconcileTest {

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private ActivitySkuMapper skuMapper;
    @Autowired
    private OrdersMapper ordersMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 显式大 id 隔离（不与 data.sql 种子的 1 冲突）。 */
    private static final long ACT_ID = 99001L;
    private static final long SKU_PK_ID = 99001L; // activity_sku 主键 id
    private static final long SKU_ID = 90001L;    // product.id（= activity_sku.sku_id 字段）

    @BeforeEach
    void setUp() {
        // 先清后插，保证每个测试方法从干净状态开始（H2 跨方法共享，必须幂等）
        jdbc.update("DELETE FROM activity_sku WHERE id = ?", SKU_PK_ID);
        jdbc.update("DELETE FROM activity WHERE id = ?", ACT_ID);
        ordersMapper.delete(new QueryWrapper<Orders>().eq("activity_id", ACT_ID));

        // JdbcTemplate 显式指定 id 插入（绕开 @TableId(AUTO) 的自增分配）
        jdbc.update("INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                ACT_ID, "对账测试活动", "DRAFT", BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        jdbc.update("INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                SKU_PK_ID, ACT_ID, SKU_ID, BigDecimal.ONE, 100, 100, 0, 0);
    }

    /** 模拟「抢了 30 单成功」：插 30 张订单 + 库存改为 stock=70, sold=30，结算对账应通过。 */
    @Test
    void finalStock_consistent_after30Orders() {
        insertOrders(ACT_ID, SKU_ID, 30, OrderStatus.CREATE_ORDER);
        skuMapper.update(null, new UpdateWrapper<ActivitySku>()
                .eq("id", SKU_PK_ID).set("stock", 70).set("sold", 30));

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertTrue(report.isPassed(), "最终库存=100-30=70，应一致通过");
    }

    /** 制造不一致：抢 30 单，但库存错误地停在 69（少回写 1），对账应发现不通过。 */
    @Test
    void finalStock_detects_mismatch() {
        insertOrders(ACT_ID, SKU_ID, 30, OrderStatus.CREATE_ORDER);
        skuMapper.update(null, new UpdateWrapper<ActivitySku>()
                .eq("id", SKU_PK_ID).set("stock", 69).set("sold", 30));

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertFalse(report.isPassed(), "stock=69 但应有 70，对账应发现不一致");
    }

    /** 取消的订单不计入成功单：抢 30 单 + 取消 5 单回补，成功单=30，期望 stock=70。 */
    @Test
    void cancelledOrders_excluded_from_success() {
        insertOrders(ACT_ID, SKU_ID, 30, OrderStatus.CREATE_ORDER);
        insertOrders(ACT_ID, SKU_ID, 5, OrderStatus.CANCEL); // 5 单已取消并回补库存
        skuMapper.update(null, new UpdateWrapper<ActivitySku>()
                .eq("id", SKU_PK_ID).set("stock", 70).set("sold", 30));

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertTrue(report.isPassed(), "成功单=30（CANCEL 不计入），期望 stock=100-30=70，应一致");
    }

    /** 上线前对账（local 无 Redis）：降级为 DB 内部校验，初始自洽状态应通过。 */
    @Test
    void stockConsistency_local_skipsRedis() {
        ReconcileReport report = reconcileService.reconcileStockConsistency(ACT_ID);
        assertTrue(report.isPassed(), "local 下应降级为 DB 内部校验并通过对");
    }

    /** 辅助：插入 n 张指定状态的订单（orderId 用 nanoTime 保证唯一，绕过唯一约束）。 */
    private void insertOrders(long activityId, long skuId, int n, OrderStatus status) {
        for (int i = 0; i < n; i++) {
            Orders o = new Orders();
            o.setOrderId("RC-" + activityId + "-" + skuId + "-" + status + "-" + i + "-" + System.nanoTime());
            o.setUserId(5000L + i);
            o.setActivityId(activityId);
            o.setSkuId(skuId);
            o.setQty(1);
            o.setStatus(status.name());
            o.setCreatedAt(LocalDateTime.now());
            ordersMapper.insert(o);
        }
    }
}

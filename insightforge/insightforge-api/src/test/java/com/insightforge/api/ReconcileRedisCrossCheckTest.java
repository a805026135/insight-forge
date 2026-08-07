package com.insightforge.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.redis.RedisStockService;
import com.insightforge.seckill.core.reconcile.ReconcileReport;
import com.insightforge.seckill.core.reconcile.ReconcileService;
import com.insightforge.seckill.core.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * REVIEW §3.2 回归测试：结算对账必须<b>交叉核对 Redis 权威库存</b>，而非只在 DB 内部用
 * 「total − 成功单数」与 DB stock 比（后者因 stock=total−sold 同源更新恒成立，是恒等式）。
 *
 * 用 @MockBean 注入一个 RedisStockService（local profile 原本没有），让对账真正走「台账 SUM(qty)
 * ↔ Redis 库存」分支，验证：Redis 与台账一致时通过；Redis 漂移（丢/多）时被抓出。
 *
 * 隔离：用显式大 id（99101）插入活动/SKU，不碰 data.sql 种子 id=1。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReconcileRedisCrossCheckTest {

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private ActivitySkuMapper skuMapper;
    @Autowired
    private OrdersMapper ordersMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 用 @MockBean 让 ReconcileService 的 ObjectProvider<RedisStockService> 拿到这个 mock。 */
    @MockBean
    private RedisStockService redisStockService;

    private static final long ACT_ID = 99101L;
    private static final long SKU_PK_ID = 99101L;
    private static final long SKU_ID = 90101L;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM activity_sku WHERE id = ?", SKU_PK_ID);
        jdbc.update("DELETE FROM activity WHERE id = ?", ACT_ID);
        ordersMapper.delete(new QueryWrapper<Orders>().eq("activity_id", ACT_ID));
        jdbc.update("INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                ACT_ID, "对账Redis交叉测试", "DRAFT", BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        jdbc.update("INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                SKU_PK_ID, ACT_ID, SKU_ID, BigDecimal.ONE, 100, 100, 0, 0);
    }

    /** 台账 30 单（qty=1），期望库存=70；Redis 也返回 70 → 通过。 */
    @Test
    void finalStock_passes_whenRedisMatchesLedger() {
        insertOrders(ACT_ID, SKU_ID, 30, OrderStatus.CREATE_ORDER, 1);
        skuMapper.update(null, new UpdateWrapper<ActivitySku>().eq("id", SKU_PK_ID).set("stock", 70).set("sold", 30));
        when(redisStockService.getStock(anyLong(), anyLong())).thenReturn(70);

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertTrue(report.isPassed(), "Redis(70) 与台账期望(70) 一致，应通过对账");
    }

    /** 台账 30 单，期望 70；但 Redis 漂移成 65（丢失 5 件）→ 必须被抓出（REVIEW §3.2 关心的漂移）。 */
    @Test
    void finalStock_detects_redis_drift_lost() {
        insertOrders(ACT_ID, SKU_ID, 30, OrderStatus.CREATE_ORDER, 1);
        skuMapper.update(null, new UpdateWrapper<ActivitySku>().eq("id", SKU_PK_ID).set("stock", 70).set("sold", 30));
        when(redisStockService.getStock(anyLong(), anyLong())).thenReturn(65);

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertFalse(report.isPassed(), "Redis 漂移成 65≠期望 70，对账必须发现不一致");
    }

    /** 台账 30 单，期望 70；Redis 膨胀成 75（多记 5 件）→ 也必须被抓出。 */
    @Test
    void finalStock_detects_redis_drift_inflated() {
        insertOrders(ACT_ID, SKU_ID, 30, OrderStatus.CREATE_ORDER, 1);
        skuMapper.update(null, new UpdateWrapper<ActivitySku>().eq("id", SKU_PK_ID).set("stock", 70).set("sold", 30));
        when(redisStockService.getStock(anyLong(), anyLong())).thenReturn(75);

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertFalse(report.isPassed(), "Redis 膨胀成 75≠期望 70，对账必须发现不一致");
    }

    /** 支持 qty>1：台账 10 单 × qty=3 = 30 件，期望 70；Redis 返回 70 → 通过（原计数实现会算错）。 */
    @Test
    void finalStock_supports_qtyGreaterThanOne() {
        insertOrders(ACT_ID, SKU_ID, 10, OrderStatus.CREATE_ORDER, 3);
        skuMapper.update(null, new UpdateWrapper<ActivitySku>().eq("id", SKU_PK_ID).set("stock", 70).set("sold", 30));
        when(redisStockService.getStock(anyLong(), anyLong())).thenReturn(70);

        ReconcileReport report = reconcileService.reconcileFinalStock(ACT_ID);
        assertTrue(report.isPassed(), "10 单×qty3=30 件，期望 70，与原计数实现（只数单数）不同，应正确通过");
    }

    private void insertOrders(long activityId, long skuId, int n, OrderStatus status, int qty) {
        for (int i = 0; i < n; i++) {
            Orders o = new Orders();
            o.setOrderId("RC2-" + activityId + "-" + skuId + "-" + status + "-" + qty + "-" + i + "-" + System.nanoTime());
            o.setUserId(6000L + i);
            o.setActivityId(activityId);
            o.setSkuId(skuId);
            o.setQty(qty);
            o.setStatus(status.name());
            o.setCreatedAt(LocalDateTime.now());
            ordersMapper.insert(o);
        }
    }
}

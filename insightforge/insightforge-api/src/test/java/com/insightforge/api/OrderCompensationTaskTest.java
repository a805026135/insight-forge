package com.insightforge.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.service.OrderCompensationTask;
import com.insightforge.seckill.core.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 14.5 补偿巡检测试：Redis 幂等键存在但订单未落库时，任务应读取载荷重建订单并扣 DB 库存；
 * 已落库的键应跳过。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class OrderCompensationTaskTest {

    @Autowired
    private OrderCompensationTask task;
    @Autowired
    private OrdersMapper ordersMapper;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private StringRedisTemplate redis;

    private static final long ACT_ID = 97001L;
    private static final long SKU_PK_ID = 97001L;
    private static final String ORDER_ID = "OF-97001-97001-70001";

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM orders WHERE order_id = ?", ORDER_ID);
        jdbc.update("DELETE FROM activity_sku WHERE id = ?", SKU_PK_ID);
        jdbc.update("DELETE FROM activity WHERE id = ?", ACT_ID);
        jdbc.update("INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) " +
                "VALUES (?, '补偿测试活动', 'LIVE', 0, 0, NULL, NULL)", ACT_ID);
        jdbc.update("INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                SKU_PK_ID, ACT_ID, 97001L, BigDecimal.ONE, 10, 10, 0, 0);
    }

    private void stubRedisWithPayload(String payload) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.keys("order:idemp:*")).thenReturn(Set.of("order:idemp:" + ORDER_ID));
        when(ops.get("order:idemp:" + ORDER_ID)).thenReturn(payload);
    }

    @Test
    void sweepRebuildsMissingOrder() throws Exception {
        OrderCreatedEvent evt = new OrderCreatedEvent();
        evt.setOrderId(ORDER_ID);
        evt.setUserId(70001L);
        evt.setActivityId(ACT_ID);
        evt.setSkuId(SKU_PK_ID);
        evt.setQty(1);
        evt.setPrice(BigDecimal.TEN);
        stubRedisWithPayload(objectMapper.writeValueAsString(evt));

        task.sweep();

        Orders saved = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", ORDER_ID));
        assertNotNull(saved, "补偿任务应重建丢失的订单");
        assertEquals(OrderStatus.CREATE_ORDER.name(), saved.getStatus());
        assertEquals(0, BigDecimal.TEN.compareTo(saved.getPrice()), "补偿订单应带载荷中的成交价");
        ActivitySku sku = activitySkuMapper.selectById(SKU_PK_ID);
        assertEquals(9, sku.getStock(), "补偿扣库存：10 → 9");
    }

    @Test
    void sweepSkipsExistingOrder() throws Exception {
        // 订单已落库（模拟消费者已处理）
        Orders existing = new Orders();
        existing.setOrderId(ORDER_ID);
        existing.setUserId(70001L);
        existing.setActivityId(ACT_ID);
        existing.setSkuId(SKU_PK_ID);
        existing.setQty(1);
        existing.setPrice(BigDecimal.ONE);
        existing.setStatus(OrderStatus.CREATE_ORDER.name());
        existing.setCreatedAt(java.time.LocalDateTime.now());
        ordersMapper.insert(existing);
        stubRedisWithPayload("{}");

        task.sweep();

        assertEquals(1L, ordersMapper.selectCount(new QueryWrapper<Orders>().eq("order_id", ORDER_ID)),
                "已落库订单不应被重复创建");
        ActivitySku sku = activitySkuMapper.selectById(SKU_PK_ID);
        assertEquals(10, sku.getStock(), "已落库订单不应重复扣库存");
    }
}

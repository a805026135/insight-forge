package com.insightforge.seckill.core.kafka;

import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.service.OrderCreationService;
import com.insightforge.seckill.core.service.InventoryConsistencyException;
import com.insightforge.seckill.core.state.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REVIEW §3.1 回归测试（迁移到幂等建单服务）：落库的订单状态必须是状态机可解析的
 * 持久初态 {@link OrderStatus#CREATE_ORDER}，且只有「真正插入新单」才扣 DB 库存。
 * 该不变量现在由 {@link OrderCreationService} 统一保证（异步消费者与补偿巡检共用）。
 */
@ExtendWith(MockitoExtension.class)
class OrderCreatedConsumerStatusTest {

    @Mock
    private OrdersMapper ordersMapper;
    @Mock
    private ActivitySkuMapper activitySkuMapper;
    @InjectMocks
    private OrderCreationService orderCreationService;

    private SeckillRequest req() {
        SeckillRequest req = new SeckillRequest();
        req.setUserId(1001L);
        req.setActivityId(1L);
        req.setSkuId(1L);
        req.setQty(2);
        return req;
    }

    @Test
    void createdOrderStatusIsCreateOrderAndStockDecremented() {
        when(ordersMapper.insert(any(Orders.class))).thenAnswer(inv -> {
            Orders o = inv.getArgument(0);
            assertEquals(OrderStatus.CREATE_ORDER.name(), o.getStatus(),
                    "落库状态必须是状态机可解析的 CREATE_ORDER");
            assertEquals(OrderStatus.CREATE_ORDER, OrderStatus.from(o.getStatus()));
            return 1;
        });
        when(activitySkuMapper.decrementStock(anyLong(), anyInt())).thenReturn(1);

        boolean inserted = orderCreationService.createOrderIdempotent(req(), "OF-1-1-1001", BigDecimal.ONE);

        assertTrue(inserted, "首次插入应返回 true");
        verify(activitySkuMapper).decrementStock(1L, 2);
    }

    @Test
    void duplicateKeySkipsStockDecrement() {
        when(ordersMapper.insert(any(Orders.class))).thenThrow(new DuplicateKeyException("dup"));

        boolean inserted = orderCreationService.createOrderIdempotent(req(), "OF-1-1-1001", BigDecimal.ONE);

        assertFalse(inserted, "重复订单不应视为新插入");
        verify(activitySkuMapper, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void stockConflictFailsFastSoTransactionCanRollback() {
        when(ordersMapper.insert(any(Orders.class))).thenReturn(1);
        when(activitySkuMapper.decrementStock(1L, 2)).thenReturn(0);

        assertThrows(InventoryConsistencyException.class,
                () -> orderCreationService.createOrderIdempotent(
                        req(), "OF-1-1-1001", BigDecimal.ONE));
    }
}

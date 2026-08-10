package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.state.OrderStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * REVIEW §3.1 回归测试：异步消费者落库的订单状态必须是状态机可解析的持久初态
 * {@link OrderStatus#CREATE_ORDER}，绝不能写成字面量 "CREATED"——否则
 * {@code OrderStatus.from("CREATED")} 抛异常，导致经异步快路径创建的订单在
 * {@code OrderController} 的 cancel / pay 直接 500。
 */
@ExtendWith(MockitoExtension.class)
class OrderCreatedConsumerStatusTest {

    @Mock
    private OrdersMapper ordersMapper;
    @Mock
    private ActivitySkuMapper activitySkuMapper;
    @Mock
    private ObjectMapper objectMapper;
    @InjectMocks
    private OrderCreatedConsumer consumer;

    @Test
    void asyncOrderStatusIsCreateOrderAndParseable() throws Exception {
        // 构造一条订单创建事件（与生产者 SeckillService 用的字段一致）
        SeckillRequest req = new SeckillRequest();
        req.setUserId(1001L);
        req.setActivityId(1L);
        req.setSkuId(1L);
        req.setQty(2);
        OrderCreatedEvent evt = OrderCreatedEvent.from(req, "OF-1-1-1001", java.math.BigDecimal.ONE);

        // 反序列化返回这条事件；建单 insert 时捕获写入的 Orders，断言其状态
        when(objectMapper.readValue(anyString(), eq(OrderCreatedEvent.class))).thenReturn(evt);
        when(ordersMapper.insert(any(Orders.class))).thenAnswer(inv -> {
            Orders o = inv.getArgument(0);
            // 核心断言：写入的状态是 CREATE_ORDER，且能被状态机 from() 解析（不抛异常）
            assertEquals(OrderStatus.CREATE_ORDER.name(), o.getStatus());
            assertEquals(OrderStatus.CREATE_ORDER, OrderStatus.from(o.getStatus()));
            return 1; // 插入成功
        });
        when(activitySkuMapper.decrementStock(anyLong(), anyInt())).thenReturn(1);

        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("order.created", 0, 0L, "OF-1-1-1001", "{}");
        consumer.onOrderCreated(record);
    }
}

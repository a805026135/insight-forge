package com.insightforge.seckill.core.service;

import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.state.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 幂等建单服务（Phase 14.5：把「异步消费者」与「补偿巡检」共用的落库逻辑收敛到这里）。
 *
 * 铁律：只有「本次真正插入新订单」才扣 DB 库存——order_id 唯一约束保证无论 Kafka 重投、
 * 还是补偿任务与消费者并发抢插，库存都恰好扣一次（exactly-once）。
 */
@Service
public class OrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreationService.class);

    private final OrdersMapper ordersMapper;
    private final ActivitySkuMapper activitySkuMapper;

    public OrderCreationService(OrdersMapper ordersMapper, ActivitySkuMapper activitySkuMapper) {
        this.ordersMapper = ordersMapper;
        this.activitySkuMapper = activitySkuMapper;
    }

    /** 幂等建单 + 扣 DB 库存；返回是否真正插入新订单。 */
    public boolean createOrderIdempotent(SeckillRequest req, String orderId, BigDecimal price) {
        Orders order = new Orders();
        order.setOrderId(orderId);
        order.setUserId(req.getUserId());
        order.setActivityId(req.getActivityId());
        order.setSkuId(req.getSkuId());
        order.setQty(req.getQty());
        order.setPrice(price);
        // 与同步建单路径保持同一持久初态 CREATE_ORDER（状态机可解析）
        order.setStatus(OrderStatus.CREATE_ORDER.name());
        order.setCreatedAt(LocalDateTime.now());
        try {
            ordersMapper.insert(order);
        } catch (DuplicateKeyException e) {
            log.info("订单已存在，命中幂等去重：orderId={}", orderId);
            return false;
        }
        // 仅新单才扣 DB 库存（Redis 已先扣，这里补齐 DB；条件更新绝不为负）
        activitySkuMapper.decrementStock(req.getSkuId(), req.getQty());
        return true;
    }
}

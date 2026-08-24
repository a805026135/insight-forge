package com.insightforge.seckill.core.service;

import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SalesTickEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import com.insightforge.seckill.core.cache.ActivityCacheService;
import com.insightforge.seckill.core.cache.SkuCacheService;
import com.insightforge.seckill.core.kafka.OrderEventProducer;
import com.insightforge.seckill.core.kafka.SalesTickProducer;
import com.insightforge.seckill.core.redis.ReservationResult;
import com.insightforge.seckill.core.redis.RedisStockService;
import com.insightforge.seckill.core.state.ActivityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 秒杀主流程（Phase 4：Kafka 异步削峰 + 幂等）。
 *
 * 相比 Phase 3 的核心变化——「建单落库」从**同步**变成**异步**：
 * - 扣减成功 → 往 Kafka 发一条 order.created 事件 → **立刻返回用户**（实际 P95/P99 以云端 A/B 为准）。
 * - 后方的 {@code OrderCreatedConsumer} 异步把「订单 + DB 库存」落库。海量并发先堆在 Kafka，
 *   由消费者匀速消化（削峰），用户的请求路径上不再有 DB 写。
 * - Redis Lua 原子创建可恢复 Reservation，合并幂等、扣减、恢复载荷和超时索引；
 *   数据库 order_id 唯一约束保证重放无重复业务副作用。
 *
 * 两条路径（自动选择，零代码切换）：
 * - 非 local：Redis 原子预留 → Kafka 异步落库；消息失败由同步收敛/ZSET 补偿恢复。
 * - local（零依赖冒烟）：无 Redis/Kafka，走 DB 条件更新 + order_id 唯一约束幂等，同步落库（教学/冒烟用）。
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final ActivityCacheService activityCacheService;
    private final ObjectMapper objectMapper;
    /** SKU 多级缓存（Phase 5）：高频读走 L1 Caffeine + L2 Redis，避免每次打 DB。 */
    private final SkuCacheService skuCacheService;
    /** Redis 库存服务：用 ObjectProvider 拿，local profile 下为 null（不存在该 Bean）。 */
    private final ObjectProvider<RedisStockService> redisStockService;
    /** Kafka 消费、同步收敛和补偿共用的唯一数据库写模型。 */
    private final OrderCreationService orderCreationService;
    /** Kafka 生产者：仅非 local 存在，local 下为 null。 */
    private final ObjectProvider<OrderEventProducer> orderEventProducer;
    /** 销售心跳生产者（集成缝：扣减成功 → sales.tick，驱动规则引擎）：仅非 local 存在。 */
    private final ObjectProvider<SalesTickProducer> salesTickProducer;

    public SeckillService(ActivityCacheService activityCacheService,
                          ObjectMapper objectMapper,
                          SkuCacheService skuCacheService,
                          ObjectProvider<RedisStockService> redisStockService,
                          OrderCreationService orderCreationService,
                          ObjectProvider<OrderEventProducer> orderEventProducer,
                          ObjectProvider<SalesTickProducer> salesTickProducer) {
        this.activityCacheService = activityCacheService;
        this.objectMapper = objectMapper;
        this.skuCacheService = skuCacheService;
        this.redisStockService = redisStockService;
        this.orderCreationService = orderCreationService;
        this.orderEventProducer = orderEventProducer;
        this.salesTickProducer = salesTickProducer;
    }

    /**
     * 抢购主入口。这里故意不加事务：Redis/Kafka 网络等待不能占用数据库连接和事务。
     *
     * 三种结局：
     * 1) 异步快路径（非 local + Redis 扣成功）：发 Kafka → 立刻返回订单号。**接口极快**。
     * 2) 同步兜底（local，或 Redis 异常回退）：DB 条件更新 → 同步建单（order_id 唯一约束幂等）。
     * 3) 售罄：Redis/DB 都告知库存不足 → 返回 409。
     *
     * 所有数据库写统一进入 {@link OrderCreationService} 的短事务。
     */
    public ApiResult<String> seckill(SeckillRequest req) {
        // —— Phase 14.1 请求校验（防脏数据/防利用）：qty>0、活动存在且 LIVE、活动与 SKU 匹配 ——
        if (req.getQty() == null || req.getQty() <= 0) {
            return ApiResult.fail(400, "购买数量不合法（必须 > 0）");
        }
        if (req.getActivityId() == null || req.getSkuId() == null || req.getUserId() == null) {
            return ApiResult.fail(400, "activityId/skuId/userId 不能为空");
        }
        Activity activity = activityCacheService.getActivity(req.getActivityId());
        if (activity == null) {
            return ApiResult.fail(404, "活动不存在");
        }
        if (ActivityStatus.from(activity.getStatus()) != ActivityStatus.LIVE) {
            return ApiResult.fail(409, "活动未开始或已结束");
        }

        ActivitySku sku = skuCacheService.getSku(req.getSkuId());
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        if (!req.getActivityId().equals(sku.getActivityId())) {
            return ApiResult.fail(400, "活动与商品不匹配");
        }

        // 确定性订单号：同一 (activityId, skuId, userId) 永远算出同一个值，
        // 这是全链路幂等的「锚点」——重复请求拿到同一个订单号，DB 唯一约束据此去重。
        String orderId = buildOrderId(req);
        // 成交单价快照：REPRICE 生效后读 Redis 实时价，否则用 DB 秒杀价
        BigDecimal price = currentPrice(sku);

        // —— 主路径：Redis Lua 原子扣减（秒杀高并发的真正入口）——
        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs != null) {
            OrderCreatedEvent event = OrderCreatedEvent.from(req, orderId, price);
            event.setProductId(sku.getSkuId());
            event.setTraceId(MDC.get("traceId"));
            String payload = orderPayloadJson(event);
            ReservationResult res = rs.reserve(sku.getActivityId(), sku.getId(), req.getQty(), orderId, payload);
            if (res == ReservationResult.SUCCESS) {
                // 扣成功 → 发 Kafka 异步建单，立刻返回；延迟目标必须由云端 A/B 验证。
                OrderEventProducer producer = orderEventProducer.getIfAvailable();
                if (producer == null || !producer.sendOrderCreated(event)) {
                    // Reservation 已保存恢复载荷；禁止 Kafka 故障时把峰值同步打进 DB 连接池。
                    log.warn("Kafka 未接受消息，保留 Reservation 等待补偿：orderId={}", orderId);
                }
                sendSalesTick(req);
                return ApiResult.ok(orderId);
            }
            if (res == ReservationResult.DUPLICATE) {
                log.info("命中原子预留凭证，重复请求直接复用订单号：orderId={}", orderId);
                return ApiResult.ok(orderId);
            }
            if (res == ReservationResult.SOLD_OUT) {
                return ApiResult.fail(409, "已售罄");
            }
            // NOT_PREHEATED / ERROR：没有创建预留凭证，保守回退 DB 条件更新。
            if (res == ReservationResult.OVERLOADED) {
                return ApiResult.fail(503, "订单处理队列繁忙，请稍后重试");
            }
        }

        // —— 兜底路径：Phase 2 的 DB 原子条件更新（local 唯一路径；Redis 异常时也走这）——
        return createOrderInDatabase(req, sku, orderId, price);
    }

    /**
     * local/Redis 不可用路径复用同一事务写模型。库存条件不满足时事务服务回滚订单。
     */
    private ApiResult<String> createOrderInDatabase(SeckillRequest req, ActivitySku sku,
                                                    String orderId, BigDecimal price) {
        try {
            boolean inserted = orderCreationService.createOrderIdempotent(
                    req, orderId, price, sku.getSkuId());
            if (inserted) {
                skuCacheService.evict(sku.getId());
                sendSalesTick(req);
            }
            return ApiResult.ok(orderId);
        } catch (InventoryConsistencyException e) {
            return ApiResult.fail(409, "已售罄");
        }
    }

    /**
     * 成交单价快照：AI 调价（REPRICE 写回 price:a:{aid}:s:{skid}）后，新订单以实时价成交；
     * 未调价 / local 环境回退 DB 秒杀价。这就是「AI 调价真实作用于用户支付」的落点。
     */
    private BigDecimal currentPrice(ActivitySku sku) {
        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs != null) {
            BigDecimal redisPrice = rs.readPrice(sku.getActivityId(), sku.getId());
            if (redisPrice != null) {
                return redisPrice;
            }
        }
        return sku.getSeckillPrice();
    }

    /**
     * 集成缝：扣减成功后发一笔销售心跳（仅非 local 有 Kafka 时生效；local 下 Bean 不存在，静默跳过）。
     * 心跳体最小（activityId/skuId/qty），剩余库存快照由规则引擎消费者直接读共享 Redis 库存键获取。
     */
    private void sendSalesTick(SeckillRequest req) {
        SalesTickProducer p = salesTickProducer.getIfAvailable();
        if (p != null) {
            SalesTickEvent event = SalesTickEvent.of(req.getActivityId(), req.getSkuId(), req.getQty());
            event.setTraceId(MDC.get("traceId"));
            p.send(event);
        }
    }

    /** 幂等键载荷：把订单事件 JSON 存进 Redis，供补偿巡检在订单丢失时重建（失败退化为 "1"）。 */
    private String orderPayloadJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("订单恢复载荷序列化失败，拒绝创建不可恢复预留", e);
        }
    }

    /**
     * 用 (activityId, skuId, userId) 确定性推导订单号。
     * 不依赖随机，保证「同一次抢购」永远得到同一个订单号 —— 全链路幂等的基础。
     * 形如 "OF-1-1-1001"，可读且绝不会因数字位数不同而碰撞。
     *
     * <b>业务规则（显式声明，REVIEW §3.3）</b>：orderId <b>不含 qty</b>，因此「同一用户 + 同一活动
     * + 同一 SKU」在本项目里<b>只允许下一单</b>（秒杀常见限一单策略）。用户改数量重试、或对同一 SKU
     * 二次合法购买，都会被判定为重复单、返回既有 orderId。这是有意的业务约束，不是 bug；
     * 若未来要支持「同 SKU 多单 / 改量」，需在此把 qty 纳入 orderId 推导。
     */
    private String buildOrderId(SeckillRequest req) {
        return "OF-" + req.getActivityId() + "-" + req.getSkuId() + "-" + req.getUserId();
    }

    /**
     * 库存预热接口：把 DB 里的剩余库存刷进 Redis（秒杀开始前必须调用一次）。
     * 没启用 Redis 时返回提示，避免静默失败。
     */
    public ApiResult<Void> preheat(Long skuId) {
        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs == null) {
            return ApiResult.fail(409, "未启用 Redis 主路径（请用 --spring.profiles.active=redis 启动）");
        }
        // 先失效缓存，再读最新 SKU（防预热推了旧库存）；getSku 内部会回源 DB 拿最新值
        skuCacheService.evict(skuId);
        ActivitySku sku = skuCacheService.getSku(skuId);
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        rs.preheat(sku.getActivityId(), sku.getId(), sku.getStock());
        return ApiResult.ok(null);
    }

    /** 查询某 SKU 的库存情况，便于手动验证与压测后核对。 */
    public ApiResult<StockInfo> stockInfo(Long skuId) {
        ActivitySku sku = skuCacheService.getSku(skuId);
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        StockInfo info = new StockInfo();
        info.setSkuId(skuId);
        info.setTotalStock(sku.getTotalStock());
        info.setStock(sku.getStock());
        info.setSold(sku.getSold());
        return ApiResult.ok(info);
    }
}

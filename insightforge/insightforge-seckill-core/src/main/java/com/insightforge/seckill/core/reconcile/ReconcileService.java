package com.insightforge.seckill.core.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.redis.RedisStockService;
import com.insightforge.seckill.core.state.OrderStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对账服务（Phase 6）——系统「账对不对」的唯一裁判。
 *
 * 两类对账：
 *  - {@link #reconcileStockConsistency(long)} ：上线前「MySQL↔Redis 库存一致性」。
 *    遍历活动的每个 SKU，比较 DB 剩余库存与 Redis 剩余库存。Redis 不可用（local profile）
 *    时跳过 Redis 比对，降级为「DB 内部合法性」校验（stock∈[0,total] 且 sold=total-stock）。
 *  - {@link #reconcileFinalStock(long)} ：结算「最终库存 = 初始 - 成功单数」。
 *    成功单 = 该 SKU 下 status != CANCEL 的订单数（下单即成功；CANCEL 已回补库存不计入）。
 *
 * 为什么对账要做成独立 Service、而不是直接塞进扣减逻辑？
 *   对账是「离线 / 管理面」操作（上线前闸门、活动结束结算、定时巡检），不是用户抢购热路径。
 *   它与高并发扣减解耦，才能在不影响吞吐量的情况下反复、可观测地跑（也方便 Phase 7+ 的 Agent 复盘）。
 */
@Service
public class ReconcileService {

    private final ActivitySkuMapper skuMapper;
    private final OrdersMapper ordersMapper;
    /** Redis 库存服务：用 ObjectProvider 拿，local profile 下为 null（不存在该 Bean）。 */
    private final ObjectProvider<RedisStockService> redisStockService;

    public ReconcileService(ActivitySkuMapper skuMapper,
                            OrdersMapper ordersMapper,
                            ObjectProvider<RedisStockService> redisStockService) {
        this.skuMapper = skuMapper;
        this.ordersMapper = ordersMapper;
        this.redisStockService = redisStockService;
    }

    /**
     * 维度一：MySQL ↔ Redis 库存一致性（上线前对账 / 定时巡检）。
     *
     * @return 每个 SKU 一条明细；Redis 不可用时整体 passed 仍可能为 true（降级为 DB 内部校验）。
     */
    public ReconcileReport reconcileStockConsistency(long activityId) {
        ReconcileReport report = new ReconcileReport();
        List<ActivitySku> skus = skuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("activity_id", activityId));

        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs == null) {
            // local profile：没有 Redis，跳过双写比对，只校验 DB 内部库存自洽。
            report.add(new ReconcileItem("mysql-redis",
                    true, "Redis 不可用（local profile）：跳过 MySQL↔Redis 比对，降级校验 DB 内部库存"));
            for (ActivitySku sku : skus) {
                boolean dbOk = sku.getStock() != null && sku.getStock() >= 0
                        && sku.getTotalStock() != null && sku.getStock() <= sku.getTotalStock()
                        && sku.getSold() != null
                        && (sku.getTotalStock() - sku.getStock()) == sku.getSold();
                report.add(new ReconcileItem("db-internal:" + sku.getId(), dbOk,
                        String.format("total=%d, stock=%d, sold=%d",
                                sku.getTotalStock(), sku.getStock(), sku.getSold())));
            }
            return report;
        }

        // 非 local：真刀真枪比 MySQL 与 Redis 的剩余库存。
        // 注意 Redis 未预热时 getStock 返回 -1，会判为不一致 → 阻断上线（宁可错杀不放过）。
        for (ActivitySku sku : skus) {
            int redisStock = rs.getStock(activityId, sku.getId());
            boolean ok = redisStock == sku.getStock();
            report.add(new ReconcileItem("mysql-redis:" + sku.getId(), ok,
                    String.format("db_stock=%d, redis_stock=%d", sku.getStock(), redisStock)));
        }
        return report;
    }

    /**
     * 维度二：结算对账（活动结束 SETTLING 时）。
     * 校验「最终剩余库存 = 初始库存 - 成功下单数」，从数学上证明没有超卖 / 少卖。
     */
    public ReconcileReport reconcileFinalStock(long activityId) {
        ReconcileReport report = new ReconcileReport();
        List<ActivitySku> skus = skuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("activity_id", activityId));

        for (ActivitySku sku : skus) {
            // 成功单 = 该 SKU 下「未被取消」的订单数（CANCEL 已回补库存，不算成功）
            long successOrders = ordersMapper.selectCount(
                    new QueryWrapper<Orders>()
                            .eq("activity_id", activityId)
                            .eq("sku_id", sku.getSkuId())
                            .ne("status", OrderStatus.CANCEL.name()));

            int expectedStock = sku.getTotalStock() - (int) successOrders;
            boolean ok = expectedStock == sku.getStock();
            report.add(new ReconcileItem("final-stock:" + sku.getId(), ok,
                    String.format("total=%d, successOrders=%d, expectedStock=%d, actualStock=%d",
                            sku.getTotalStock(), successOrders, expectedStock, sku.getStock())));
        }
        return report;
    }
}

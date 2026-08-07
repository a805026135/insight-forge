package com.insightforge.rule.engine;

/**
 * 分段库存服务抽象（大白话：一个 SKU 的库存被切成若干「段」seg_1、seg_2……）。
 *
 * 两条防超卖铁律在 {@code InMemoryStockSegmentService} / {@code RedisStockSegmentService} 两个实现里落地：
 * ① <b>调拨只建新段 seg_{N+1}</b>（O(1) 追加，绝不重排 / 改写已有段，不影响正在抢购的用户）；
 * ② <b>两阶段预留 pending</b>：调拨先 mark 一笔 pending（库存没真增加），确认才入账，
 *    失败/超时只删 pending——库存回到调拨前，<b>绝不超卖</b>。
 *
 * 接口化是为了「同一份库存底座可替换」：
 * - 默认（@Profile("!integration")）：{@code InMemoryStockSegmentService} 内存态（教学/沙箱零依赖）；
 * - 集成（@Profile("integration")）：{@code RedisStockSegmentService} 操作与秒杀<b>同一份</b> Redis 库存键
 *   （stock:a:{aid}:s:{skid} + pending_stock:a:{aid}:s:{skid}），REALLOCATE 原子写回，让 AI 调拨
 *   真的增加用户可抢的库存——「Agent 控盘与用户抢购争用同一库存」由此成立。
 */
public interface StockSegmentService {

    /** 调拨结果（新建的段号与调拨量）。 */
    record AllocResult(int newSegment, int amount) {
    }

    /**
     * 调拨：只建新段 seg_{N+1}，同时记 pending（两阶段预留第一阶段）。
     *
     * @return 新建的段号与调拨量
     */
    AllocResult allocateNewSegment(long activityId, long skuId, int amount);

    /** 两阶段预留第二阶段：确认调入，撤销 pending（库存正式对用户可用）。 */
    void confirmPending(long activityId, long skuId, int amount);

    /** 补偿：调入取消——移除新段 + 撤销 pending（库存回到调拨前，绝不超卖）。 */
    void releasePending(long activityId, long skuId, int amount);

    /** 用户可抢的可用库存 = 总库存 − 预占（pending 期间用户不能抢调拨中的库存）。 */
    int available(long activityId, long skuId);

    int segmentCount(long activityId, long skuId);

    int total(long activityId, long skuId);

    /** 清空（测试隔离用）。 */
    void reset();
}

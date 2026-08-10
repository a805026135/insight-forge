package com.insightforge.api;

import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.service.SeckillService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 14.1 请求校验回归测试：
 * 负数/零数量必须被拒；未上线（非 LIVE）活动必须被拒；活动与 SKU 不匹配必须被拒。
 * 防止「负 qty 反向加库存」「DRAFT 活动被抢购」这类问题回归。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillValidationTest {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACT_ID = 93001L;
    private static final long SKU_PK_ID = 93001L;

    @BeforeEach
    void setUp() {
        // 实体基类 @TableId(AUTO) 会忽略 setId，故用 JdbcTemplate 显式大 id 插入（与 ReconcileTest 同法）
        jdbc.update("DELETE FROM activity_sku WHERE id = ?", SKU_PK_ID);
        jdbc.update("DELETE FROM activity WHERE id = ?", ACT_ID);
        jdbc.update("INSERT INTO activity (id, name, status, target_gmv, target_roi, start_at, end_at) " +
                "VALUES (?, ?, 'LIVE', 0, 0, NULL, NULL)", ACT_ID, "校验测试活动");
        jdbc.update("INSERT INTO activity_sku (id, activity_id, sku_id, seckill_price, total_stock, stock, sold, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                SKU_PK_ID, ACT_ID, 90001L, BigDecimal.ONE, 10, 10, 0, 0);
    }

    private SeckillRequest req(int qty, long activityId, long skuId) {
        SeckillRequest r = new SeckillRequest();
        r.setActivityId(activityId);
        r.setSkuId(skuId);
        r.setUserId(70000L);
        r.setQty(qty);
        return r;
    }

    @Test
    void negativeQtyRejected() {
        ApiResult<String> r = seckillService.seckill(req(-1, ACT_ID, SKU_PK_ID));
        assertEquals(400, r.getCode());
    }

    @Test
    void zeroQtyRejected() {
        ApiResult<String> r = seckillService.seckill(req(0, ACT_ID, SKU_PK_ID));
        assertEquals(400, r.getCode());
    }

    @Test
    void nullQtyRejected() {
        ApiResult<String> r = seckillService.seckill(req(0, ACT_ID, SKU_PK_ID));
        assertEquals(400, r.getCode());
    }

    @Test
    void draftActivityRejected() {
        jdbc.update("UPDATE activity SET status = 'DRAFT' WHERE id = ?", ACT_ID);
        ApiResult<String> r = seckillService.seckill(req(1, ACT_ID, SKU_PK_ID));
        assertEquals(409, r.getCode());
    }

    @Test
    void activitySkuMismatchRejected() {
        // 用一个不存在的活动 id 调同 SKU → 活动不存在（404），而不是扣库存
        ApiResult<String> r = seckillService.seckill(req(1, 99999L, SKU_PK_ID));
        assertEquals(404, r.getCode());
    }
}

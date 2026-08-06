package com.insightforge.api.explain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可解释性模板引擎单测（Phase 10）。
 *
 * 纯 JUnit、不依赖 Spring/DB——因为它本就是纯函数。
 * 重点验证「动作 → 人话」映射正确，且风险措辞、数量、售价都对得上，
 * 保证大屏解释文案不会张冠李戴（这也是对「正确失败」语义的可测试保障）。
 */
class ExplainabilityServiceTest {

    private final ExplainabilityService service = new ExplainabilityService();

    @Test
    void reallocateTemplateMentionsAmountAndRisk() {
        ExplainInput in = new ExplainInput(1L, 101L, "库存水位", "0.32", "0.2",
                "REALLOCATE", 50, null, "中");
        String text = service.explain(in);
        assertTrue(text.contains("SKU-101"), "应点名 SKU");
        assertTrue(text.contains("调入 50 件"), "调拨数量要对");
        assertTrue(text.contains("风险等级：中"), "风险措辞要对");
        assertTrue(text.contains("0.32"), "实测值要对");
        assertTrue(text.contains("0.2"), "阈值要对");
    }

    @Test
    void repriceTemplateMentionsPrice() {
        ExplainInput in = new ExplainInput(1L, 101L, "流速", "2.1", "1.5",
                "REPRICE", null, new BigDecimal("89.90"), "中");
        String text = service.explain(in);
        assertTrue(text.contains("调价"), "应说明是调价");
        assertTrue(text.contains("¥89.90"), "调后售价要对");
    }

    @Test
    void refuseTemplateSignalsFailClose() {
        ExplainInput in = new ExplainInput(1L, 101L, "毛利率", "0.01", "0.05",
                "REFUSE", null, null, "高");
        String text = service.explain(in);
        assertTrue(text.contains("拒绝执行"), "REFUSE 必须明确表示拒绝");
        assertTrue(text.contains("Fail-Close"), "必须点出铁律");
    }

    @Test
    void nullInputReturnsFriendlyText() {
        String text = service.explain(null);
        assertNotNull(text);
        assertTrue(text.contains("无需解释"));
    }

    @Test
    void defaultActionFallsThrough() {
        ExplainInput in = new ExplainInput(1L, 101L, "X", "1", "1", "ALLOW", null, null, "低");
        String text = service.explain(in);
        assertEquals("SKU-101 的X处于 1，未触发任何护栏，维持原策略。风险等级：低。", text);
    }
}

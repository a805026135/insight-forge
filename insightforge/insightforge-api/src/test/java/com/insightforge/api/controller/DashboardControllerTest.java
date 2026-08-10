package com.insightforge.api.controller;

import com.insightforge.api.explain.ExplainInput;
import com.insightforge.api.explain.ExplainabilityService;
import com.insightforge.api.observability.ObservabilityService;
import com.insightforge.api.observability.dto.DashboardSnapshot;
import com.insightforge.api.observability.dto.TraceView;
import com.insightforge.common.result.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DashboardController 测试（Phase 10）。
 *
 * 用 {@code @SpringBootTest + @AutoConfigureMockMvc + local profile} 跑完整上下文
 * （H2 内存库 + MyBatis，mapper 才能正常装配），把 ObservabilityService / ExplainabilityService
 * 用 {@code @MockBean} 替掉——这样既能用 MockMvc 打 HTTP，又不依赖真实 DB 数据、断言稳定。
 * 验证：总览接口回包体、trace 列表回包体、explain 接口把动作翻成人话。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObservabilityService observabilityService;

    @MockBean
    private ExplainabilityService explainabilityService;

    @Test
    void overviewReturnsWrappedSnapshot() throws Exception {
        DashboardSnapshot snapshot = new DashboardSnapshot(
                new DashboardSnapshot.SeckillSummary(1, 1, 100, 30, 30, new java.math.BigDecimal("189000")),
                new DashboardSnapshot.AgentSummary(10, 8, 1, 1, 0.1, 1200, 0.02),
                new DashboardSnapshot.GuardrailSummary(2, 1, "中"),
                "now");
        when(observabilityService.buildSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.seckill.totalStock").value(100))
                .andExpect(jsonPath("$.data.agent.correctFailureRate").value(0.1));
    }

    @Test
    void tracesReturnsList() throws Exception {
        TraceView v = new TraceView("t1", 1L, "SUCCESS", null, false, null, null,
                100L, null, "local", "规划成功并落库策略", null);
        when(observabilityService.recentTraces(20)).thenReturn(List.of(v));

        mockMvc.perform(get("/api/dashboard/traces?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].traceId").value("t1"))
                .andExpect(jsonPath("$.data[0].verdict").value("规划成功并落库策略"));
    }

    @Test
    void explainReturnsHumanText() throws Exception {
        ExplainInput in = new ExplainInput(1L, 101L, "库存水位", "0.32", "0.2",
                "REALLOCATE", 50, null, "中");
        when(explainabilityService.explain(in))
                .thenReturn("因 SKU-101 的库存水位达到 0.32（越过阈值 0.2），规则引擎自动执行「调拨」：从备用库存池调入 50 件。风险等级：中。");

        mockMvc.perform(get("/api/dashboard/explain")
                        .param("activityId", "1")
                        .param("action", "REALLOCATE")
                        .param("skuId", "101")
                        .param("metric", "库存水位")
                        .param("metricValue", "0.32")
                        .param("threshold", "0.2")
                        .param("amount", "50")
                        .param("riskLevel", "中"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("调入 50 件")));
    }

    @Test
    void guardrailsDelegatesToService() throws Exception {
        when(observabilityService.guardrails(1L)).thenReturn(List.of());
        mockMvc.perform(get("/api/dashboard/guardrails").param("activityId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}

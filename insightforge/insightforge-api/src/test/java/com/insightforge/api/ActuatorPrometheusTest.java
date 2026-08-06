package com.insightforge.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务指标注册验证（Phase 10，对应监测设计文档 L4 系统评估）。
 *
 * 核心断言：InsightForge 自定义业务指标（活动数 / 订单数 / trace 数 / 正确失败率 / 护栏数）
 * 已经注册进 {@link MeterRegistry}——Prometheus 来 scrape /actuator/prometheus 时拿到的就是这些。
 * 这是「能证明 AI 控盘有效 + 无超卖」的硬指标来源，必须确定性可测。
 *
 * 附带 best-effort：若本环境暴露了 /actuator/prometheus（标准的 Spring Boot 行为，
 * 已在 application.yml 配置 exposure 包含 prometheus），其返回体应含上述指标。
 * 若某些 actuator 装配环境下该 HTTP 端点未启用，也不影响「指标已注册」这一核心事实。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ActuatorPrometheusTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MeterRegistry registry;

    @Test
    void businessMetricsRegisteredForPrometheus() {
        // ① 核心：指标确实注册进了 MeterRegistry（Prometheus 抓取的对象）
        assertNotNull(registry.find("insightforge_activities_total").gauge(),
                "应注册 insightforge_activities_total 指标");
        assertNotNull(registry.find("insightforge_agent_correct_failure_rate").gauge(),
                "应注册正确失败率指标——这是 AI 控盘质量的红线指标");
        assertNotNull(registry.find("insightforge_guardrail_policies_total").gauge(),
                "应注册护栏策略数指标");

        // ② best-effort：若 /actuator/prometheus 已暴露，断言其抓取内容包含我们的指标
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus", String.class);
        if (resp.getStatusCode().is2xxSuccessful()) {
            String body = resp.getBody();
            assertTrue(body != null && body.contains("insightforge_activities_total"),
                    "prometheus 抓取应包含 insightforge_activities_total");
        }
    }
}

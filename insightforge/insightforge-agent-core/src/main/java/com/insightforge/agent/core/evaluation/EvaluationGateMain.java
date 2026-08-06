package com.insightforge.agent.core.evaluation;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评估闭环 CLI 入口（大白话：在 CI / 本地手动跑一次「评估及格线」）。
 *
 * 它把 Spring 上下文拉起来（非 Web、H2 内存库，与测试同源），取出 EvaluationHarness 跑一遍，
 * 打印报告；门槛不通过则 {@code System.exit(1)}——这就是 GitHub Actions 里「阈值失败阻断合并」的开关。
 *
 * 重要：本类只作为「main 入口」被 SpringApplication.run 使用，绝不实现 CommandLineRunner，
 * 否则会被 @SpringBootTest 当成测试上下文里的 Runner 自动执行、并在门槛不过时 System.exit 杀死测试 JVM。
 * 评估门槛的「可信验证」由 JUnit 的 EvaluationHarnessTest 完成（二者逻辑完全一致）。
 *
 * 调用方式（需测试 classpath 才能拿到 schema-agent.sql / data-agent.sql）：
 *   mvn -pl insightforge-agent-core -am test-compile exec:java \
 *       -Dexec.mainClass=com.insightforge.agent.core.evaluation.EvaluationGateMain
 *
 * 生产环境把 BusinessMetricsSource / SystemRunMetricsSource 换成读真实表的实现即可，门槛逻辑不变。
 */
@Configuration
@EnableAutoConfiguration
@ComponentScan("com.insightforge.agent.core")
@MapperScan("com.insightforge.persistence.mapper")
public class EvaluationGateMain {

    private static final Logger log = LoggerFactory.getLogger(EvaluationGateMain.class);

    /** 把报告拼成一段人话摘要（也方便 CI 日志直接读）。 */
    private static String summary(EvaluationReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("golden验收率", String.format("%.0f%%", r.goldenAcceptanceRate() * 100));
        m.put("失败样本回归通过率", String.format("%.0f%%", r.regressionPassRate() * 100));
        m.put("AI售罄率", r.abExperiment().ai().sellThroughRate() + " (+" + r.abExperiment().sellThroughLiftPct() + ")");
        m.put("AI毛利率", r.abExperiment().ai().grossMarginRate());
        m.put("A/B胜方", r.abExperiment().winner());
        m.put("定性结论", r.abExperiment().qualitative());
        m.put("正确失败率(红线)", r.systemMetrics().correctFailureRate());
        m.put("总运行数", r.systemMetrics().totalRuns());
        m.put("最大延迟(ms)", r.systemMetrics().maxLatencyMs());
        m.put("总成本(USD)", r.systemMetrics().totalCostUsd());
        StringBuilder sb = new StringBuilder("========== InsightForge 评估闭环报告 ==========\n");
        m.forEach((k, v) -> sb.append(String.format("  %-18s: %s%n", k, v)));
        sb.append("===============================================");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        SpringApplication app = new SpringApplication(EvaluationGateMain.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // 与测试同源的 H2 内存库 + SQL 初始化（schema-agent.sql / data-agent.sql 在测试 classpath）
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url",
                "jdbc:h2:mem:evalgate_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        props.put("spring.datasource.driver-class-name", "org.h2.Driver");
        props.put("spring.datasource.username", "sa");
        props.put("spring.datasource.password", "");
        props.put("spring.sql.init.mode", "always");
        props.put("spring.sql.init.schema-locations", "classpath:schema-agent.sql");
        props.put("spring.sql.init.data-locations", "classpath:data-agent.sql");
        props.put("spring.main.web-application-type", "none");
        app.setDefaultProperties(props);

        try (ConfigurableApplicationContext ctx = app.run(args)) {
            EvaluationHarness harness = ctx.getBean(EvaluationHarness.class);
            EvaluationReport report = harness.run();
            System.out.println(summary(report));
            if (!report.gatePassed()) {
                System.err.println("❌ EVAL GATE FAILED:");
                report.gateFailures().forEach(f -> System.err.println("   - " + f));
                System.exit(1);
            }
            System.out.println("✅ EVAL GATE PASSED");
        }
    }
}

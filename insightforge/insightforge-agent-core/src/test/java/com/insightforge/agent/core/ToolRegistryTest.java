package com.insightforge.agent.core;

import com.insightforge.agent.core.memory.ArtifactStore;
import com.insightforge.agent.core.tool.ParameterValidator;
import com.insightforge.agent.core.tool.ToolContext;
import com.insightforge.agent.core.tool.ToolRegistry;
import com.insightforge.agent.core.tool.ToolResult;
import com.insightforge.agent.core.tool.tools.HistoricalSalesAnalyzerTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具系统测试：注册表收纳、JSON Schema 参数校验（合规/违规）、工具真实执行并写 Artifact。
 */
@SpringBootTest(classes = AgentCoreTestConfig.class)
@ActiveProfiles("test")
class ToolRegistryTest {

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private HistoricalSalesAnalyzerTool analyzer;

    @Autowired
    private ArtifactStore artifactStore;

    @Test
    void registryCollectsAllTools() {
        assertEquals(3, registry.size());
        assertTrue(registry.contains("historical_sales_analyzer"));
        assertTrue(registry.contains("memory_lookup"));
        assertTrue(registry.contains("web_search"));
    }

    @Test
    void validArgsPassSchema() {
        Map<String, Object> args = Map.of("activityId", 1, "lookbackDays", 30);
        List<String> errors = ParameterValidator.validate(analyzer.parametersJson(), args);
        assertTrue(errors.isEmpty(), "合规参数不应有校验错误: " + errors);
    }

    @Test
    void invalidArgsFailSchema() {
        // 缺 lookbackDays + activityId 类型错误
        Map<String, Object> args = Map.of("activityId", "not-a-number");
        List<String> errors = ParameterValidator.validate(analyzer.parametersJson(), args);
        assertFalse(errors.isEmpty(), "违规参数必须被 Schema 校验拦下");
    }

    @Test
    void analyzerExecutesAgainstDbAndWritesArtifact() {
        ToolContext ctx = new ToolContext("trace-test-1", 1L, artifactStore, null);
        ToolResult result = analyzer.execute(Map.of("activityId", 1L, "lookbackDays", 30), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        // 种子数据：5 笔订单累计 qty=10+10+10+8+4=42
        assertEquals(42, data.get("historicalQty"));
        assertNotNull(artifactStore.get("analysis-1"), "分析结果应写入 ArtifactStore");
    }
}

package com.insightforge.agent.core.tool.tools;

import com.insightforge.agent.core.tool.Tool;
import com.insightforge.agent.core.tool.ToolContext;
import com.insightforge.agent.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联网检索工具（设计文档 §5.1 内置工具 web_search）——本 Phase 为本地桩。
 *
 * 诚实边界：沙箱无外网，真实环境应接 web_search MCP / 搜索 API（且自身也走 Phase 5 的限流 + 缓存）。
 * 这里返回确定性的占位竞品价，仅用于演示「定价 Agent 会参考竞品」的链路，绝不假装已联网。
 */
@Component
public class WebSearchTool implements Tool {

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "（本地桩）拉取竞品/市场实时价；生产接 web_search MCP/API";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "keyword": { "type": "string", "description": "检索关键词，如『iPhone 16 全网最低价』" }
                  },
                  "required": ["keyword"]
                }""";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String keyword = String.valueOf(args.get("keyword"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("competitorPrice", 6199.00); // 占位竞品价
        result.put("note", "本地桩：未联网，返回占位竞品价；生产接 web_search MCP/API");
        result.put("fetchedAt", "local-stub");
        return ToolResult.ok(result);
    }
}

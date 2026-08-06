package com.insightforge.agent.core.tool.tools;

import com.insightforge.agent.core.memory.MemoryRecord;
import com.insightforge.agent.core.memory.MemoryStore;
import com.insightforge.agent.core.tool.Tool;
import com.insightforge.agent.core.tool.ToolContext;
import com.insightforge.agent.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 记忆检索工具（设计文档 §5.1 内置工具 memory_lookup）。
 * 让 Agent 能从历史活动方案里找「相似打法」——规划期参考、相似活动复用。
 */
@Component
public class MemoryLookupTool implements Tool {

    private final MemoryStore memoryStore;

    public MemoryLookupTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String name() {
        return "memory_lookup";
    }

    @Override
    public String description() {
        return "从长期记忆检索相似历史活动方案（选品/定价/库存打法）";
    }

    @Override
    public String parametersJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "检索语义，如『数码品类 高客单 控盘预案』" },
                    "topK":  { "type": "integer", "description": "召回条数" }
                  },
                  "required": ["query", "topK"]
                }""";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String query = String.valueOf(args.get("query"));
        int topK = ((Number) args.get("topK")).intValue();
        List<MemoryRecord> records = memoryStore.search(query, topK);
        return ToolResult.ok(records);
    }
}

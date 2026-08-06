package com.insightforge.agent.core.tool;

import com.insightforge.agent.core.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（启动时收集所有 {@link Tool} Bean）。
 * runtime 按 name 取工具、把 specs() 暴露给模型（让模型知道能调哪些、参数长什么样）。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool t : toolBeans) {
            tools.put(t.name(), t);
        }
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public int size() {
        return tools.size();
    }

    /** 暴露给模型的工具说明书列表。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool t : tools.values()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.parametersJson()));
        }
        return specs;
    }
}

package com.insightforge.agent.core.tool;

/**
 * 工具执行结果。success=false 时 runtime 会把 error 作为观察回灌并记录 FAIL span。
 */
public class ToolResult {

    private final boolean success;
    private final Object data;
    private final String error;

    private ToolResult(boolean success, Object data, String error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error);
    }

    public boolean success() {
        return success;
    }

    public Object data() {
        return data;
    }

    public String error() {
        return error;
    }
}

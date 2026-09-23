package com.benxin.llm.core.tool;

import java.util.Map;

/**
 * 工具执行结果。
 *
 * @param content 回灌给模型的文本
 * @param error   是否为错误
 * @param meta    附加元数据（耗时、退出码、文件路径等），不进入模型上下文
 */
public record ToolResult(String content, boolean error, Map<String, Object> meta) {

    public ToolResult {
        content = content == null ? "" : content;
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static ToolResult ok(String content) {
        return new ToolResult(content, false, Map.of());
    }

    public static ToolResult ok(String content, Map<String, Object> meta) {
        return new ToolResult(content, false, meta);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true, Map.of());
    }

    public static ToolResult error(String content, Map<String, Object> meta) {
        return new ToolResult(content, true, meta);
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }
}
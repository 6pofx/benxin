package com.benxin.llm.core.tool;

import java.util.Map;

/**
 * 一次工具调用的历史记录（含结果），用于审计、回放与前端展示。
 */
public record ToolCallRecord(
        String toolUseId,
        String name,
        Map<String, Object> arguments,
        String result,
        boolean error,
        long durationMillis) {

    public ToolCallRecord {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        result = result == null ? "" : result;
    }

    public static ToolCallRecord of(ToolInvocation invocation, ToolResult result, long durationMillis) {
        return new ToolCallRecord(invocation.toolUseId(), invocation.toolName(), invocation.arguments(),
                result.content(), result.error(), durationMillis);
    }
}